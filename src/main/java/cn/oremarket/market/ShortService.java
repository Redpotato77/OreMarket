/*
 * oremarket - Minecraft Forge Mod
 * Copyright (C) 2026 Redpotato77
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package cn.oremarket.market;

import cn.oremarket.common.DebugLog;
import cn.oremarket.common.Fees;
import cn.oremarket.common.LogType;
import cn.oremarket.common.ShortSettlement;
import cn.oremarket.common.StockState;
import cn.oremarket.common.TradeSlippage;
import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.event.MarketModifiers;
import cn.oremarket.market.log.LogService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Short selling: opening, closing, daily interest and forced liquidation; every money movement runs on the server main thread only.
 *
 * <p>Three finalised rules (short-pool overhaul):
 * <ol>
 * <li><b>Short credit quota abolished entirely</b>: openings are no longer limited by credit quota x shortCreditRatio,
 * replaced by player wallet balance + a per-stock server-wide short pool (pure share count). Every check that
 * referenced the short quota has been deleted.</li>
 * <li><b>Locked margin frozen at opening</b>: at the instant of opening, lockedMargin = margin + fees/taxes is computed
 * once and written to NBT; no later logic may recompute it from the live price. Refunds at closing and daily-interest
 * charges all operate on this locked pool.</li>
 * <li><b>Double-checked concurrency at opening</b>: the wallet-balance and pool-remaining checks, the deduction, the pool
 * drawdown and the position booking all happen inside the same {@link #SHORT_LOCK} critical section -- two concurrent
 * open requests can never both pass validation and then drive the pool negative or over-deduct the wallet.</li>
 * </ol>
 */
public final class ShortService {
    /** Deprecated: the short quantity cap now uses the maxTradeQuantity config entry (category 22). */
    @Deprecated
    private static final int MAX_QUANTITY = 1_000_000;

    /**
     * The shared critical-section lock covering open-side deductions, close-side pool refunds and liquidation pool refunds.
     * Server business runs on the main thread; this guard covers two entry points crossing within the same tick and any future
     * threading work. The locked region only touches in-memory maps and accounts -- no network or IO -- so it never stalls a tick.
     */
    public static final Object SHORT_LOCK = new Object();

    private ShortService() {
    }

    // ------------------------------------------------------------------ opening

