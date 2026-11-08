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

package cn.oremarket.market.event;

import cn.oremarket.common.EffectType;
import cn.oremarket.common.StockState;
import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.ShortPosition;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import cn.oremarket.market.TradeService;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger-once event effect settlement:
 * forced buy / forced sell / dividend / stock split each run once at the moment of event trigger; liquidity drain is a timed module (matching pause).
 *
 * Key invariants:
 * - split conservation: new price x new quantity == old price x old quantity (floating-point arithmetic + assertion; on breach, roll back and warn);
 * short positions: borrowed shares multiply and open/liquidation prices divide; coin collateral, refunds, tax and paid interest remain unchanged;
 * resting Limit Orders and player Order Book entries convert by the same ratio, preserving their locked value and economic size;
 * - dividend ex-rights: coins are paid while the price is force-reduced by the per-share dividend (kills the risk-free 'collect dividend, sell at the old price' arbitrage);
 * globally at most one dividend event per 30 game days;
 * - splits and dividends are banned on halted stocks;
 * - after every effect settlement the price floors at Math.max(0.01, price) .
 */
public final class EventEffectsService {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/EventEffects");

    /** Price floor (values <= 0 are forbidden). */
    public static final double PRICE_FLOOR = 0.01;

    private EventEffectsService() {
    }

    /** Called after an event enters the pool: executes its trigger-once modules. */
    public static void executeInstant(MinecraftServer server, MarketSavedData data, ActiveEvent event) {
        for (ActiveEvent.EffectModule m : event.modules) {
            switch (m.type) {
                case FORCE_BUY -> forceTrade(server, data, event, m, true);
                case FORCE_SELL -> forceTrade(server, data, event, m, false);
                case PRICE_MULT -> realizePriceShock(data, event, m);
                case DIVIDEND -> payDividend(server, data, event, m);
                case SPLIT -> split(server, data, event, m);
                default -> { }
            }
        }
    }

    // ------------------------------------------------------------ Price shock

    /**
     * PRICE_MULT is applied once, before the trigger announcement. Its stored parameter remains the
     * per-tick value for old configs/cards; exponentiating by the module duration preserves the total
     * movement while removing the free post-announcement countdown. The normal anchor band still caps
     * the resulting news gap.
     */
    private static void realizePriceShock(MarketSavedData data, ActiveEvent event,
                                           ActiveEvent.EffectModule module) {
        double perTick = MarketSavedData.clampEventMultiplier(module.param);
        double largestRealized = 0.0;
        for (String code : targetsOf(module, event)) {
            Stock stock = StockRegistry.getByCode(code);
            if (stock == null || EventEngine.suspendTicksLeft(data, code) > 0) {
                continue;
            }
            StockState state = data.state(stock);
            if (state == null || !(state.price > 0.0) || !Double.isFinite(state.price)) {
                continue;
            }
            MarketSettings settings = MarketSettings.server();
            double base = data.anchorBase(stock);
            double floor = settings.priceFloor(base);
            double ceiling = settings.priceCeiling(base);
            double next = cn.oremarket.common.PriceShockMath.apply(
                    state.price, perTick, Math.max(1, module.duration), floor, ceiling);
            if (next > 0.0 && Double.isFinite(next)) {
                double previousPrice = state.price;
                state.price = next;
                if (previousPrice > 0.0 && Double.isFinite(previousPrice)) {
                    largestRealized = Math.max(largestRealized, Math.abs(next / previousPrice - 1.0));
                }
                state.recordPriceChange(previousPrice, next);
                state.appendHistory(next);
                data.setDirty();
            }
        }
        // The announcement says what was actually realized, not the uncapped theoretical compounding.
        module.realizedValue = largestRealized == 0.0
                ? 0.0 : (perTick >= 1.0 ? largestRealized : -largestRealized);
    }

    // ------------------------------------------------------------ Forced buy / sell

    private static void forceTrade(MinecraftServer server, MarketSavedData data, ActiveEvent event,
                                   ActiveEvent.EffectModule m, boolean buy) {
        MarketSettings ms = MarketSettings.server();
        int cap = buy ? ms.eventForceBuyMaxShares : ms.eventForceSellMaxShares;
        // The config range currently starts at 10, but keep the runtime safe if a dirty config or future
        // reload supplies a smaller cap: never execute more shares than the configured maximum.
        int safeCap = Math.max(1, cap);
        int shares = (int) Math.max(1, Math.min(safeCap, m.param));
        for (String code : targetsOf(m, event)) {
            Stock stock = StockRegistry.getByCode(code);
            if (stock == null) {
                continue;   // The registry may already have delisted this stock.
            }
            StockState st = data.state(stock);
            if (st == null || EventEngine.suspendTicksLeft(data, code) > 0) {
                continue;   // No system orders on halted stocks
            }
            // System orders run through the impact formula only and are excluded from player volume stats (so player-action triggers cannot double-count them)
            TradeService.applyShock(st, data, stock, shares, buy, 1.0);
            st.price = Math.max(PRICE_FLOOR, st.price);
            data.setDirty();
            LogService.pushAllKey(server, data, LogType.EVENT,
                    buy ? "oremarket.event.log.system_buy" : "oremarket.event.log.system_sell",
                    event.nameOrKey(), stock.displayName(), shares);
        }
    }

