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

import cn.oremarket.market.log.LogService;
import cn.oremarket.common.LogType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import cn.oremarket.common.Fees;
import cn.oremarket.common.StockState;

/** The players' Order Book; locked resources derive purely from order quantity and price, and are released automatically on fill, cancel or delisting. */
public final class OrderBookService {
    /** Deprecated: the quantity cap moved to the maxTradeQuantity config entry (category twenty-two). */
    @Deprecated
    public static final int MAX_QTY = 1_000_000;

    /** Order lifecycle log: both creation and removal are printed (server console). */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("OreMarket/OrderBook");

    private OrderBookService() {
    }

    public static void place(ServerPlayer player, Stock stock, boolean buy, int quantity, double price) {
        if (player == null || stock == null) {
            return;
        }
        MinecraftServer server = player.server;
        MarketSavedData data = MarketSavedData.get(server);
        UUID id = player.getUUID();
        if (stock.retired() || data.isCodeRetiredFlagged(stock.code())) {
            TradeService.feedbackKey(player, "oremarket.text.0390", false);
            return;
        }
        if (!data.unlocked(id, stock)) {
            TradeService.feedbackKey(player, "oremarket.text.0302", false);
            return;
        }
        // The current price must be positive and finite when placing an order (no quoting before market data loads; a broken feed blocks orders too).
        StockState quote = data.state(stock);
        if (quote == null || !(quote.price > 0) || !Double.isFinite(quote.price)) {
            TradeService.feedbackKey(player, "oremarket.trade.err.no_market", false);
            return;
        }
        if (quantity <= 0 || quantity > MarketSettings.current().maxTradeQuantity || !(price > 0) || !Double.isFinite(price)) {
            TradeService.feedbackKey(player, "oremarket.text.0393", false);
            return;
        }
        MarketSettings settings = MarketSettings.current();
        double orderRate = Fees.additive(settings.taxRate(), settings.orderFeeRate / 2.0);
        long coinNeed = Fees.buyCost(price, quantity, orderRate);
        if (buy) {
            if (data.availableCoins(id) < coinNeed) {
                TradeService.feedbackKey(player, "oremarket.text.0304", false);
                return;
            }
        } else {
            // Only ordinary sellable holdings (availableShares) may be locked; frozen holdings and Short Positions must never enter the Order Book.
            if (data.availableUnlocked(id, stock) < quantity) {
                TradeService.feedbackKey(player, "oremarket.text.0304", false);
                return;
            }
        }
        // Dedup: one Limit Order per (player, stock, side, price, quantity); a repeated submission is rejected outright.
        // The same loop also counts the player's open orders - there was **no per-player cap** before,
        // so forged C2S packets with a flood of tiny orders could grow the Order Book without bound (NBT bloat + Order Matching O(n^2) + full sync packet bloat).
        // New orders beyond the limit are rejected here; the wording uses the order_limit_reached key.
        int mineCount = 0;
        for (PendingOrder o : data.pendingOrders) {
            if (o.owner.equals(id)) {
                mineCount++;
                if (o.code.equals(stock.code()) && o.buy == buy
                        && o.quantity == quantity && Math.abs(o.price - price) < 1.0e-9) {
                    TradeService.feedbackKey(player, "oremarket.text.0289", false);
                    return;
                }
            }
        }
        if (mineCount >= MarketSettings.current().orderMax) {
            TradeService.feedbackKey(player, "oremarket.error.order_limit_reached", false);
            return;
        }
        PendingOrder fresh = new PendingOrder(data.nextOrderSequence(), id, player.getName().getString(),
                stock.code(), buy, quantity, price, data.marketTicks, data.tradeDay());
        data.pendingOrders.add(fresh);
        LOG.info("Order created: stock {}, side {}, price {}, qty {}, player {}, orderId {}",
                stock.code(), buy ? "buy" : "sell", String.format(Locale.ROOT, "%.2f", price), quantity,
                cn.oremarket.common.DebugLog.ascii(
                        player.getName().getString()), fresh.id);
        data.setDirty();
        // No more splicing "placed buy/sell order" fragments - one whole-sentence key + arguments.
        // The anti-self-trade rule remains server-side; its internal state is not player-facing toast content.
        LogService.pushKey(player, data, LogType.SYSTEM,
                buy ? "oremarket.log.order_placed_buy" : "oremarket.log.order_placed_sell",
                stock.displayName(), quantity, String.format(Locale.ROOT, "%.2f", price));
        TradeService.feedbackKey(player,
                buy ? "oremarket.order.placed_buy" : "oremarket.order.placed_sell", true);
        broadcastAll(server, data);
    }

