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

import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import cn.oremarket.common.Fees;
import cn.oremarket.common.StockState;
import cn.oremarket.common.TradeSlippage;

/**
 * Server-side limit orders:
 * a BUY limit order triggers a buy when the stock price <= the trigger price; a SELL limit order triggers a sell when the stock price >= the trigger price.
 * The regular path checks once per marketTick after order-book matching; an order whose condition is already met at submission executes ON THE SPOT
 * (at the current system price, without waiting for the next marketTick); both paths share one log format and always carry the deviation field.
 */
public final class LimitOrderService {
    /** Deprecated: the quantity cap now comes from config item maxTradeQuantity (category 22). */
    @Deprecated
    public static final int MAX_QTY = 1_000_000;

    private LimitOrderService() {
    }

    public static void place(ServerPlayer player, Stock stock, boolean buy, int quantity, double price) {
        if (player == null || stock == null) {
            return;
        }
        MinecraftServer server = player.server;
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        if (stock.retired() || data.isCodeRetiredFlagged(stock.code())) {
            TradeService.feedbackKey(player, "oremarket.text.0459", false);
            return;
        }
        if (!data.unlocked(id, stock)) {
            TradeService.feedbackKey(player, "oremarket.text.0302", false);
            return;
        }
        // At submission the current price must be a positive finite value (no orders while quotes are unloaded / data is broken).
        StockState quoteNow = data.state(stock);
        if (quoteNow == null || !(quoteNow.price > 0) || !Double.isFinite(quoteNow.price)) {
            TradeService.feedbackKey(player, "oremarket.trade.err.no_market", false);
            return;
        }
        MarketSettings settings = MarketSettings.current();
        if (data.limitOrderCount(id) >= settings.limitOrderMax) {
            TradeService.feedbackKey(player, "oremarket.text.0408", false);
            return;
        }
        if (quantity <= 0 || quantity > settings.maxTradeQuantity || !(price > 0) || !Double.isFinite(price)) {
            TradeService.feedbackKey(player, "oremarket.text.0433", false);
            return;
        }
        double rate = Fees.additive(settings.taxRate(), settings.limitOrderFeeRate);
        if (buy) {
            long need = Fees.buyCost(price, quantity, rate);
            if (data.availableCoins(id) < need) {
                TradeService.feedbackKey(player, "oremarket.text.0304", false);
                return;
            }
        } else if (data.availableUnlocked(id, stock) < quantity) {
            TradeService.feedbackKey(player, "oremarket.text.0304", false);
            return;
        }
        LimitOrder order = new LimitOrder(data.nextOrderSequence(), id, player.getName().getString(),
                stock.code(), buy, price, quantity, data.marketTicks, data.tradeDay());
        data.limitOrders.add(order);
        data.setDirty();
        // If the trigger condition is already met at submission (buy: price <= trigger; sell: price >= trigger), fill immediately,
        // never wait for the next marketTick; the trade price is the current system price - the trigger price is only the condition gate, not the execution price.
        StockState state = data.state(stock.code());
        boolean triggerNow = state != null
                && EventEngine.suspendTicksLeft(data, stock.code()) <= 0
                && (buy ? state.price <= price + 1.0e-9 : state.price >= price - 1.0e-9);
        if (triggerNow) {
            execute(server, data, order, stock, state, true);
            if (order.quantity <= 0) {
                data.limitOrders.remove(order);
                data.setDirty();
                // execute() already sent the "limit fill: stock @ price" toast; do not repeat the immediate-fill notice here, otherwise one fill would stack two popups.
                return;
            }
            // Edge case (e.g. sellable quantity changed mid-way) left unfilled: the order stays and keeps matching via the delayed trigger next marketTick
        }
        // Placing a limit order = whole-sentence key + args, no more stitched Chinese fragments
        LogService.pushKey(player, data, LogType.SYSTEM,
                buy ? "oremarket.log.limit_placed_buy" : "oremarket.log.limit_placed_sell",
                stock.displayName(), quantity, String.format(Locale.ROOT, "%.2f", price));
        TradeService.feedbackKey(player, "oremarket.text.0306", true);
    }