    /**
     * Open a short position.
     *
     * <p>[BUSINESS] The player borrows shares from the system at the current market price and sells them: the wallet pays
     * margin + opening fees/taxes into the locked pool, and the short pool shrinks by the borrowed share count.
     * Validation order = state gates -> quantity/quote -> locked amount -> double-checked concurrency inside the critical section.
     * <p>[WORKED EXAMPLE] price 10, borrow 100 shares, margin ratio 1.5, combined fee+tax 3%:
     * borrowed value 1000; margin = ceil(1000 x 1.5) = 1500; fees = ceil(1000 x 0.03) = 30;
     * wallet pays 1530, lockedMargin = 1530 (frozen), pool goes X -> X-100.
     */
    public static void open(ServerPlayer player, Stock stock, int requestedQuantity) {
        if (player == null || stock == null) {
            return;
        }
        final String trace = DebugLog.on() ? DebugLog.begin() : null;
        try {
            MinecraftServer server = player.server;
            MarketSavedData data = MarketSavedData.get(server);
            UUID id = player.getUUID();
            if (stock.retired() || data.isCodeRetiredFlagged(stock.code())) {
                intercept(player, "retired", stock);
                feedback(player, "oremarket.text.0389");
                return;
            }
            if (EventEngine.suspendTicksLeft(data, stock.code()) > 0) {
                intercept(player, "suspended", stock);
                feedback(player, "oremarket.text.0418");
                return;
            }
            if (!data.unlocked(id, stock)) {
                intercept(player, "locked_stock", stock);
                feedback(player, "oremarket.text.0302");
                return;
            }
            // Post-bankruptcy short cooldown: counted from the trading day AFTER the one containing the bankruptcy moment, and spans
            // full trading days of bankruptcyShortCooldown; during it new shorts are refused.
            long cooldownEnd = data.shortsCooldownEndDay(id);
            if (data.tradeDay() < cooldownEnd) {
                intercept(player, "bankrupt_cooldown", stock);
                feedbackKey(player, "oremarket.short.err.cooldown_days",
                        String.valueOf(cooldownEnd - data.tradeDay()));
                return;
            }
            int quantity = Math.min(MarketSettings.server().maxTradeQuantity, requestedQuantity);
            if (quantity <= 0) {
                intercept(player, "bad_quantity", stock);
                feedback(player, "oremarket.text.0416");
                return;
            }
            StockState state = data.state(stock);
            // Opening requires a positive, finite price (0 / NaN / Inf / missing = quotes not loaded;
            // locking an opening price of 0 would make P&L always 0 and freeze a 0 margin -- a money-minting path).
            if (state == null || !(state.price > 0) || !Double.isFinite(state.price)) {
                intercept(player, "bad_quote", stock);
                cn.oremarket.net.OreMarketNetwork
                        .pushError(player, "oremarket.error.quote_unavailable");
                return;
            }
            MarketModifiers.Per modifiers = currentModifiers(data).get(stock.code());
            MarketSettings settings = MarketSettings.current();
            double scarcityRate = settings.shortDailyRate;
            boolean scarcityApplied = false;
            double fee = Fees.effectiveFeeRate(modifiers.fee);
            // Opening a short is a real sell against the market. When short positions affect the quote,
            // charge the same half-impact at the fill that a system sell pays; otherwise the later quote
            // drop would be pure self-created P&L. If shortAffectPrice is off, there is no impact to charge.
            double openPrice = state.price;
            if (settings.shortAffectPrice) {
                double slip = TradeSlippage.slipFraction(settings.tradeImpactFactor, modifiers.shockMult, quantity);
                openPrice = TradeSlippage.execPrice(state.price, slip, false);
            }
            double raw = Fees.amount(openPrice, quantity);
            long margin = (long) Math.ceil(raw * settings.shortMarginRatio);
            long openTax = (long) Math.ceil(raw * Fees.combined(fee, settings.taxRate()));
            // ---------- The old short-quota system (credit quota x shortCreditRatio + margin-loan bonus)
            // has been deleted entirely. How many shares you may open is decided by two things only: (1) can the wallet pay margin + fees;
            // (2) does that stock's short pool still have that many shares. Both are double-checked inside the critical section below. ----------
            synchronized (SHORT_LOCK) {
                // Re-check every racy state inside the critical section (check -> deduct -> draw pool -> book position, atomically)
                ShortPosition old = data.shortPosition(id, stock);
                if (old != null) {
                    intercept(player, "duplicate_position", stock);
                    feedback(player, "oremarket.text.0497");
                    return;
                }
                // Legacy saves / stocks that never passed a listing event: create their pure-share pool on the spot from the stock's own base price (a hard NPE guarantee).
                data.ensureShortPool(stock.code(), stock.basePrice());
                int poolLeft = data.shortPoolLeftNow(stock.code());
                if (poolLeft <= 0) {
                    intercept(player, "pool_empty", stock);
                    feedbackKey(player, "oremarket.short.err.pool_empty");
                    MarketEngine.pushAfterAction(server, player);
                    return;
                }
                int poolMax = data.shortPoolMax(stock.code());
                scarcityApplied = cn.oremarket.common.ShortPoolCalculator
                        .scarce(poolLeft, poolMax);
                scarcityRate = cn.oremarket.common.ShortPoolCalculator
                        .scarceDailyRate(settings.shortDailyRate, poolLeft, poolMax,
                                settings.shortScarcityMultiplier);
                if (poolLeft < quantity) {
                    intercept(player, "pool_insufficient", stock);
                    feedbackKey(player, "oremarket.short.err.pool_insufficient",
                            String.valueOf(poolLeft), String.valueOf(quantity));
                    MarketEngine.pushAfterAction(server, player);
                    return;
                }
                long lockedTotal = margin + openTax;
                long walletBefore = data.availableCoins(id);
                if (walletBefore < lockedTotal) {
                    intercept(player, "insufficient_wallet", stock);
                    feedback(player, "oremarket.text.0396");
                    return;
                }
                if (!data.shortPoolConsume(stock.code(), quantity)) {
                    // Theoretically unreachable (the balance was verified above); the return-value check stays as the last gate:
                    // refuse the opening rather than let the pool go negative.
                    intercept(player, "race_pool", stock);
                    feedbackKey(player, "oremarket.short.err.pool_insufficient",
                            String.valueOf(data.shortPoolLeftNow(stock.code())), String.valueOf(quantity));
                    MarketEngine.pushAfterAction(server, player);
                    return;
                }
                data.addBalance(id, -lockedTotal);
                ShortPosition position = new ShortPosition(id, stock.code(), quantity, openPrice,
                        margin, openTax, settings.shortMarginRatio, settings.shortLiquidationRatio,
                        settings.shortLiquidationThreshold2, scarcityRate, data.tradeDay());
                if (!data.openShort(position)) {
                    // Data-layer duplicate guard is normally unreachable because this whole
                    // sequence is under SHORT_LOCK. Keep the rollback anyway: a future caller
                    // cannot lose coins or pool shares if it bypasses the service pre-check.
                    data.shortPoolReturn(stock.code(), quantity);
                    data.creditSaleProceeds(id, lockedTotal);
                    intercept(player, "duplicate_position_data_guard", stock);
                    feedback(player, "oremarket.text.0497");
                    return;
                }
                if (DebugLog.on()) {
                    DebugLog.info("oremarket.debug.log.short_open",
                            stock.displayName(), Long.toString(walletBefore), Long.toString(lockedTotal),
                            Integer.toString(quantity), Integer.toString(data.shortPoolLeftNow(stock.code())));
                }
            }
            // When enabled, opening applies sell-side impact; when disabled, shorting stays outside the shared quote.
            if (settings.shortAffectPrice) {
                TradeService.applyShock(state, data, stock, quantity, false, modifiers.shockMult, true);
            }
            LogService.pushKey(player, data, LogType.SELL, "oremarket.text.0545",
                    stock.displayName(), quantity,
                    String.format(java.util.Locale.ROOT, "%.2f", openPrice), margin,
                    String.format(java.util.Locale.ROOT, "%.1f", raw * fee),
                    String.format(java.util.Locale.ROOT, "%.1f", raw * settings.taxRate()));
            // Config-driven opening particles (1 block in front of the player)
            cn.oremarket.net.ParticleService
                    .playShortOpen(server, player);
            TradeService.feedbackKey(player, "oremarket.text.0468", true);
            if (scarcityApplied && scarcityRate > settings.shortDailyRate) {
                TradeService.feedbackKey(player, "oremarket.short.warn.scarce_rate", false,
                        String.format(java.util.Locale.ROOT, "%.1f", settings.shortScarcityMultiplier),
                        String.format(java.util.Locale.ROOT, "%.2f", scarcityRate * 100.0));
            }
            MarketEngine.pushAfterAction(server, player);
        } finally {
            if (trace != null) {
                DebugLog.end();
            }
        }
    }