    /**
     * Active order taking: fill somebody else's posted Limit Order directly (select a
     * foreign order in the UI and press "trade this order").
     *
     *  - trade price = the maker's posted price (a Limit Order is a limit instruction; whoever takes it settles at the price it was quoted at);
     *  - fee basis identical to the scheduled Order Matching: destruction tax + half the order fee (the maker already reserved this share when submitting);
     *  - quantity {@code <= 0} means take the **entire remaining size** of this order; a positive number takes exactly that much (never more than the remainder);
     *  - when balance / sellable holdings only cover part of it, fill the largest affordable amount as a **partial fill** and the rest of the order stays on the book;
     *  - never matches against your own orders; locked, halted or delisted stocks are rejected outright (server-side second check, anti packet-tampering).
     */
    public static void take(ServerPlayer player, long orderId, int quantity) {
        if (player == null) {
            return;
        }
        MinecraftServer server = player.server;
        MarketSavedData data = MarketSavedData.get(server);
        UUID taker = player.getUUID();
        PendingOrder maker = findOrder(data, orderId);
        if (maker == null) {
            TradeService.feedbackKey(player, "oremarket.text.0599", false);
            if (!cn.oremarket.net.OreMarketNetwork.requestTooFast(taker, "book_stale")) {
                requestFull(player);
            }
            return;
        }
        TakePlan plan = prepareTake(player, data, taker, maker, quantity);
        if (plan == null) {
            return;
        }
        settleTake(player, server, data, taker, maker, plan);
    }

    /** Validates a take request and computes its safe fill quantity before any balance changes. */
    private static TakePlan prepareTake(ServerPlayer player, MarketSavedData data, UUID taker,
                                        PendingOrder maker, int requestedQuantity) {
        if (maker.owner.equals(taker)) {
            TradeService.feedbackKey(player, "oremarket.text.0600", false);
            return null;
        }
        Stock stock = StockRegistry.server().getByCode(maker.code);
        if (stock == null || stock.retired() || data.isCodeRetiredFlagged(maker.code)) {
            TradeService.feedbackKey(player, "oremarket.text.0601", false);
            return null;
        }
        StockState quote = data.state(stock);
        if (quote == null || !(quote.price > 0) || !Double.isFinite(quote.price)
                || !(maker.price > 0) || !Double.isFinite(maker.price)) {
            TradeService.feedbackKey(player, "oremarket.trade.err.no_market", false);
            return null;
        }
        if (!data.unlocked(taker, stock)) {
            TradeService.feedbackKey(player, "oremarket.text.0302", false);
            return null;
        }
        if (cn.oremarket.market.event.EventEngine.suspendTicksLeft(data, maker.code) > 0) {
            TradeService.feedbackKey(player, "oremarket.text.0602", false);
            return null;
        }
        boolean takerBuys = !maker.buy;
        int want = requestedQuantity <= 0 ? maker.quantity : Math.min(requestedQuantity, maker.quantity);
        if (want <= 0) {
            TradeService.feedbackKey(player, "oremarket.text.0618", false);
            return null;
        }
        MarketSettings settings = MarketSettings.current();
        double rate = Fees.additive(settings.taxRate(), settings.orderFeeRate / 2.0);
        int fill = takerBuys
                ? affordableBuyFill(player, data, stock, maker, taker, want, rate)
                : affordableSellFill(player, data, stock, maker, taker, want, rate);
        return fill <= 0 ? null : new TakePlan(stock, settings, takerBuys, want, fill, rate);
    }

    private static int affordableBuyFill(ServerPlayer player, MarketSavedData data, Stock stock,
                                         PendingOrder maker, UUID taker, int want, double rate) {
        int afford = maxAffordableBuy(data, taker, maker.price, rate, want);
        int makerHolds = Math.max(0,
                data.availableUnlockedExceptOrder(maker.owner, stock, maker.id));
        int fill = Math.min(afford, makerHolds);
        if (fill <= 0) {
            TradeService.feedbackKey(player, afford <= 0
                    ? "oremarket.order.err.take_need_coins" : "oremarket.order.err.counterparty_short",
                    false, afford <= 0 ? String.valueOf(Fees.buyCost(maker.price, want, rate))
                            : stock.displayName());
        }
        return fill;
    }

    private static int affordableSellFill(ServerPlayer player, MarketSavedData data, Stock stock,
                                          PendingOrder maker, UUID taker, int want, double rate) {
        int fill = Math.min(want, data.availableUnlocked(taker, stock));
        if (fill <= 0) {
            TradeService.feedbackKey(player, "oremarket.order.err.take_need_shares", false,
                    stock.displayName(), String.valueOf(want));
            return 0;
        }
        int makerAfford = affordableFrom(
                data.availableCoinsExceptOrder(maker.owner, maker.id), maker.price, rate, fill);
        if (makerAfford <= 0) {
            TradeService.feedbackKey(player, "oremarket.order.log.buyer_broke", false, stock.displayName());
            return 0;
        }
        return Math.min(fill, makerAfford);
    }