    public static void cancel(ServerPlayer player, long orderId) {
        if (player == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(player.server);
        for (int i = 0; i < data.limitOrders.size(); i++) {
            LimitOrder order = data.limitOrders.get(i);
            if (order.id == orderId && order.owner.equals(player.getUUID())) {
                data.limitOrders.remove(i);
                data.setDirty();
                TradeService.feedbackKey(player, "oremarket.text.0305", true);
                return;
            }
        }
        TradeService.feedbackKey(player, "oremarket.text.0420", false);
    }

    /** Called each marketTick AFTER player order matching first; within the same tick, orders run in submission-sequence order. */
    public static void check(MinecraftServer server, MarketSavedData data, PushFrame frame) {
        if (data.limitOrders.isEmpty()) {
            return;
        }
        List<LimitOrder> snapshot = new ArrayList<>(data.limitOrders);
        if (snapshot.size() > 1) {
            snapshot.sort(Comparator.comparingLong(o -> o.createdSequence));
        }
        for (LimitOrder order : snapshot) {
            if (order.quantity <= 0) {
                continue;
            }
            Stock stock = StockRegistry.server().getByCode(order.code);
            StockState state = data.state(order.code);
            if (stock == null || state == null || stock.retired()
                    || data.isCodeRetiredFlagged(order.code)
                    || !(state.price > 0) || !Double.isFinite(state.price)) {
                continue;   // Delisted / missing quote / non-positive price -> do not trigger, wait for the feed to recover
            }
            if (frame != null ? frame.suspended(order.code)
                    : EventEngine.suspendTicksLeft(data, order.code) > 0) {
                continue;
            }
            boolean triggered = order.buy ? state.price <= order.triggerPrice + 1.0e-9
                    : state.price >= order.triggerPrice - 1.0e-9;
            if (!triggered) {
                continue;
            }
            execute(server, data, order, stock, state, false);
        }
        data.limitOrders.removeIf(o -> o.quantity <= 0);
        data.setDirty();
    }

    public static void expire(MinecraftServer server, MarketSavedData data) {
        MarketSettings settings = MarketSettings.current();
        long cutoff = data.tradeDay - settings.limitOrderMaxKeepDays;
        boolean changed = false;
        java.util.Map<UUID, Integer> online = new java.util.HashMap<>();
        for (int i = data.limitOrders.size() - 1; i >= 0; i--) {
            LimitOrder o = data.limitOrders.get(i);
            if (o.createdDay <= cutoff && o.quantity > 0) {
                data.limitOrders.remove(i);
                changed = true;
                if (notice(data, server, o, "oremarket.log.limit_expired")) {
                    online.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        if (changed) {
            data.setDirty();
            // No silent vanishing: online owners get a toast summary, offline owners a login catch-up.
            online.forEach((owner, n) -> OrderNoticeService.summary(server, owner, true, n));
        }
    }

    public static void cancelStock(MinecraftServer server, MarketSavedData data, String code) {
        boolean changed = false;
        java.util.Map<UUID, Integer> online = new java.util.HashMap<>();
        for (int i = data.limitOrders.size() - 1; i >= 0; i--) {
            LimitOrder o = data.limitOrders.get(i);
            if (o.code.equals(code)) {
                data.limitOrders.remove(i);
                changed = true;
                if (o.quantity > 0 && notice(data, server, o, "oremarket.log.limit_delisted")) {
                    online.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        if (changed) {
            data.setDirty();
            online.forEach((owner, n) -> OrderNoticeService.summary(server, owner, true, n));
        }
    }

    /**
     * One system-cancelled limit order: online owner gets a detail log line (and the caller counts
     * it into the summary toast); offline owner's cancellation enters the persisted login catch-up.
     * Locked coins / shares release with the order itself (locks are derived live from the order list).
     */
    private static boolean notice(MarketSavedData data, MinecraftServer server, LimitOrder o, String logKey) {
        Stock stock = StockRegistry.server().getByCode(o.code);
        String name = stock != null ? stock.displayName() : o.code;
        return OrderNoticeService.record(server, data, o.owner, true, logKey,
                name, o.buy ? "oremarket.text.0019" : "oremarket.text.0032",
                o.quantity, String.format(java.util.Locale.ROOT, "%.2f", o.triggerPrice));
    }

    /**
     * Trade execution.
     * The trade price starts from the current system price state.price and applies the same half-impact slippage as a market order; the trigger price is only the condition gate; the deviation goes into the fill log.
     * Deviation formula: buy = (trigger - actual) / trigger x 100%; sell = (actual - trigger) / trigger x 100%.
     */
    private static void execute(MinecraftServer server, MarketSavedData data, LimitOrder order,
                                Stock stock, StockState state, boolean immediate) {
        if (state == null || !(state.price > 0) || !Double.isFinite(state.price)
                || order == null || order.quantity <= 0
                || !(order.triggerPrice > 0) || !Double.isFinite(order.triggerPrice)) {
            return;     // An invalid price must NEVER fill at 0 (a money-printing path)
        }
        MarketSettings settings = MarketSettings.current();
        double rate = Fees.additive(settings.taxRate(), settings.limitOrderFeeRate);
        UUID id = order.owner;
        // The slippage notice must only follow a real fill; a retry caused by insufficient funds/shares must never masquerade as a fill.
        if (order.buy) {
            int qty = order.quantity;
            double execPrice = state.price;
            if (settings.tradeSlippage) {
                double slip = TradeSlippage.slipFraction(settings.tradeImpactFactor, 1.0, qty);
                execPrice = TradeSlippage.execPrice(state.price, slip, true);
            }
            long cost = Fees.buyCost(execPrice, qty, rate);
            int oldShares = data.totalShares(id, stock);
            double oldAvg = data.costOr(id, stock, execPrice);
            // MarketSavedData.lockedCoins is derived live from both order tables and contains this very pending limit order;
            // judging with the un-excluded figure demands twice the funds from the player - a limit BUY submitted with the full available balance
            // failed as 'insufficient funds' on every trigger and was silently cancelled after limitOrderFailRetries failures (the order vanished into thin air).
            // Locks of all OTHER orders / limit orders still count as before; excluding only oneself cannot cause overspend.
            if (data.availableCoinsExceptOrder(id, order.id) < cost) {
                if (++order.insufficientTicks >= settings.limitOrderFailRetries) {
                    cancelOrder(data, order, "oremarket.text.0432", id, server);
                } else {
                    data.setDirty();
                }
                return;
            }
            data.addBalance(id, -cost);
            data.addFrozen(id, stock, qty);
            data.setCost(id, stock,
                    Fees.weightedAverageBuyCost(oldShares, oldAvg, cost, qty), "buy");
            TradeService.applyShock(state, data, stock, qty, true, 1.0, true);
            order.quantity = 0;
            // Player-action trigger stats + particles (the counterparty is the system, so no self-trading)
            cn.oremarket.market.event.PlayerVolumeTracker
                    .recordBuy(stock.code(), id, qty, false);
            cn.oremarket.net.ParticleService
                    .playTradeByUuid(server, id, true);
            logDeviation(server, data, order, stock, id, execPrice);
            notifyKey(server, id, LogType.BUY, fillLogKey(immediate), fillLogArgs(order, stock, execPrice));
        } else {
            // Same as above: a limit SELL's own lock must not read as 'nothing to sell' (under the old rule a full-size limit sell could never fill).
            int sellable = data.availableUnlockedExceptOrder(id, stock, order.id);
            int qty = Math.min(order.quantity, Math.max(0, sellable));
            if (qty <= 0) {
                if (++order.insufficientTicks >= settings.limitOrderFailRetries) {
                    cancelOrder(data, order, "oremarket.text.0431", id, server);
                } else {
                    data.setDirty();
                }
                return;
            }
            double execPrice = state.price;
            if (settings.tradeSlippage) {
                double slip = TradeSlippage.slipFraction(settings.tradeImpactFactor, 1.0, qty);
                execPrice = TradeSlippage.execPrice(state.price, slip, false);
            }
            long gain = Fees.sellGain(execPrice, qty, rate);
            double avg = data.costOr(id, stock, execPrice);
            data.addShares(id, stock, -qty);
            data.creditSaleProceeds(id, gain);
            data.addRealized(id, stock, gain - qty * avg);
            if (data.totalShares(id, stock) <= 0) {
                data.clearPositionCost(id, stock, "clear");
            }
            TradeService.applyShock(state, data, stock, qty, false, 1.0, true);
            order.quantity -= qty;
            cn.oremarket.market.event.PlayerVolumeTracker
                    .recordSell(stock.code(), id, qty, false);
            cn.oremarket.net.ParticleService
                    .playTradeByUuid(server, id, false);
            logDeviation(server, data, order, stock, id, execPrice);
            notifyKey(server, id, LogType.SELL, fillLogKey(immediate), fillLogArgs(order, stock, execPrice));
        }
        LevelService.raise(data, id);
        order.insufficientTicks = 0;
        data.setDirty();
    }

    /**
     * Unified fill log (immediate and delayed fills share one format, only the key differs;
     * log key-ization: ship 'translation key + args' and the client renders it with the current-language template):
     * 'Limit order filled immediately: <stock> <direction> BUY, trigger price Y, actual fill price Z, deviation W%'.
     */
    private static String fillLogKey(boolean immediate) {
        return immediate ? "oremarket.log.limit_fill_immediate" : "oremarket.log.limit_fill";
    }

    private static Object[] fillLogArgs(LimitOrder order, Stock stock, double actualPrice) {
        double dev = order.buy
                ? (order.triggerPrice - actualPrice) / order.triggerPrice * 100.0
                : (actualPrice - order.triggerPrice) / order.triggerPrice * 100.0;
        return new Object[]{
                stock.displayName(),
                order.buy ? "oremarket.text.0019" : "oremarket.text.0032",
                String.format(Locale.ROOT, "%.2f", order.triggerPrice),
                String.format(Locale.ROOT, "%.2f", actualPrice),
                String.format(Locale.ROOT, "%+.2f", dev)};
    }

    /**
     * Deviation hint between trigger price and actual fill price:
     * buy deviation = (trigger - actual) / trigger x 100%; sell deviation = (actual - trigger) / trigger x 100%.
     * A positive value favors the player, a negative one disfavors; the line is logged only beyond limitOrderDeviationThreshold (default 5%).
     */
    private static void logDeviation(MinecraftServer server, MarketSavedData data, LimitOrder order,
                                     Stock stock, UUID id, double actualPrice) {
        double trigger = order.triggerPrice;
        if (!(trigger > 0) || !Double.isFinite(actualPrice)) {
            return;
        }
        double devPercent = order.buy
                ? (trigger - actualPrice) / trigger * 100.0
                : (actualPrice - trigger) / trigger * 100.0;
        double threshold = MarketSettings.current().limitOrderDeviationThreshold * 100.0;
        if (Math.abs(devPercent) <= threshold) {
            return;
        }
        notifyKey(server, id, LogType.SYSTEM, "oremarket.text.0524",
                String.format(Locale.ROOT, "%.2f", actualPrice),
                String.format(Locale.ROOT, "%.2f", trigger),
                String.format(Locale.ROOT, "%.2f", devPercent));
    }

    private static void cancelOrder(MarketSavedData data, LimitOrder order, String reasonKey,
                                    UUID id, MinecraftServer server) {
        int remaining = order.quantity;
        order.quantity = 0;
        data.limitOrders.remove(order);
        data.setDirty();
        // Cancellation reasons ship by key (the argument itself is a translation key; the client renders by key)
        LogService.pushKey(server, data, id, LogType.SYSTEM, "oremarket.log.limit_cancelled",
                reasonKey, order.code, remaining);
        // The log line alone is easy to miss: pop a toast for the online owner, queue the
        // persistent login catch-up for the offline one (locked coins / shares release with the order).
        if (server != null && server.getPlayerList().getPlayer(id) != null) {
            Stock stock = StockRegistry.server().getByCode(order.code);
            String name = stock != null ? stock.displayName() : order.code;
            OrderNoticeService.single(server, id, "oremarket.toast.limit.auto_cancelled",
                    name, String.valueOf(remaining));
        } else {
            data.noteOrderCancel(id, true, 1);
        }
    }

    private static void notifyKey(MinecraftServer server, UUID id, LogType type, String key, Object... args) {
        if (server == null || id == null || key == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player != null) {
            LogService.pushKey(player, MarketSavedData.get(server), type, key, args);
            // Only genuine fill logs send the fill toast; the slippage notice carries three arguments and must not reuse the "stock @ price" template.
            if (key.equals("oremarket.log.limit_fill") || key.equals("oremarket.log.limit_fill_immediate")) {
                if (args != null && args.length >= 4) {
                    TradeService.feedbackKey(player, "oremarket.limit.toast.fill", true,
                            String.valueOf(args[0]), String.valueOf(args[3]));
                }
            }
            return;
        }
        // Offline owner: the fill / deviation changed their coins or shares, so the outcome goes
        // into the persisted login queue instead of vanishing with the session-only log page.
        MarketSavedData data = MarketSavedData.get(server);
        if ((key.equals("oremarket.log.limit_fill") || key.equals("oremarket.log.limit_fill_immediate"))
                && args != null && args.length >= 4) {
            OrderNoticeService.notice(server, data, id,
                    "oremarket.toast.offline.limit_fill", true, args[0], args[3]);
        } else if (key.equals("oremarket.text.0524") && args != null && args.length >= 3) {
            OrderNoticeService.notice(server, data, id, key, false, args);
        }
    }

    public static List<LimitOrder> ordersFor(MarketSavedData data, UUID id) {
        List<LimitOrder> out = new ArrayList<>();
        for (LimitOrder order : data.limitOrders) {
            if (order.owner.equals(id)) {
                out.add(order);
            }
        }
        return out;
    }
}