    // ------------------------------------------------------------------ closing

    /** quantity == 0 means close everything; partial closes are supported (e.g. close 50%); share counts are always integers. */
    public static void close(ServerPlayer player, Stock stock, int requestedQuantity) {
        close(player, stock, requestedQuantity, 0L);
    }

    /**
     * Close (partial closes supported).
     *
     * <p>[BUSINESS] The player buys back and returns the borrowed shares: a non-final close releases only the
     * current refundable collateral in proportion to the shares closed from the remaining position, and the pool
     * receives back exactly the integer count actually closed. The final close refunds the entire remaining balance,
     * including all rounding crumbs; a partial close can never drain the collateral of shares that remain open.
     * <p>[WORKED EXAMPLE] with 100 shares and a current refundable balance of 700:
     * close 50 -> refund 350 and leave 350 with the 50 shares still open; close the last 50 -> refund the remaining 350.
     * <p>Every client close request must carry a positive server-issued position ID; zero is reserved for old saved positions before an ID is assigned and is never accepted from the network. A request larger than the held quantity is refused as a forged packet.
     */
    public static void close(ServerPlayer player, Stock stock, int requestedQuantity, long positionId) {
        if (player == null || stock == null) {
            return;
        }
        final String trace = DebugLog.on() ? DebugLog.begin() : null;
        try {
            MinecraftServer server = player.server;
            MarketSavedData data = MarketSavedData.get(server);
            UUID id = player.getUUID();
            ShortPosition position = data.shortPosition(id, stock);
            if (requestedQuantity < 0) {
                intercept(player, "negative_close_quantity", stock);
                feedbackKey(player, "oremarket.short.err.invalid_close_quantity");
                MarketEngine.pushAfterAction(server, player);
                return;
            }
            if (position == null || position.quantity <= 0) {
                feedback(player, "oremarket.text.0445");
                // Position no longer exists (possibly just liquidated) -- push the latest snapshot synchronously so the client refreshes the UI at once
                MarketEngine.pushAfterAction(server, player);
                return;
            }
            if (!ShortPosition.validCloseId(positionId) || !ShortPosition.validCloseId(position.id)
                    || position.id != positionId) {
                intercept(player, "invalid_or_stale_position_id", stock);
                TradeService.feedbackKey(player, "oremarket.short.err.stale", false);
                MarketEngine.pushAfterAction(server, player);
                return;
            }
            // Partial closes are allowed (50% etc.), but a forged packet asking to close MORE than held must be refused -
            // it must never mint extra locked-margin refunds or extra pool shares.
            if (requestedQuantity > position.quantity) {
                intercept(player, "over_quantity", stock);
                TradeService.feedbackKey(player, "oremarket.short.err.stale", false);
                MarketEngine.pushAfterAction(server, player);
                return;
            }
            if (EventEngine.suspendTicksLeft(data, stock.code()) > 0) {
                feedback(player, "oremarket.text.0358");
                return;
            }
            int quantity = requestedQuantity <= 0 ? position.quantity : Math.min(requestedQuantity, position.quantity);
            // The quote must be valid before closing. With currentPrice <= 0 / NaN closing is refused -- otherwise
            // P&L = (open price - 0) x quantity would spit out the entire margin as profit, a money-minting path.
            StockState quote = data.state(stock);
            if (quote == null || !(quote.price > 0) || !Double.isFinite(quote.price)
                    || quantity <= 0 || !Double.isFinite(position.openPrice) || position.openPrice <= 0) {
                cn.oremarket.net.OreMarketNetwork
                        .pushError(player, "oremarket.error.quote_unavailable");
                return;
            }
            long payout;
            synchronized (SHORT_LOCK) {
                // Inside the critical section re-verify the position still exists and its quantity was not changed by a concurrent liquidation
                ShortPosition fresh = data.shortPosition(id, stock);
                if (fresh != position || fresh.quantity < quantity) {
                    intercept(player, "race_close", stock);
                    TradeService.feedbackKey(player, "oremarket.short.err.stale", false);
                    MarketEngine.pushAfterAction(server, player);
                    return;
                }
                payout = closeInternal(server, data, position, stock, quantity, false, player);
                // The pool gets back an integer count of the shares ACTUALLY closed; 1.5 shares can never appear
                data.shortPoolReturn(stock.code(), quantity);
                if (DebugLog.on()) {
                    DebugLog.info("oremarket.debug.log.short_close",
                            stock.displayName(), Integer.toString(quantity), Long.toString(payout),
                            Integer.toString(data.shortPoolLeftNow(stock.code())));
                }
            }
            // Config-driven closing particles (1 block behind the player)
            cn.oremarket.net.ParticleService
                    .playShortClose(server, player);
            MarketEngine.pushAfterAction(server, player);
        } finally {
            if (trace != null) {
                DebugLog.end();
            }
        }
    }