    /** Applies a validated fill and sends the same logs, toasts, snapshots and book update as matching. */
    private static void settleTake(ServerPlayer player, MinecraftServer server, MarketSavedData data,
                                   UUID taker, PendingOrder maker, TakePlan plan) {
        UUID makerId = maker.owner;
        UUID buyerId = plan.takerBuys ? taker : makerId;
        UUID sellerId = plan.takerBuys ? makerId : taker;
        settle(data, plan.stock, buyerId, sellerId, plan.fill, maker.price, plan.rate);
        StockState state = data.state(plan.stock);
        if (state != null && !plan.settings.orderAffectPrice) {
            state.addPlayerOnlyVolume(plan.fill);
        }
        cn.oremarket.net.ParticleService.playTradeByUuid(server, buyerId, true);
        cn.oremarket.net.ParticleService.playTradeByUuid(server, sellerId, false);
        if (plan.settings.orderAffectPrice) {
            applyFillQuote(data, plan.stock, maker.price, plan.fill);
        }
        String priceText = String.format(Locale.ROOT, "%.2f", maker.price);
        String verbForTaker = cn.oremarket.common.RawText.t(
                plan.takerBuys ? "oremarket.text.0090" : "oremarket.text.0098");
        String verbForMaker = cn.oremarket.common.RawText.t(
                plan.takerBuys ? "oremarket.text.0098" : "oremarket.text.0090");
        LogService.pushKey(server, data, taker, plan.takerBuys ? LogType.BUY : LogType.SELL,
                "oremarket.log.order_filled_taker", data.nameOf(makerId), verbForTaker,
                plan.stock.displayName(), plan.fill, priceText);
        LogService.pushKey(server, data, makerId, plan.takerBuys ? LogType.SELL : LogType.BUY,
                "oremarket.log.order_filled_maker", player.getName().getString(), verbForMaker,
                plan.stock.displayName(), plan.fill, priceText);
        toastFill(server, data, taker, plan.stock, plan.fill, priceText);
        toastFill(server, data, makerId, plan.stock, plan.fill, priceText);
        maker.quantity -= plan.fill;
        if (maker.quantity <= 0) {
            data.pendingOrders.remove(maker);
            LOG.info("Order removed: ID {} (filled by player {})", maker.id,
                    cn.oremarket.common.DebugLog.ascii(player.getName().getString()));
        } else {
            LOG.info("Order partially filled: ID {} remaining {}, player {} took {}",
                    maker.id, maker.quantity,
                    cn.oremarket.common.DebugLog.ascii(player.getName().getString()), plan.fill);
        }
        data.setDirty();
        LevelService.raise(data, taker);
        LevelService.raise(data, makerId);
        TradeService.feedbackKey(player, plan.fill < plan.want
                ? (plan.takerBuys ? "oremarket.order.take_buy_partial" : "oremarket.order.take_sell_partial")
                : (plan.takerBuys ? "oremarket.order.take_buy" : "oremarket.order.take_sell"),
                true, plan.stock.displayName(), String.valueOf(plan.fill), priceText,
                String.valueOf(plan.want), String.valueOf(plan.fill));
        ServerPlayer makerPlayer = server.getPlayerList().getPlayer(makerId);
        if (makerPlayer != null) {
            MarketEngine.pushAfterAction(server, makerPlayer);
        }
        broadcastAll(server, data);
    }

    private static final class TakePlan {
        private final Stock stock;
        private final MarketSettings settings;
        private final boolean takerBuys;
        private final int want;
        private final int fill;
        private final double rate;

        private TakePlan(Stock stock, MarketSettings settings, boolean takerBuys,
                         int want, int fill, double rate) {
            this.stock = stock;
            this.settings = settings;
            this.takerBuys = takerBuys;
            this.want = want;
            this.fill = fill;
            this.rate = rate;
        }
    }

    /** Finds an order in the Order Book by ID, requiring remaining quantity; returns null when absent. */
    private static PendingOrder findOrder(MarketSavedData data, long orderId) {
        for (PendingOrder o : data.pendingOrders) {
            if (o.id == orderId && o.quantity > 0) {
                return o;
            }
        }
        return null;
    }