    // ------------------------------------------------------------ Dividend

    private static void payDividend(MinecraftServer server, MarketSavedData data, ActiveEvent event,
                                    ActiveEvent.EffectModule m) {
        MarketSettings ms = MarketSettings.server();
        long day = data.tradeDay;
        m.realizedValue = 0.0;
        if (day - data.lastDividendDay < ms.eventDividendCooldownDays) {
            LOG.info("Dividend event blocked by {}-game-day cooldown: lastDividendDay={} day={}",
                    ms.eventDividendCooldownDays, data.lastDividendDay, day);
            return;
        }
        double perShare = Math.max(0.1, Math.min(ms.eventDividendMaxPerShare, m.param));
        boolean any = false;
        for (String code : targetsOf(m, event)) {
            Stock stock = StockRegistry.getByCode(code);
            if (stock == null || EventEngine.suspendTicksLeft(data, code) > 0) {
                continue;   // Dividends are banned on halted stocks
            }
            StockState st = data.state(stock);
            // The old rule price = max(PRICE_FLOOR, price - perShare) lost its anchor on low-priced stocks:
            // a 0.05-coin stock still paid 0.1 per share while the price could only fall to 0.01 - the extra payout was freshly printed.
            // Players could then net a riskless profit via 'buy before the dividend -> collect -> sell at the old price', whatever one thinks of dividend fairness.
            // The per-share payout is now clamped to (current price - price floor); if the price cannot absorb it, nothing is paid (the global dividend cooldown is not consumed).
            double quote = st == null ? 0.0 : st.price;
            double absorbable = (st != null && Double.isFinite(quote) && quote > PRICE_FLOOR)
                    ? quote - PRICE_FLOOR : 0.0;
            double paidPerShare = Math.min(perShare, absorbable);
            m.realizedValue = Math.max(m.realizedValue, paidPerShare);
            if (paidPerShare <= 0.0) {
                LOG.info("Dividend skipped for {}: price {} at/below floor, cannot absorb perShare {}",
                        code, String.format(java.util.Locale.ROOT, "%.4f", quote), perShare);
                continue;
            }
            data.lastDividendDay = day;
            any = true;
            long totalPaid = 0L;
            for (Map.Entry<UUID, Map<String, Integer>> holder : new ArrayList<>(data.holdings.entrySet())) {
                Integer shares = holder.getValue().get(code);
                if (shares == null || shares <= 0) {
                    continue;
                }
                long payout = (long) Math.floor(paidPerShare * shares);
                if (payout <= 0) {
                    continue;
                }
                data.addBalance(holder.getKey(), payout);   // System mints the payouts (the price cut below mirrors this amount exactly)
                totalPaid += payout;
                // Personal money movement: the global event line alone is not enough - holders
                // online get a toast, holders offline get the persisted login notice.
                cn.oremarket.market.OrderNoticeService.notice(
                        server, data, holder.getKey(), "oremarket.toast.dividend", true,
                        stock.displayName(), shares, payout);
            }
            // Ex-rights: cut both the live quote and its saved anchor by the same amount. The payout is
            // therefore a value transfer, not a temporary discount that mean reversion later mints back.
            if (st != null) {
                double oldAnchor = data.anchorBase(stock);
                double previousPrice = st.price;
                st.price = Math.max(PRICE_FLOOR, st.price - paidPerShare);
                st.recordPriceChange(previousPrice, st.price);
                if (oldAnchor > paidPerShare && Double.isFinite(oldAnchor)) {
                    data.scaleAnchor(code, (oldAnchor - paidPerShare) / oldAnchor);
                }
            }
            LogService.pushAllKey(server, data, LogType.EVENT,
                    "oremarket.event.log.dividend",
                    stock.displayName(), fmt(paidPerShare), totalPaid,
                    st == null ? "-" : fmt(st.price));
            data.setDirty();
        }
        if (any) {
            data.setDirty();
        }
    }

    // ------------------------------------------------------------ Stock split