    // ------------------------------------------------------------------ daily settlement and liquidation

    /**
     * Charge daily interest at every trading-day close, then immediately test liquidation condition two (including the 100% value line).
     *
     * <p>[BUSINESS] Daily interest is computed on the value LOCKED AT OPENING x the locked daily rate (independent of the live price)
     * and deducted in sync from the risk margin balance and the locked pool.
     * [WORKED EXAMPLE] opening value 1000, daily rate 3% -> charge 30 coins that day; margin 1500 -> 1470,
     * the locked pool's refundable balance moves -30 in sync; the displayed lockedMargin is unchanged.
     */
    public static void settleDailyInterest(MinecraftServer server, MarketSavedData data) {
        List<ShortPosition> positions = allPositions(data);
        for (ShortPosition position : positions) {
            Stock stock = StockRegistry.server().getByCode(position.code);
            StockState state = data.state(position.code);
            if (stock == null || state == null || position.quantity <= 0
                    || !(state.price > 0) || !Double.isFinite(state.price)
                    || !(position.openPrice > 0) || !Double.isFinite(position.openPrice)) {
                continue;   // With an invalid price, neither charge interest nor liquidate
            }
            long interest = (long) Math.ceil(position.openPrice * position.quantity * position.dailyInterestRate);
            position.chargeInterest(interest);
            if (DebugLog.on() && interest > 0) {
                DebugLog.info("oremarket.debug.log.short_interest",
                        position.code, Long.toString(interest), Long.toString(position.margin));
            }
            data.setDirty();
            // Use the same ordered trigger as the per-market-tick path. The price line is checked first;
            // only when it has not been reached can a genuine current-equity/maintenance-threshold breach
            // be reported as margin_below.
            String trigger = position.liquidationReason(state.price);
            if (trigger != null) {
                forceLiquidate(server, data, position, stock, shortReasonKey(trigger));
            }
        }
    }

    /**
     * Daily cross-day pool regeneration entry (only MarketEngine's trading-day close settlement may call it; strictly out of render/query paths).
     * [WORKED EXAMPLE] pool cap 1000, 0 left -> the day regenerates floor(1000 x 10%) = 100 shares.
     */
    public static void refillDailyPools(MarketSavedData data) {
        synchronized (SHORT_LOCK) {
            data.refillAllShortPools();
        }
    }