    /** Largest share count the taker can afford (a per-share step-back absorbs float error; the balance is never allowed to go negative). */
    private static int maxAffordableBuy(MarketSavedData data, UUID id, double price, double rate, int want) {
        return affordableFrom(data.availableCoins(id), price, rate, want);
    }

    /** Most shares affordable under a given Ore Coin ceiling (taker checks and scheduled Order Matching reuse this one criterion). */
    private static int affordableFrom(long coins, double price, double rate, int want) {
        if (price <= 0 || !Double.isFinite(price) || want <= 0) {
            return 0;
        }
        if (coins <= 0) {
            return 0;
        }
        double unit = price * (1.0 + rate);
        if (unit <= 0 || !Double.isFinite(unit)) {
            return 0;
        }
        int n = (int) Math.min((double) want, Math.floor((double) coins / unit));
        while (n > 0 && Fees.buyCost(price, n, rate) > coins) {
            n--;
        }
        return Math.max(0, n);
    }

    /**
     * Settlement of a trade between two players (shared by scheduled Order Matching and
     * active order taking, so both paths use one identical basis): the buyer books the
     * tax-and-fee-inclusive amount actually paid into the weighted-average cost, the
     * seller carries out realized P&L against the average cost and the cost record is
     * cleared once the position is sold out; bought shares always go into the frozen
     * bucket (T+1) and Ore Coins earned by selling count toward the peak balance.
     */
    /**
     * The order-book quote-update gate (anti quote-teleport).
     *
     * <p>[WHY] A book fill used to rewrite the shared quote directly to its fill price (no size or distance
     * check). One planted outlier order - e.g. 1 share at 0.26 x anchor, taken by an alt - dragged the whole
     * market quote there INSTANTLY, inside any single-tick limit. With mean reversion that created a
     * risk-free ladder (teleport the quote down, buy heavily at the floor, wait for the mechanical climb
     * back), plus free short-liquidation triggers. The settlement between the two players stays untouched;
     * only the quote rewrite is gated.</p>
     *
     * <p>[RULE] Every book fill records share x distance-to-quote pressure into this tick's ledger, and the
     * quote follows only when that same-direction mass reaches QUOTE_PRESSURE_LIMIT of the stock's depth
     * (short-pool cap as the size proxy). A single small order contributes ~0 weight - it settles, the quote
     * stands still. A genuinely large trade (>= a quarter of the pool cap at any distance, or sustained mass
     * at smaller distances) still moves the quote, and even then never more than the engine's own
     * single-tick price limit in one go. Traded volume always counts (the fill really happened).</p>
     */
    private static void applyFillQuote(MarketSavedData data, Stock stock, double fillPrice, int qty) {
        StockState bookState = data.state(stock);
        if (bookState == null) {
            return;
        }
        double quote = bookState.price;
        if (!(quote > 0.0) || !Double.isFinite(quote) || !(fillPrice > 0.0) || !Double.isFinite(fillPrice)) {
            return;
        }
        double deviation = Math.abs(fillPrice - quote) / quote;
        boolean buySide = fillPrice > quote;
        MarketSettings settings = MarketSettings.current();
        MarketEngine.recordQuotePressure(stock.code(), buySide, qty, fillPrice, quote);
        bookState.addPlayerTradedVolume(qty);
        if (deviation <= 1.0e-12) {
            data.setDirty();   // fill == quote: nothing to move, volume only
            return;
        }
        if (!MarketEngine.quotePressureCleared(data, stock.code(), buySide, deviation)) {
            data.setDirty();   // not enough same-direction mass this tick: the quote stands still
            return;
        }
        double target = MarketSavedData.applyPriceLimit(fillPrice, quote, settings.priceLimitFraction());
        double previousPrice = bookState.price;
        bookState.price = data.clampPrice(stock, target);
        bookState.recordPriceChange(previousPrice, bookState.price);
        bookState.appendHistory(bookState.price);
        data.setDirty();
    }