    private static void split(MinecraftServer server, MarketSavedData data, ActiveEvent event,
                              ActiveEvent.EffectModule m) {
        MarketSettings ms = MarketSettings.server();
        m.realizedValue = 0.0;
        if (!ms.eventStockSplitEnabled) {
            return;
        }
        int ratio = ms.eventSplitRatio;
        // The configured ratio is the only truthful fallback when every target is skipped by a safety check.
        m.realizedValue = ratio;
        boolean changed = false;
        for (String code : targetsOf(m, event)) {
            Stock stock = StockRegistry.getByCode(code);
            if (stock == null || EventEngine.suspendTicksLeft(data, code) > 0) {
                continue;   // Splits are banned on halted stocks
            }
            StockState st = data.state(stock);
            if (st == null) {
                continue;
            }
            double oldPrice = st.price;
            double newPrice = oldPrice / ratio;
            // The global quote floor is part of the economy. If halving would cross it,
            // skip the entire split rather than clamping back up and creating value.
            if (!(oldPrice > PRICE_FLOOR) || !Double.isFinite(oldPrice)
                    || !Double.isFinite(newPrice) || newPrice < PRICE_FLOOR) {
                LOG.warn("Split skipped below price floor: {} oldPrice {} ratio {}", code, oldPrice, ratio);
                continue;
            }
            // Validate everything first without mutating: any quantity overflow / conservation failure skips the whole stock, so no half-applied split.
            boolean ok = true;
            for (Map.Entry<UUID, Map<String, Integer>> holder : data.holdings.entrySet()) {
                Integer qty = holder.getValue().get(code);
                if (qty == null || qty <= 0) {
                    continue;
                }
                if (qty > Integer.MAX_VALUE / ratio) {
                    ok = false;
                    break;
                }
                long oldNotional = (long) Math.floor(oldPrice * qty);
                long newNotional = (long) Math.floor(newPrice * qty * (long) ratio);
                double relError = oldNotional == 0 ? 0
                        : Math.abs(newNotional - oldNotional) / (double) oldNotional;
                if (relError > 1.0e-6) {
                    ok = false;
                    LOG.error("Split conservation assertion failed: {} qty {} oldNotional {} newNotional {}",
                            code, qty, oldNotional, newNotional);
                    break;
                }
            }
            if (ok) {
                for (Map<String, Integer> frozen : data.frozen.values()) {
                    Integer qty = frozen.get(code);
                    if (qty != null && qty > 0 && qty > Integer.MAX_VALUE / ratio) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                for (Map<String, ShortPosition> positions : data.shorts.values()) {
                    ShortPosition position = positions.get(code);
                    if (position != null && position.quantity > 0
                            && (position.quantity > Integer.MAX_VALUE / ratio
                            || position.originalQuantity > Integer.MAX_VALUE / ratio)) {
                        ok = false;
                        break;
                    }
                }
            }
            // Resting player and trigger orders are contracts written in pre-split share terms.
            // Validate them before the first mutation so a single overflow cannot leave positions,
            // locks and order books split inconsistently.
            if (ok) {
                for (cn.oremarket.market.PendingOrder order : data.pendingOrders) {
                    if (code.equals(order.code) && order.quantity > 0
                            && (order.quantity > Integer.MAX_VALUE / ratio
                            || !Double.isFinite(order.price) || !(order.price > 0.0)
                            || order.price / ratio < PRICE_FLOOR)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                for (cn.oremarket.market.LimitOrder order : data.limitOrders) {
                    if (code.equals(order.code) && order.quantity > 0
                            && (order.quantity > Integer.MAX_VALUE / ratio
                            || !Double.isFinite(order.triggerPrice) || !(order.triggerPrice > 0.0)
                            || order.triggerPrice / ratio < PRICE_FLOOR)) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) {
                LOG.warn("Split aborted (conservation check failed or quantity overflow): {}", code);
                continue;
            }
            // Once every check passes, mutate all position quantities in one go - no partial splits.
            for (Map.Entry<UUID, Map<String, Integer>> holder : data.holdings.entrySet()) {
                Integer qty = holder.getValue().get(code);
                if (qty != null && qty > 0) {
                    holder.getValue().put(code, qty * ratio);
                }
            }
            for (Map<String, Integer> frozen : data.frozen.values()) {
                Integer qty = frozen.get(code);
                if (qty != null && qty > 0) {
                    frozen.put(code, qty * ratio);
                }
            }
            // Cost basis is adjusted by the same ratio (floating P/L semantics unchanged)
            for (Map<String, Double> cb : data.costBasis.values()) {
                Double avg = cb.get(code);
                if (avg != null) {
                    cb.put(code, avg / ratio);
                }
            }
            // Short positions: borrowed shares and original open quantity multiply, while open and
            // liquidation prices divide. Collateral, refunds, tax and interest are coin balances
            // and remain unchanged; their accounting identity survives the contract conversion.
            for (Map.Entry<UUID, Map<String, ShortPosition>> e : new ArrayList<>(data.shorts.entrySet())) {
                ShortPosition p = e.getValue().get(code);
                if (p == null || p.quantity <= 0) {
                    continue;
                }
                p.applyStockSplit(ratio);
            }
            for (cn.oremarket.market.PendingOrder order : data.pendingOrders) {
                if (code.equals(order.code) && order.quantity > 0) {
                    order.applyStockSplit(ratio);
                }
            }
            for (cn.oremarket.market.LimitOrder order : data.limitOrders) {
                if (code.equals(order.code) && order.quantity > 0) {
                    order.applyStockSplit(ratio);
                }
            }
            double previousPrice = st.price;
            st.price = Math.max(PRICE_FLOOR, newPrice);
            st.recordPriceChange(previousPrice, st.price);
            // A split changes the quote unit, so its long-run anchor changes by the same ratio.
            // Otherwise the reversion engine would pull the halved quote back to the pre-split nominal.
            data.scaleAnchor(code, 1.0 / ratio);
            changed = true;
            m.realizedValue = ratio;
            // Every holder's share count was re-based: tell them explicitly (toast online,
            // login notice offline) - otherwise the position line "mutates" without a word.
            java.util.Set<UUID> affected = new java.util.LinkedHashSet<>();
            for (Map.Entry<UUID, Map<String, Integer>> e : data.holdings.entrySet()) {
                Integer q = e.getValue().get(code);
                if (q != null && q > 0) {
                    affected.add(e.getKey());
                }
            }
            for (Map.Entry<UUID, Map<String, Integer>> e : data.frozen.entrySet()) {
                Integer q = e.getValue().get(code);
                if (q != null && q > 0) {
                    affected.add(e.getKey());
                }
            }
            for (Map.Entry<UUID, Map<String, ShortPosition>> e : data.shorts.entrySet()) {
                ShortPosition p = e.getValue().get(code);
                if (p != null && p.quantity > 0) {
                    affected.add(e.getKey());
                }
            }
            for (UUID holder : affected) {
                cn.oremarket.market.OrderNoticeService.notice(
                        server, data, holder, "oremarket.toast.split", true,
                        stock.displayName(), fmt(oldPrice), fmt(st.price));
            }
            // K-line history is never re-based: raw data stays and new candles accumulate naturally at the new prices (StockState performs no rewrite)
            LogService.pushAllKey(server, data, LogType.EVENT,
                    "oremarket.event.log.split",
                    stock.displayName(), ratio, fmt(oldPrice) + " -> " + fmt(st.price));
            data.setDirty();
        }
        if (changed) {
            // Order-book clients cache prices and quantities. Push the converted resting orders immediately;
            // the next scheduled market packet only carries quotes, not the player order tables.
            syncSplitClients(server, data);
        }
    }

    // ------------------------------------------------------------ Liquidity drain query

    /** Push converted order state and personal locks to every player currently viewing the market. */
    private static void syncSplitClients(MinecraftServer server, MarketSavedData data) {
        if (server == null || data == null
                || cn.oremarket.market.MarketEngine.WATCHERS.isEmpty()) {
            return;
        }
        cn.oremarket.market.OrderBookService.pushAll(server, data);
        cn.oremarket.market.PushFrame frame =
                cn.oremarket.market.PushFrame.capture(data);
        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (cn.oremarket.market.MarketEngine.WATCHERS.contains(player.getUUID())) {
                cn.oremarket.net.OreMarketNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                        cn.oremarket.net.packet.S2CSnapshotPacket.forPlayer(
                                data, player.getUUID(), frame));
            }
        }
    }

    /** Whether the given stock currently has 'order matching paused' (queried once per tick by OrderBookService.match). */
    public static int liquidityDryTicksLeft(MarketSavedData data, String code) {
        if (data == null || code == null) {
            return 0;
        }
        int left = 0;
        for (ActiveEvent e : data.events) {
            for (ActiveEvent.EffectModule m : e.modules) {
                if (m.type == EffectType.LIQUIDITY_DRY && !m.expired() && m.affects(code)) {
                    left = Math.max(left, m.ticksLeft);
                }
            }
        }
        return left;
    }

    // ------------------------------------------------------------ Utilities

    private static java.util.Set<String> targetsOf(ActiveEvent.EffectModule m, ActiveEvent event) {
        return m.global || m.targets.isEmpty() ? event.targets : m.targets;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Pure-function check of split conservation (callable directly by unit tests; touches no game objects). */
    public static boolean conservationHolds(double oldPrice, long oldQty, double newPrice, long newQty) {
        double oldNotional = oldPrice * oldQty;
        double newNotional = newPrice * newQty;
        double rel = oldNotional == 0 ? Math.abs(newNotional) : Math.abs(newNotional - oldNotional) / oldNotional;
        return rel <= 1.0e-6;
    }
}