    /**
     * Liquidation check on every marketTick:
     * condition one = price reaches open price x shortLiquidationLine (default 130%); fires first in continuous trading;
     * condition two = current remaining margin (collateral plus floating P&L) < current value x shortLiquidationThreshold2,
     * the redundant insurance for instant price jumps (one candle straight past condition one).
     * Either one fires the liquidation; while suspended it waits to run after trading resumes. When shortAffectPrice is enabled,
     * a genuine price-line or insufficient-margin liquidation also applies one buy-side shock for the shares bought back; this can
     * legitimately push another short position over its line in the same market tick (a short squeeze). Delisting/bankruptcy cleanup
     * is not treated as a market buyback.
     */
    public static void checkForcedLiquidations(MinecraftServer server, MarketSavedData data,
                                               PushFrame frame) {
        for (ShortPosition position : allPositions(data)) {
            Stock stock = StockRegistry.server().getByCode(position.code);
            StockState state = data.state(position.code);
            if (stock == null || state == null || position.quantity <= 0
                    || !(state.price > 0) || !Double.isFinite(state.price)) {
                continue;   // No liquidation when the current price is missing or non-positive (wait for quotes to recover)
            }
            if (frame != null ? frame.suspended(position.code)
                    : EventEngine.suspendTicksLeft(data, position.code) > 0) {
                continue;
            }
            String trigger = position.liquidationReason(state.price);
            if (trigger != null) {
                forceLiquidate(server, data, position, stock, shortReasonKey(trigger));
            }
        }
    }

    /** Delisting / bankruptcy special case: no fees or taxes, and it never re-triggers the bankruptcy judgment. */
    public static void forceCloseAll(MinecraftServer server, MarketSavedData data, UUID id, String reason) {
        Map<String, ShortPosition> map = data.shortsOf(id);
        for (ShortPosition position : new ArrayList<>(map.values())) {
            Stock stock = StockRegistry.server().getByCode(position.code);
            if (stock != null) {
                forceLiquidate(server, data, position, stock, reason);
            } else {
                // When a stock had vanished from the registry the old code simply removeShort'd it,
                // and the margin locked on the position was never refunded (the player's coins evaporated). If a position cannot settle
                // against a quote
                // settle it at the open price: P&L 0, full margin refunded -- never swallow it.
                settleForced(server, data, position, null, position.openPrice, position.code, reason);
            }
        }
    }

    /** On delisting, force-close every short of one stock at the last price; the stock is still in the server's active registry at call time. */
    public static void forceCloseCode(MinecraftServer server, MarketSavedData data, String code, String reason) {
        Stock stock = StockRegistry.server().getByCode(code);
        for (Map.Entry<UUID, Map<String, ShortPosition>> entry
                : new HashMap<>(data.shorts).entrySet()) {
            ShortPosition position = entry.getValue().get(code);
            if (position == null) continue;
            if (stock != null) {
                forceLiquidate(server, data, position, stock, reason);
            } else {
                settleForced(server, data, position, null, position.openPrice, code, reason);
            }
        }
    }

    private static void forceLiquidate(MinecraftServer server, MarketSavedData data,
                                       ShortPosition position, Stock stock, String reason) {
        StockState state = data.state(stock);
        // With missing quotes or a dirty price, settle AT THE OPEN PRICE (P&L 0, full margin refunded);
        // never repeat the old behaviour of quietly deleting the position and refunding nothing, swallowing the whole margin.
        double px;
        if (state != null && state.price > 0 && Double.isFinite(state.price)) {
            px = state.price;
        } else if (position.openPrice > 0 && Double.isFinite(position.openPrice)) {
            px = position.openPrice;
        } else {
            px = 0.0;                     // Even the open price is dirty: refund the margin only, compute no P&L at all
        }
        settleForced(server, data, position, stock, px,
                stock == null ? position.code : stock.displayName(), reason);
    }