    private static void settle(MarketSavedData data, Stock stock, UUID buyerId, UUID sellerId,
                               int quantity, double price, double rate) {
        long buyerPayment = Fees.buyCost(price, quantity, rate);
        long sellerReceipt = Fees.sellGain(price, quantity, rate);
        int buyerOldShares = data.totalShares(buyerId, stock);
        double buyerOldAvg = data.costOr(buyerId, stock, price);
        double sellerAvg = data.costOr(sellerId, stock, price);
        // buy spending leaves the peak alone (same basis as system buys), sell proceeds move the peak
        data.addBalanceNoPeak(buyerId, -buyerPayment);
        data.creditSaleProceeds(sellerId, sellerReceipt);
        data.addShares(sellerId, stock, -quantity);
        data.addFrozen(buyerId, stock, quantity);
        // player-behaviour trigger stats (a wash trade whose buyer and seller share one UUID is never counted)
        boolean selfTrade = buyerId != null && buyerId.equals(sellerId);
        cn.oremarket.market.event.PlayerVolumeTracker
                .recordBuy(stock.code(), buyerId, quantity, selfTrade);
        cn.oremarket.market.event.PlayerVolumeTracker
                .recordSell(stock.code(), sellerId, quantity, selfTrade);
        double buyerNewAvg = buyerOldShares <= 0 ? (double) buyerPayment / quantity
                : (buyerOldShares * buyerOldAvg + buyerPayment) / (buyerOldShares + quantity);
        data.setCost(buyerId, stock, buyerNewAvg, "buy");
        data.addRealized(sellerId, stock, sellerReceipt - quantity * sellerAvg);
        if (data.totalShares(sellerId, stock) <= 0) {
            data.clearPositionCost(sellerId, stock, "clear");
        }
    }