    /**
     * Forced-liquidation settlement.
     *
     * <p>Finalised: the refund base moves from the risk margin balance to the locked pool's balance right then,
     * {@code lockedMargin - lockedReleased} -- the same convention as an active close;
     * P&L = (open price - settle price) x shares, and when the loss exceeds the balance it is taken from coins first,
     * with the shortfall converted into shortDebt.
     * [WORKED EXAMPLE] lockedMargin 1515, 30 already refunded (daily interest) -> liquidation pays 1485 + P&L;
     * the pool takes back the full remaining share count as an integer.
     */
    private static void settleForced(MinecraftServer server, MarketSavedData data,
                                     ShortPosition position, Stock stock, double px,
                                     String displayName, String reason) {
        boolean priceOk = ShortSettlement.validPrice(px) && ShortSettlement.validPrice(position.openPrice);
        long buyback = ShortSettlement.buybackCost(px, position.quantity);
        long pnl = priceOk ? ShortSettlement.pnl(position.openPrice, px, position.quantity) : 0L;
        long payout;
        long uncovered;
        final int closedQty = position.quantity;
        final double currentMarginBeforeSettlement = position.currentRemainingMargin(px);
        final double thresholdBeforeSettlement = position.liquidationThreshold(px);
        synchronized (SHORT_LOCK) {
            // The liquidation refund base = the refundable collateral pool's balance RIGHT THEN (opening locked value - all daily interest so far),
            // one convention with the active close; it is never re-multiplied by the live price.
            long poolBalance = position.margin;
            payout = priceOk ? saturatingAdd(poolBalance, pnl) : poolBalance;
            // No valid price to settle against: refund the balance only, book no P&L
            uncovered = 0L;
            if (payout > 0) {
                // Credit through the loan-aware entry: borrowed short margin returned from a
                // forced close must regain its exchange lock, just like stock-sale proceeds.
                data.creditSaleProceeds(position.owner, payout);
            } else if (payout < 0) {
                // The loss eats the whole balance and still falls short: take coins first, convert the remainder into shortDebt (counts into total debt).
                long need = payout == Long.MIN_VALUE ? Long.MAX_VALUE : -payout;
                long available = Math.max(0L, data.balance(position.owner));
                long take = Math.min(available, need);
                if (take > 0) {
                    data.addBalance(position.owner, -take);
                }
                uncovered = need - take;
                if (uncovered > 0) {
                    addShortDebt(data, position.owner, uncovered);
                }
            }
            data.addRealizedShortPnl(position.owner, position.code, pnl);
            // The position dies: settle the pool right then, push the audit counter to the cap (the collected loss is already carried by
            // addBalance / shortDebt,
            // keeping the identity sum(refunds) + sum(daily interest) + collected shortfall == lockedMargin + collected shortfall closed).
            position.consumeMargin(poolBalance);
            position.releaseLockedAll();
            data.removeShort(position.owner, position.code);
            // The pool takes back the full remaining share count as an integer (when the stock is already delisted the return is ignored)
            data.shortPoolReturn(position.code, closedQty);
            // A forced buyback caused by a live risk trigger is a real market-side buy and therefore moves the
            // quote exactly like an active close. Administrative cleanup (delisting/bankruptcy) must not create
            // a price shock after the stock has left normal trading.
            MarketSettings forcedSettings = MarketSettings.current();
            if (stock != null && ("oremarket.short.reason.price_line".equals(reason)
                    || "oremarket.short.reason.margin_below".equals(reason))
                    && forcedSettings.shortAffectPrice && closedQty > 0) {
                StockState live = data.state(stock);
                if (live != null && live.price > 0.0 && Double.isFinite(live.price)) {
                    TradeService.applyShock(live, data, stock, closedQty, true,
                            ShortSettlement.coverShockMultiplier(
                                    currentModifiers(data).get(stock.code()).shockMult,
                                    forcedSettings.shortCloseImpactMultiplier), false);
                }
            }
            if (DebugLog.on()) {
                DebugLog.info("oremarket.debug.log.short_liquidated",
                        displayName, reason, Long.toString(payout), Long.toString(pnl),
                        Integer.toString(closedQty), Integer.toString(data.shortPoolLeftNow(position.code)));
                DebugLog.info("oremarket.debug.log.short_trigger",
                        displayName, reason, String.format(java.util.Locale.ROOT, "%.4f", px),
                        String.format(java.util.Locale.ROOT, "%.2f", currentMarginBeforeSettlement),
                        String.format(java.util.Locale.ROOT, "%.2f", thresholdBeforeSettlement),
                        String.format(java.util.Locale.ROOT, "%.4f", position.liquidationPrice()));
            }
            // Margin-loan blow-up fallback: triggered only by a genuine short liquidation (price line / insufficient margin).
            // The close-shorts-before-repayment / bankruptcy path must never run it again, or the very margin loan being repaid would be charged twice.
            if (data.shortsOf(position.owner).isEmpty()
                    && ("oremarket.short.reason.price_line".equals(reason)
                    || "oremarket.short.reason.margin_below".equals(reason))) {
                callMarginLoan(server, data, position.owner);
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(position.owner);
        long loss = pnl < 0 ? (pnl == Long.MIN_VALUE ? Long.MAX_VALUE : -pnl) : 0L;
        if (player != null) {
            LogService.pushKey(player, data, LogType.SYSTEM, "oremarket.text.0525",
                    displayName, reason, buyback, Math.max(0L, payout));
            // Red forced event: states the liquidation reason and the loss amount (the log entry stays).
            TradeService.feedbackKey(player, "oremarket.short.toast.force_close", false, 80,
                    displayName, reason, String.valueOf(loss), String.valueOf(uncovered));
        } else if (!"oremarket.short.reason.pre_bankrupt".equals(reason)
                && !"oremarket.short.reason.maturity_bankrupt".equals(reason)) {
            // The owner was offline when the position was liquidated: the session-only log page
            // cannot reach them, so persist a login catch-up notice - never a silent settlement.
            // (Both bankruptcy paths already replay their own login notice covering this event.)
            OrderNoticeService.notice(server, data, position.owner,
                    "oremarket.toast.offline.short_liquidated", false,
                    displayName, reason, loss, uncovered);
        }
    }

    /** Attach the liquidation shortfall to the loan dossier's shortDebt (create a dossier if none exists; does not touch the outstanding principal). */
    private static void addShortDebt(MarketSavedData data, UUID id, long gap) {
        long add = Math.max(0L, gap);
        if (add <= 0) {
            return;
        }
        LoanData loan = data.loan(id);
        loan.shortDebt = cn.oremarket.common.LoanSchedule
                .clampL((double) loan.shortDebtDue() + add);
        data.setDirty();
    }

    /**
     * Margin-loan blow-up fallback: once every short position is cleared, deduct the unpaid principal from coins;
     * any shortfall becomes shortDebt, and the loan fields are zeroed (no longer occupying the quota, no longer accruing).
     */
    private static void callMarginLoan(MinecraftServer server, MarketSavedData data, UUID id) {
        LoanData loan = data.loanOrNull(id);
        if (loan == null || !loan.hasLoan() || loan.product != LoanData.PRODUCT_MARGIN) {
            return;
        }
        long owe = loan.payoff();
        long bal = Math.max(0L, data.balance(id));
        long take = Math.min(bal, owe);
        if (take > 0) {
            data.addBalance(id, -take);
        }
        long gap = owe - take;
        loan.shortDebt = cn.oremarket.common.LoanSchedule
                .clampL((double) loan.shortDebtDue() + Math.max(0L, gap));
        loan.resetLoan();
        loan.lastSettledDay = data.tradeDay();
        data.setDirty();
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            LogService.pushKey(player, data, LogType.SYSTEM,
                    "oremarket.loan.log.shortfall_margin", take, gap, loan.shortDebt);
            TradeService.feedbackKey(player, "oremarket.loan.toast.shortfall", false, 80,
                    String.valueOf(gap), String.valueOf(loan.shortDebt));
        } else if (gap > 0 || take > 0) {
            // Margin loan settled while the owner was offline: replay the outcome at the next login
            // instead of letting coins / debt change in silence.
            OrderNoticeService.notice(server, data, id,
                    "oremarket.toast.offline.margin_loan", false, take, gap, loan.shortDebt);
        }
    }

    /**
     * Closing-refund settlement (the LOCKED-POOL convention; the only entry for an active close, running inside the {@link #SHORT_LOCK} critical section).
     *
     * <p>[BUSINESS] Whatever fraction of the OPENED shares the player closes, that fraction of the LOCKED margin comes back
     * from the locked pool; the final close (remaining hits zero) refunds the pool's balance right then -- to the coin, with no
     * entry point for floating-point crumbs (999.99 / 1000.01). The risk ledger (margin) shrinks by the same share fraction.
     * <p>[WORKED EXAMPLE] lockedMargin 1500, opened 100 shares:
     * close 50 -> floor(1500 x 50 / 100) = 750; of the 50 left close 25 -> floor(1500 x 25 / 100) = 375;
     * close the last 25 to 0 -> refund the whole balance right then (1500 - 750 - 375 - all daily interest so far).
     * <p>P&L: quantity mode settles it only on the final tranche (floating P&L stays with the remaining position); withPnl mode settles each tranche with its refund.
     *
     * @return coins actually refunded by this call (may be negative = the collected shortfall; for the caller's log)
     */
    private static long closeInternal(MinecraftServer server, MarketSavedData data, ShortPosition position,
                                      Stock stock, int quantity, boolean forced, ServerPlayer player) {
        StockState state = data.state(stock);
        // The price must be a positive finite value; the open price / quantity must be valid.
        // If any is missing, the position and the margin do not move -- avoiding profit 0 while the margin is spat out as gain.
        if (state == null || quantity <= 0
                || !(state.price > 0) || !Double.isFinite(state.price)
                || !Double.isFinite(position.openPrice) || position.openPrice <= 0) {
            return 0L;
        }
        int before = position.quantity;
        if (before <= 0) {
            return 0L;     // Divisor protection: forbid divide-by-zero when the remaining borrowed shares are 0 before a close
        }
        boolean full = quantity >= before;
        long poolBalance = position.margin;
        // The final close refunds the whole current balance. A non-final close releases the
        // current balance by the closed/remaining quantity ratio, preserving collateral for
        // shares that stay open after daily interest and integer rounding.
        long released = full
                ? poolBalance
                // A non-final close releases only the current collateral share of the
                // tranche. Using lockedMargin/originalQuantity here after daily interest
                // can consume the entire remaining pool while shares stay open, making
                // the next maintenance check liquidate a zero-margin remainder.
                : ShortSettlement.releasedRemaining(poolBalance, quantity, before);
        // Settlement price lock: P&L is computed against the quote the player saw when the close was
        // clicked (the same value the UI shows as the floating P&L). The cover's own buy shock lands
        // on the shared quote only AFTER settlement and scaled by shortCloseImpactMultiplier below,
        // so a large profitable close can never be flipped into a loss by its own buy-back.
        MarketSettings settings = MarketSettings.current();
        MarketModifiers.Per coverModifiers = currentModifiers(data).get(stock.code());
        double settlePrice = state.price;
        long pnl = ShortSettlement.pnl(position.openPrice, settlePrice, quantity);
        long payout;
        if (settings.shortReleaseWithPnl()) {
            // withPnl settles each tranche's P&L; quantity mode releases only the locked-margin share, including on a full close.
            payout = saturatingAdd(released, pnl);
        } else {
            payout = released;
        }
        if (!forced && payout < 0) {
            payout = 0L;   // An active close can lose at most this tranche's released amount (collecting shortfalls happens only in liquidation)
        }
        position.quantity -= quantity;
        position.consumeMargin(released);      // The refund draws down the collateral pool balance
        position.releaseLocked(released);      // Audit counter: record exactly what is refunded
        creditClosePayout(data, position.owner, payout);
        if (settings.shortReleaseWithPnl() && pnl != 0L) {
            data.addRealizedShortPnl(position.owner, position.code, pnl);
        }
        // The buy-back still moves the shared quote, but a cover is a passive repayment: its impact is
        // scaled by shortCloseImpactMultiplier (default 20% of a normal buy) and lands after settlement.
        if (settings.shortAffectPrice) {
            TradeService.applyShock(state, data, stock, quantity, true,
                    ShortSettlement.coverShockMultiplier(
                            coverModifiers.shockMult, settings.shortCloseImpactMultiplier), !forced);
        }
        if (position.quantity <= 0) {
            data.removeShort(position.owner, position.code);
        } else {
            data.setDirty();
        }
        if (!forced && player != null) {
            // The log shows business numbers only (the released amount on the locked-pool convention)
            LogService.pushKey(player, data, LogType.BUY, "oremarket.text.0542",
                    stock.displayName(), quantity,
                    String.format(java.util.Locale.ROOT, "%.2f", settlePrice), pnl, released);
            TradeService.feedbackKey(player, "oremarket.short.close_pnl", true, String.valueOf(pnl));
        }
        return payout;
    }

    /**
     * Loan-aware credit for a close payout: returned margin that was originally borrowed must
     * regain its exchange lock (the borrow -> short -> close -> exchange laundering leg), while a
     * negative payout (shortfall collection, forced paths) is still taken outright from the wallet.
     */
    private static void creditClosePayout(MarketSavedData data, UUID owner, long payout) {
        if (payout > 0L) {
            data.creditSaleProceeds(owner, payout);
        } else if (payout < 0L) {
            data.addBalance(owner, payout);
        }
    }

    private static String shortReasonKey(String trigger) {
        return "price_line".equals(trigger)
                ? "oremarket.short.reason.price_line"
                : "oremarket.short.reason.margin_below";
    }

    private static List<ShortPosition> allPositions(MarketSavedData data) {
        List<ShortPosition> result = new ArrayList<>();
        for (Map<String, ShortPosition> map : data.shorts.values()) {
            result.addAll(map.values());
        }
        return result;
    }

    private static MarketModifiers currentModifiers(MarketSavedData data) {
        MarketSettings ms = MarketSettings.current();
        return MarketModifiers.collect(data.events, ms.feeRate(), ms.meanReversionFactor,
                ms.priceLimitFraction());
    }

    /** Unified entry for interception logs: every refused open/close branch is 100% logged (zero concatenation when the switch is off). */
    private static void intercept(ServerPlayer player, String reason, Stock stock) {
        if (DebugLog.on()) {
            DebugLog.info("oremarket.debug.log.short_intercept",
                    reason, player == null ? "?" : player.getName().getString(),
                    stock == null ? "?" : stock.code());
        }
    }

    private static void feedback(ServerPlayer player, String key) {
        TradeService.feedbackKey(player, key, false);
    }

    private static void feedbackKey(ServerPlayer player, String key, String... args) {
        TradeService.feedbackKey(player, key, false, args);
    }

    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        if (b < 0 && a < Long.MIN_VALUE - b) {
            return Long.MIN_VALUE;
        }
        return a + b;
    }
}