    public static void cancel(ServerPlayer player, long orderId) {
        if (player == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(player.server);
        for (int i = 0; i < data.pendingOrders.size(); i++) {
            PendingOrder order = data.pendingOrders.get(i);
            if (order.id == orderId && order.owner.equals(player.getUUID())) {
                data.pendingOrders.remove(i);
                LOG.info("Order removed: ID {}", orderId);
                data.setDirty();
                TradeService.feedbackKey(player, "oremarket.text.0249", true);
                broadcastAll(player.server, data);
                return;
            }
        }
        TradeService.feedbackKey(player, "oremarket.text.0392", false);
    }

    /** Called every matching cycle: price priority, then time priority; partial fills are supported. */
    public static void match(MinecraftServer server, MarketSavedData data, PushFrame frame) {
        // With 30 stocks x thousands of orders the old style burned hundreds of thousands of pointless comparisons per matching cycle.
        // Grouping walks the same list and appends in the same order, so the relative order within one code is byte-for-byte as before;
        // the "price priority -> sequence number (time) priority" sort result and the fill pairings are unchanged.
        Map<String, List<PendingOrder>> byCode = groupByCode(data.pendingOrders);
        for (Stock stock : StockRegistry.server().getActiveStocks()) {
            if (frame != null ? frame.suspended(stock)
                    : EventEngineSuspend.suspended(data, stock.code())) {
                continue;
            }
            // Liquidity-drought Event: this stock's Limit Orders are paused from matching
            if (cn.oremarket.market.event.EventEffectsService
                    .liquidityDryTicksLeft(data, stock.code()) > 0) {
                continue;
            }
            List<PendingOrder> group = byCode.get(stock.code());
            if (group == null || group.isEmpty()) {
                continue;   // no live orders for this stock, skip it at once (the old style scanned the whole table to learn the same)
            }
            matchStock(server, data, stock, group);
        }
        // Matching only touches orders that already exist: filled ones are removed from the list (logged), unfilled ones stay as they are and are never re-added.
        for (int i = data.pendingOrders.size() - 1; i >= 0; i--) {
            PendingOrder o = data.pendingOrders.get(i);
            if (o.quantity <= 0) {
                data.pendingOrders.remove(i);
                LOG.info("Order removed: ID {}", o.id);
            }
        }
        data.setDirty();
        broadcastAll(server, data);
    }

    /** Trading Day close: Limit Orders kept longer than their retention window are cancelled automatically; locked resources release naturally with the order removal. */
    public static void expire(MinecraftServer server, MarketSavedData data) {
        MarketSettings settings = MarketSettings.current();
        long cutoff = data.tradeDay - settings.orderMaxKeepDays;
        boolean changed = false;
        Map<UUID, Integer> online = new HashMap<>();
        for (int i = data.pendingOrders.size() - 1; i >= 0; i--) {
            PendingOrder o = data.pendingOrders.get(i);
            if (o.createdDay <= cutoff && o.quantity > 0) {
                data.pendingOrders.remove(i);
                LOG.info("Order removed: ID {} (expired)", o.id);
                changed = true;
                if (notice(data, server, o, "oremarket.log.order_expired")) {
                    online.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        if (changed) {
            data.setDirty();
            broadcastAll(server, data);
            // Auto-cancelled while the owner was online: pop a toast summary (parked and replayed
            // when the market screen opens next); offline owners got the persisted login catch-up.
            online.forEach((owner, n) -> OrderNoticeService.summary(server, owner, false, n));
        }
    }

    public static void cancelStock(MinecraftServer server, MarketSavedData data, String code) {
        boolean changed = false;
        Map<UUID, Integer> online = new HashMap<>();
        for (int i = data.pendingOrders.size() - 1; i >= 0; i--) {
            PendingOrder o = data.pendingOrders.get(i);
            if (o.code.equals(code)) {
                data.pendingOrders.remove(i);
                LOG.info("Order removed: ID {} (stock delisted)", o.id);
                changed = true;
                // zero-quantity leftovers are housekeeping removals, not player-facing cancellations
                if (o.quantity > 0 && notice(data, server, o, "oremarket.log.order_delisted")) {
                    online.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        if (changed) {
            data.setDirty();
            broadcastAll(server, data);
            online.forEach((owner, n) -> OrderNoticeService.summary(server, owner, false, n));
        }
    }

    /**
     * One system-cancelled pending order: online owner gets a detail log line (and the caller counts
     * it into the summary toast); offline owner's cancellation enters the persisted login catch-up.
     * The locked coins / shares need NO explicit refund - locks derive live from the order list,
     * so removing the order already released them.
     */
    private static boolean notice(MarketSavedData data, MinecraftServer server, PendingOrder o, String logKey) {
        Stock stock = StockRegistry.server().getByCode(o.code);
        String name = stock != null ? stock.displayName() : o.code;
        return OrderNoticeService.record(server, data, o.owner, false, logKey,
                name, o.buy ? "oremarket.text.0019" : "oremarket.text.0032",
                o.quantity, String.format(Locale.ROOT, "%.2f", o.price));
    }

    /**
     * Matching for one stock. {@code group} holds every live Limit Order of that code
     * (zero-quantity orders already filtered out, original order preserved by
     * {@link #groupByCode}); here we only bucket buys / sells and sort.
     */
    private static void matchStock(MinecraftServer server, MarketSavedData data, Stock stock,
                                   List<PendingOrder> group) {
        List<PendingOrder> buys = new ArrayList<>();
        List<PendingOrder> sells = new ArrayList<>();
        for (PendingOrder order : group) {
            (order.buy ? buys : sells).add(order);
        }
        buys.sort(Comparator.<PendingOrder>comparingDouble(o -> -o.price)
                .thenComparingLong(o -> o.createdSequence));
        sells.sort(Comparator.<PendingOrder>comparingDouble(o -> o.price)
                .thenComparingLong(o -> o.createdSequence));
        int bi = 0;
        int si = 0;
        MarketSettings settings = MarketSettings.current();
        while (bi < buys.size() && si < sells.size()) {
            PendingOrder buy = buys.get(bi);
            PendingOrder sell = sells.get(si);
            if (buy.price + 1.0e-9 < sell.price) {
                break;
            }
            // Anti self-trade (wash trading): a buy and a sell under the same player name never fill each other, even at crossed or identical prices.
            // Simply "skipping one order" is not enough - the pair would then jam at the head of the queue for each other and
            // block both from matching **other** players. So look whether either side still has another counterparty:
            //  - the next buy crosses the current sell -> advance the buy pointer (let this sell match other players first);
            //  - otherwise the next sell crosses the current buy -> advance the sell pointer (same reasoning, let this buy go find others);
            //  - no crossing counterparty on either side -> this group is done: both orders stay in the book untouched - they cannot fill
            //    each other, and nothing stops either of them from filling against a newly arriving counterparty (retried next matching cycle).
            if (buy.owner.equals(sell.owner)) {
                PendingOrder nextBuy = bi + 1 < buys.size() ? buys.get(bi + 1) : null;
                PendingOrder nextSell = si + 1 < sells.size() ? sells.get(si + 1) : null;
                boolean buyHasOtherCounter = nextBuy != null && nextBuy.price + 1.0e-9 >= sell.price;
                boolean sellHasOtherCounter = nextSell != null && buy.price + 1.0e-9 >= nextSell.price;
                if (buyHasOtherCounter) {
                    bi++;
                } else if (sellHasOtherCounter) {
                    si++;
                } else {
                    break;
                }
                continue;
            }
            int quantity = Math.min(buy.quantity, sell.quantity);
            if (quantity <= 0) {
                if (buy.quantity <= 0) {
                    bi++;
                }
                if (sell.quantity <= 0) {
                    si++;
                }
                continue;
            }
            double midpoint = (buy.price + sell.price) / 2.0;
            double rate = Fees.additive(settings.taxRate(), settings.orderFeeRate / 2.0);
            Stock sellerStock = StockRegistry.server().getByCode(sell.code);
            if (sellerStock == null) {
                break;
            }
            // Anti "ghost sell order": after a sell was submitted, outside changes may have shrunk the seller's holdings (admin wipe / freeze, bankruptcy clear)
            // while the remaining quantity still shows the old value. Matching caps the fill by the seller's true sellable holdings right now, and voids the order if not even one share survives.
            int rawSellable = Math.max(0, data.available(sell.owner, sellerStock));
            if (rawSellable <= 0) {
                // The sell order can never fill: removing it silently would be a disappearing order.
                // Cancel it through the same no-silent-vanishing notice channel as expiry / delisting.
                LOG.info("Order removed: ID {} (seller holdings insufficient)", sell.id);
                voidOrder(server, data, sell, stock);
                sell.quantity = 0;
                si++;
                continue;
            }
            // Keep every other sell order's reservation intact; this order's own reservation is excluded.
            int otherLockedSellable = Math.max(0,
                    data.availableUnlockedExceptOrder(sell.owner, sellerStock, sell.id));
            if (quantity > otherLockedSellable) {
                quantity = otherLockedSellable;
            }
            if (quantity <= 0) {
                // Shares still exist, but another order owns the reservation for them; defer this order.
                LOG.info("Order deferred: ID {} (seller shares reserved by another order)", sell.id);
                si++;
                continue;
            }
            // Keep every other buy order's reservation intact; this order's own reservation is excluded.
            long buyerCoins = data.availableCoinsExceptOrder(buy.owner, buy.id);
            if (Fees.buyCost(midpoint, quantity, rate) > buyerCoins) {
                quantity = affordableFrom(buyerCoins, midpoint, rate, quantity);
                if (quantity <= 0) {
                    // this buy order has no money right now: skip it (the order stays; it can still fill once the balance recovers) and move to the next buy
                    LOG.info("Order deferred: ID {} (buyer cannot afford midpoint fill)", buy.id);
                    bi++;
                    continue;
                }
            }
            long buyerPayment = Fees.buyCost(midpoint, quantity, rate);
            long sellerReceipt = Fees.sellGain(midpoint, quantity, rate);
            // Balances, holdings, cost basis and realized P&L on both sides all go through settle (same basis as active order taking):
            // the buy order's lock was reserved at the submitted price (availableCoins was validated at submission) and the trade happens at the midpoint,
            // so the difference (buy price - midpoint) x filled quantity comes back to the buyer on the spot as a "smaller charge"; buy spending never raises the peak balance,
            // sell proceeds do; the buyer's newly bought shares go into the frozen bucket (T+1).
            settle(data, sellerStock, buy.owner, sell.owner, quantity, midpoint, rate);
            if (!settings.orderAffectPrice) {
                StockState matchedState = data.state(stock);
                if (matchedState != null) {
                    matchedState.addPlayerOnlyVolume(quantity);
                }
            }
            cn.oremarket.net.ParticleService
                    .playTradeByUuid(server, buy.owner, true);
            cn.oremarket.net.ParticleService
                    .playTradeByUuid(server, sell.owner, false);
            // Same quote-update gate as active order taking: scheduled matching records its pressure first;
            // the quote only follows when this tick's same-direction book mass justifies the move.
            if (settings.orderAffectPrice) {
                applyFillQuote(data, stock, midpoint, quantity);
            }
            buy.quantity -= quantity;
            sell.quantity -= quantity;
            ServerPlayer buyer = server.getPlayerList().getPlayer(buy.owner);
            ServerPlayer seller = server.getPlayerList().getPlayer(sell.owner);
            if (buyer != null) {
                LogService.pushKey(buyer, data, LogType.BUY, "oremarket.text.0527",
                        stock.displayName(), quantity,
                        String.format(Locale.ROOT, "%.2f", midpoint),
                        String.format(Locale.ROOT, "%.1f", (double) buyerPayment));
                TradeService.feedbackKey(buyer, "oremarket.order.toast.fill", true,
                        stock.displayName(), String.valueOf(quantity),
                        String.format(Locale.ROOT, "%.2f", midpoint));
            } else {
                // Offline buyer: coins / shares still moved at matching; queue a login notice.
                OrderNoticeService.notice(server, data, buy.owner,
                        "oremarket.toast.offline.order_fill", true,
                        stock.displayName(), quantity, String.format(Locale.ROOT, "%.2f", midpoint));
            }
            if (seller != null) {
                LogService.pushKey(seller, data, LogType.SELL, "oremarket.text.0528",
                        stock.displayName(), quantity,
                        String.format(Locale.ROOT, "%.2f", midpoint),
                        String.format(Locale.ROOT, "%.1f", (double) sellerReceipt));
                TradeService.feedbackKey(seller, "oremarket.order.toast.fill", true,
                        stock.displayName(), String.valueOf(quantity),
                        String.format(Locale.ROOT, "%.2f", midpoint));
            } else {
                // Offline seller: same rule - a fill that touches the wallet is never silent.
                OrderNoticeService.notice(server, data, sell.owner,
                        "oremarket.toast.offline.order_fill", true,
                        stock.displayName(), quantity, String.format(Locale.ROOT, "%.2f", midpoint));
            }
            LevelService.raise(data, buy.owner);
            LevelService.raise(data, sell.owner);
            if (buy.quantity <= 0) {
                bi++;
            }
            if (sell.quantity <= 0) {
                si++;
            }
        }
    }

    /** Group by stock code (original list order preserved; only orders with quantity > 0). */
    private static Map<String, List<PendingOrder>> groupByCode(List<PendingOrder> orders) {
        Map<String, List<PendingOrder>> byCode = new HashMap<>();
        for (PendingOrder order : orders) {
            if (order.quantity > 0) {
                byCode.computeIfAbsent(order.code, k -> new ArrayList<>()).add(order);
            }
        }
        return byCode;
    }

    public static List<PendingOrder> ordersFor(MarketSavedData data, UUID id) {
        List<PendingOrder> out = new ArrayList<>();
        for (PendingOrder order : data.pendingOrders) {
            if (order.owner.equals(id)) {
                out.add(order);
            }
        }
        return out;
    }

    /** After a hot reload, refresh the Order Book for all watchers. */
    public static void pushAll(MinecraftServer server, MarketSavedData data) {
        if (server != null && data != null) {
            broadcastAll(server, data);
        }
    }

    /** Requests the server-wide order list separately when the orders sub-tab is opened. */
    public static void requestFull(ServerPlayer player) {
        if (player == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(player.server);
        cn.oremarket.net.OreMarketNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                bookPacket(data));
    }

    private static void toastFill(MinecraftServer server, MarketSavedData data, UUID id,
                                  Stock stock, int fill, String priceText) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p == null) {
            // The maker may be offline while their order is taken: their coins / shares still moved,
            // so queue a login notice instead of letting the fill pass in silence.
            OrderNoticeService.notice(server, data, id,
                    "oremarket.toast.offline.order_fill", true,
                    stock.displayName(), fill, priceText);
            return;
        }
        TradeService.feedbackKey(p, "oremarket.order.toast.fill", true,
                stock.displayName(), String.valueOf(fill), priceText);
    }

    /**
     * A resting sell order whose owner no longer holds any sellable share (admin wipe / freeze /
     * bankruptcy clear): removing it must follow the no-silent-vanishing channel like expiry and
     * delisting - detail log line for the owner, summary toast online, login catch-up offline.
     */
    private static void voidOrder(MinecraftServer server, MarketSavedData data,
                                  PendingOrder order, Stock stock) {
        if (order == null || order.quantity <= 0) {
            return;     // zero-quantity leftovers are housekeeping, not player-facing cancellations
        }
        String name = stock != null ? stock.displayName() : order.code;
        int remaining = order.quantity;
        boolean online = OrderNoticeService.record(server, data, order.owner, false,
                "oremarket.log.order_voided", name, "oremarket.text.0032",
                remaining, String.format(Locale.ROOT, "%.2f", order.price));
        data.pendingOrders.remove(order);
        order.quantity = 0;
        data.setDirty();
        if (online) {
            OrderNoticeService.summary(server, order.owner, false, 1);
        }
    }

    private static void broadcastAll(MinecraftServer server, MarketSavedData data) {
        if (MarketEngine.WATCHERS.isEmpty()) {
            return;
        }
        cn.oremarket.net.packet.S2COrderBookPacket packet = bookPacket(data);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (MarketEngine.WATCHERS.contains(player.getUUID())) {
                cn.oremarket.net.OreMarketNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), packet);
            }
        }
    }

    private static cn.oremarket.net.packet.S2COrderBookPacket bookPacket(
            MarketSavedData data) {
        net.minecraft.nbt.ListTag all = new net.minecraft.nbt.ListTag();
        for (PendingOrder order : data.pendingOrders) {
            if (order.quantity > 0) {
                all.add(order.toTag());
            }
        }
        net.minecraft.nbt.CompoundTag payload = new net.minecraft.nbt.CompoundTag();
        payload.put(cn.oremarket.net.packet.S2COrderBookPacket.KEY_ORDERS, all);
        return new cn.oremarket.net.packet.S2COrderBookPacket(payload);
    }

    /** Reads only the Event trading-halt status, keeping OrderBookService free of an extra package dependency on EventEngine. */
    private static final class EventEngineSuspend {
        static boolean suspended(MarketSavedData data, String code) {
            return cn.oremarket.market.event.EventEngine
                    .suspendTicksLeft(data, code) > 0;
        }
    }
}
