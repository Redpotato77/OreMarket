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

import cn.oremarket.config.OreMarketConfig;
import cn.oremarket.market.event.EventEngine;
import cn.oremarket.market.event.MarketModifiers;
import cn.oremarket.market.log.LogService;
import cn.oremarket.net.OreMarketNetwork;
import cn.oremarket.net.packet.MarketSnapshotPacket;
import cn.oremarket.net.packet.MarketTickPacket;
import cn.oremarket.net.packet.S2CSnapshotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import cn.oremarket.common.StockState;
import java.util.Locale;

/**
 * Server market engine: advances one marketTick every marketTickInterval game ticks; all computation stays server-side.
 * The interval, trading-day length, the six price-engine parameters, fees and price limits all come from {@link MarketSettings}
 * (the live config snapshot) - no 60 / 400 / 0.02 / 0.03 / 2% / 10% is hard-coded in this class.
 *
 * - the quote loop walks {@link StockRegistry} (dynamic length, built-in + custom; DELISTED STOCKS GET NO PRICE UPDATE);
 * - event parameters are looked up by code (MarketModifiers.get(code)); the ordinal arrays are gone;
 * - each marketTick pushes the new {@link MarketTickPacket} (quotes: price / last history point / current candle), while
 * personal data (balance, holdings, loans, events) rides the dynamic-length {@link S2CSnapshotPacket};
 * - the ON_DEMAND change-detection table is keyed by code and adapts automatically after registry rebuilds.
 *
 * Execution phase: this class is driven by the START phase of ServerTickEvent;
 * config hot reload and post-login recalculation run in the END phase (see MarketReloadService), so they never overlap a marketTick.
 *
 * Order within each marketTick:
 * 0) trading-day timing; 1) event timing; 2) price evolution; 2c) candle aggregation; 3) chains and event-pool draw;
 * 3b) level-unlock detection; 4) network push.
 *
 * A trading halt only forbids buying and selling; prices keep evolving and the exchange rate is unaffected.
 * All randomness comes from the Overworld's unified random source, so every player sees the same events.
 */
public final class MarketEngine {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("OreMarket/Engine");

    /**
     * Same-direction ORDER-BOOK pressure this marketTick: share x distance-to-quote weights summed by
     * direction, reset at the TOP of every marketTick. Order-book fills record into it BEFORE the quote
     * gate is evaluated (OrderBookService.applyFillQuote), so the gate always sees the fill being judged.
     * Nothing else may read it: it must never touch settlement, matching or P&L math.
     */
    private static final Map<String, double[]> QUOTE_PRESSURE = new HashMap<>();

    /**
     * Book fills may only move the shared quote while this tick's same-direction pressure reaches this
     * weight (share x distance-to-quote summed per fill). 0.25 of one pool cap = a sustained push, not a
     * planted outlier; below it the fill settles between the players but the quote stands still.
     */
    static final double QUOTE_PRESSURE_LIMIT = 0.25;

    /** Floor of the depth used to normalize order-book pressure, in shares. */
    static final int QUOTE_GATE_MIN_LIQUIDITY = 50;

    /** Clears one tick's pressure ledger; call at the TOP of every marketTick (before matching). */
    static void beginQuotePressureWindow() {
        QUOTE_PRESSURE.clear();
    }

    /** Current tick's same-direction pressure weight of one stock (0 = untouched). */
    static double quotePressure(String code, boolean buySide) {
        double[] v = code == null ? null : QUOTE_PRESSURE.get(code);
        if (v == null) {
            return 0.0;
        }
        double w = buySide ? v[0] : v[1];
        return Double.isFinite(w) && w > 0.0 ? w : 0.0;
    }

    /**
     * Records one settled order-book fill into this tick's pressure ledger. Every component is finite and
     * non-negative (a dirty component contributes nothing instead of poisoning the whole stock window).
     * The weight can only GROW within a tick (monotone), so a later fill can legitimately unlock the gate.
     */
    static void recordQuotePressure(String code, boolean buySide, int qty, double price, double quote) {
        if (code == null || code.isEmpty() || qty <= 0
                || !(price > 0.0) || !Double.isFinite(price)
                || !(quote > 0.0) || !Double.isFinite(quote)) {
            return;
        }
        double dist = Math.abs(price - quote) / quote;
        if (!(dist > 0.0) || !Double.isFinite(dist)) {
            dist = 0.0;
        }
        double[] v = QUOTE_PRESSURE.computeIfAbsent(code, k -> new double[2]);
        double add = (double) qty * dist;
        if (buySide) {
            v[0] = Double.isFinite(v[0] + add) ? v[0] + add : v[0];
        } else {
            v[1] = Double.isFinite(v[1] + add) ? v[1] + add : v[1];
        }
    }

    /** Whether this tick's pressure for one settled book fill clears the gate (depth-normalized). */
    static boolean quotePressureCleared(MarketSavedData data, String code, boolean buySide, double deviation) {
        int depth = depthForQuoteGate(data, code);
        if (depth <= 0 || !Double.isFinite(deviation) || deviation <= 0.0) {
            return false;
        }
        double normalized = quotePressure(code, buySide) / (double) depth;
        return normalized >= QUOTE_PRESSURE_LIMIT * deviation * (1.0 - 1.0e-9);
    }

    /** Book depth used to normalize pressure: the stock's own short-pool cap (a stable per-stock size proxy). */
    static int depthForQuoteGate(MarketSavedData data, String code) {
        int depth = 0;
        try {
            depth = data.shortPoolMax(code);
        } catch (RuntimeException ignored) {
            depth = 0;
        }
        if (depth < QUOTE_GATE_MIN_LIQUIDITY) {
            depth = QUOTE_GATE_MIN_LIQUIDITY;
        }
        return depth;
    }

    /** Price-update logging: only the first 20 marketTicks print. */
    private static final int PRICE_LOG_MAX_TICKS = 20;

    /** Game ticks per marketTick (config category 5, read live). */
    public static int ticksPerMarketTick() {
        return MarketSettings.current().marketTickInterval;
    }

    /** Default single-tick price-limit width (config category 7; events may rewrite it within category 9's range). */
    public static double defaultPriceLimit() {
        return MarketSettings.current().priceLimitFraction();
    }

    /** Players with the UI open (INCREMENTAL / ON_DEMAND modes push to them only). */
    public static final Set<UUID> WATCHERS = new HashSet<>();

    /** ON_DEMAND mode's last pushed price: keyed by stock code, as big as the registry itself. */
    private static final Map<String, Double> LAST_PUSHED = new HashMap<>();
    /**
     * ON_DEMAND mode's last pushed halt flag per stock: the client's halt badge (one localized HALT label in the stock
     * list) rides the personal snapshot ONLY, and a just-resumed stock often trades within the 1% price band, so
     * price movement alone would let the badge linger until the next big move. Any halt flag flip counts as a change.
     */
    private static final Map<String, Boolean> LAST_PUSHED_SUSPEND = new HashMap<>();
    private static boolean PUSH_SEEN = false;

    /** Call after a registry rebuild: drop push-memory of delisted stocks; new stocks' first comparison counts as a change. */
    public static synchronized void onRegistryRebuilt() {
        Set<String> live = new HashSet<>();
        for (Stock s : StockRegistry.server().getActiveStocks()) {
            live.add(s.code());
        }
        LAST_PUSHED.keySet().retainAll(live);
        LAST_PUSHED_SUSPEND.keySet().retainAll(live);
        PUSH_SEEN = false;
    }

    private MarketEngine() {
    }

    /** Driven by the START phase of ServerTickEvent, called every game tick. */
    public static void onServerTick(MinecraftServer server) {
        MarketSettings ms = MarketSettings.server();
        MarketSavedData data = MarketSavedData.get(server);
        data.tickCounter++;
        if (data.tickCounter % ms.marketTickInterval != 0) {
            return;
        }
        marketTick(server, data, ms);
    }

    private static void marketTick(MinecraftServer server, MarketSavedData data, MarketSettings ms) {
        ServerLevel overworld = server.overworld();
        RandomSource rand = overworld.getRandom();

        // 0) trading-day timing: once this day's accumulated marketTicks reach the configured length, the day closes;
        // close = day +1 -> T+1 frozen holdings unfreeze -> loan interest accrual / maturity forced liquidation.
        beginQuotePressureWindow();
        data.marketTicks++;
        data.dayProgress++;
        boolean closeTradingDay = data.dayProgress >= ms.marketTicksPerTradeDay();
        Map<String, double[]> closedDailyThisTick = Map.of();
        if (closeTradingDay) {
            closedDailyThisTick = closeDailyKlines(data);
            data.dayProgress = 0;
            LoanService.onTradeDayClose(server, data);
            // Close sequence: loan interest -> short daily interest / liquidation -> order expiry -> limit-order expiry -> listing/delisting
            // -> record the next day's total-asset baseline -> async leaderboard refresh.
            ShortService.settleDailyInterest(server, data);
            // The daily refill is written ONLY in the trading-day close settlement - each stock recovers
            // 10% of its own pool cap (clamped at the top); it must never enter render loops / query methods / other tick branches.
            ShortService.refillDailyPools(data);
            OrderBookService.expire(server, data);
            LimitOrderService.expire(server, data);
            ListingService.onTradeDayClose(server, data);
            // Trading-day switch: rewrite dailyBaseline for ALL ONLINE players (= calculateTotalAssets at this moment);
            // offline players backfill at login. Daily gain comes from the leaderboard's realtime refresh every 5 seconds.
            LeaderboardService.recordDailyBaselines(server, data);
            LeaderboardService.schedule(server, data);
            data.setDirty();
        } else {
            LoanService.tickLiquidation(server, data);
        }

        // 1) event timing: the overall duration plus each module's own duration; expired events deactivate (an auto 'event ended' log line on expiry)
        java.util.function.Consumer<ActiveEvent> announce = e -> EventEngine.announce(server, data, e);
        EventEngine.advanceTimers(server, data, rand, announce);

        // 2) compress active events into this tick's parameter table (by code) - capture the whole frame once and reuse the modifier table.
        // (performance fix: previously collect ran here and capture ran it again at 2d; the event table does not change
        // between advanceTimers and 2d (prices / first-day volatility / candles never touch events), so the frame can be reused)
        PushFrame checks = PushFrame.capture(data, false);
        MarketModifiers mm = checks.modifiers();

        // 2b) price evolution (active stocks only; delisted and halted stocks stay frozen)
        stepPrices(data, rand, ms, mm, checks);
        // newly listed stocks: run the price engine first, then apply the first-day volatility once (exempt from the single-tick price limit)
        ListingService.applyFirstDayVolatility(data, rand);
        // 2c) candle aggregation: one global period counter closes a candle every 10 marketTicks, shared by all active stocks
        Map<String, double[]> closedThisTick = stepKlines(data, checks);
        // 2d) short liquidation (price line) -> order matching -> limit-order checks (order: matching before limit orders)
        // this tick's halt table scans the event list ONCE and is shared by three checks (performance fix:
        // previously every empty position, every limit order and every active stock re-scanned 'all events x all effect modules')
        ShortService.checkForcedLiquidations(server, data, checks);
        if (data.marketTicks - data.lastOrderMatchMarketTick >= ms.orderMatchIntervalTicks()) {
            data.lastOrderMatchMarketTick = data.marketTicks;
            OrderBookService.match(server, data, checks);
        }
        LimitOrderService.check(server, data, checks);
        data.setDirty();

        // 3) scheduled events (deterministic chains / legendary preview fulfillment / player triggers / combo checks)
        // -> probabilistic chains (while active) -> event-pool draw (condition filter + rarity weights + dynamic frequency + preview interception)
        cn.oremarket.market.event.PlayerVolumeTracker
                .beginMarketTick(data.marketTicks);
        EventEngine.tickScheduled(server, data, rand, announce);
        EventEngine.fireRunningChains(server, data, rand, announce);
        EventEngine.tryTriggerRandom(server, data, rand, announce);

        // 3b) level-unlock detection: covers peak changes that bypass the trade entry (forced sells / maturity settlements etc.)
        LogService.syncTiers(server, data);

        // 4) network push (chains and new events may have just changed the event table, so capture a fresh frame here,
        // guaranteeing the halted/modifier figures pushed to players are exactly what on-demand computation would give)
        pushUpdate(server, data, closedThisTick, closedDailyThisTick);
    }

    /**
     * One stock's price evolution in a single marketTick (a pure function for easy numerical review):
     * volatility (x event volatility) -> trend term -> mean reversion (x event reversion) -> event price multiplier
     * -> clamped into the anchor floor%~ceiling% band -> price limits.
     * The eight engine parameters are global config shared by all stocks - NOT per-stock.
     *
     * <p>[REVERSION DEAD ZONE] mean reversion only pulls the quote inside +/- deadZone of the anchor.
     * Rationale: a constant pull toward a public, immutable base price is a risk-free ladder by itself
     * ('buy any dip, wait for the guaranteed climb back'), no events needed. Inside the dead zone the
     * quote still behaves mean-reverting (small noise, familiar shape); outside it the quote moves by
     * trend / noise / events only, so deep discounts and deep premiums are NOT guaranteed to heal -
     * holding them is a position with real risk (delisting at the low end keeps its teeth).</p>
     *
     * <p>[ANCHOR] reversion target and floor/ceiling use the SAVE anchor (basePrice x split/dividend
     * scale, see MarketSavedData.quoteAnchorScales), never the raw config base: an ex-rights quote must
     * revert to the ex-rights anchor, otherwise reversion mints the whole cut back.</p>
     */
    public static double evolvePrice(MarketSavedData data, Stock stock, double prevTickPrice,
                                      MarketSettings ms, MarketModifiers mm, RandomSource rand,
                                      double sharedNoise) {
        return PriceEvolutionStrategy.evolvePrice(data, stock, prevTickPrice, ms, mm, rand, sharedNoise);
    }

    /**
     * Maps one limit episode to one of four visual/market microstructure patterns.
     * Kept as a thin delegate so callers and numerical tests retain the original engine surface.
     */
    static int limitPattern(String code, int direction) {
        return PriceEvolutionStrategy.limitPattern(code, direction);
    }

    /** Keeps a sealed board at the legal edge without crossing either the normal band or the long-run base band. */
    static double sealedLimitQuote(double limited, double previous, double limit, int direction,
                                   double floor, double ceiling) {
        return PriceEvolutionStrategy.sealedLimitQuote(limited, previous, limit, direction, floor, ceiling);
    }

    /**
     * Replaces a hard long-run boundary quote with a small, randomized auction band.
     * The quote remains inside the configured floor/ceiling and the normal one-tick limit.
     */
    static double boundaryQuote(double desired, double raw, double previous, double floor, double ceiling,
                                double limit, double noise) {
        return PriceEvolutionStrategy.boundaryQuote(desired, raw, previous, floor, ceiling, limit, noise);
    }

    /**
     * Creates one small inward auction step only when the previous quote was already pinned to a long-run edge.
     * The following tick can return to the edge, producing a bounded wick without bypassing either price band.
     */
    static double boundaryAuctionRelief(double limited, double previous, double floor, double ceiling,
                                        double limit, int direction) {
        return PriceEvolutionStrategy.boundaryAuctionRelief(limited, previous, floor, ceiling, limit, direction);
    }

    /**
     * Pulls a capped quote a small distance back from the edge after a repeated same-direction clip.
     * The adjustment is deliberately bounded by both the quote's current legal band and a fixed microstructure step.
     */
    static double auctionRelief(double limited, double previous, double limit, int direction) {
        return PriceEvolutionStrategy.auctionRelief(limited, previous, limit, direction);
    }

    /**
     * Run one price-evolution pass over ALL ACTIVE stocks, advancing the stock's own trend independently of temporary event drift (delisted stocks skipped).
     * The price engine never touches averageCost: only StockState prices / history / trend are read and written here.
     */
    private static void stepPrices(MarketSavedData data, RandomSource rand,
                                   MarketSettings ms, MarketModifiers mm, PushFrame frame) {
        boolean logTicks = data.marketTicks <= PRICE_LOG_MAX_TICKS;
        double sharedNoise = 0.0;
        if (ms.indexVolatilityWeight > 0.0) {
            sharedNoise = cn.oremarket.common.MarketIndexNoise.noise(
                    rand.nextDouble(), ms.randomFluctuation, ms.indexVolatilityWeight);
        }
        for (Stock s : StockRegistry.getActiveStocks()) {
            StockState st = data.state(s);
            if (st == null) {
                continue;                       // should be unreachable: after rebuild every active stock has state
            }
            if (frame != null && frame.suspended(s)) {
                // A halted quote is frozen: no hidden drift, trend decay, history point or candle movement occurs.
                continue;
            }
            double prevTick = st.price;
            st.price = evolvePrice(data, s, prevTick, ms, mm, rand, sharedNoise);
            st.appendHistory(st.price);
            if (logTicks) {
                LOG.info("price update: stock {} old {} new {} cost {}",
                        s.code(), String.format(Locale.ROOT, "%.4f", prevTick), String.format(Locale.ROOT, "%.4f", st.price),
                        String.format(Locale.ROOT, "%.4f", data.stockWeightedCost(s.code())));
            }

            // Event drift is consumed only by this tick's quote formula; never write it into the persistent trend.
            // The stock's own random trend therefore resumes immediately when the event module expires.
            st.trendTicksLeft--;
            if (st.trendTicksLeft <= 0) {
                st.trend = rand.nextInt(3) - 1;              // -1 / 0 / 1
                st.trendTicksLeft = 20 + rand.nextInt(41);   // random flip within 20~60 ticks
            }
        }
    }

    /**
     * Advances the configured short-term candle and returns bars closed on this market tick.
     * Daily candles are maintained separately by {@link #closeDailyKlines(MarketSavedData)}.
     */
    private static Map<String, double[]> stepKlines(MarketSavedData data, PushFrame frame) {
        data.klinePeriodTick++;
        boolean close = data.klinePeriodTick >= MarketSettings.server().klineTicksPerBar;
        Map<String, double[]> closed = close ? new LinkedHashMap<>() : Map.of();
        for (Stock s : StockRegistry.getActiveStocks()) {
            StockState st = data.state(s);
            if (st == null) {
                continue;
            }
            if (frame != null && frame.suspended(s)) {
                // Do not manufacture flat OHLC bars or virtual volume while no trading is allowed.
                continue;
            }
            if (st.liveKlineFilled() == 0) {
                st.startLiveKline(st.price);
            } else {
                st.updateLiveKline(st.price);
            }
            if (st.liveDailyKlineFilled() == 0) {
                st.startLiveDailyKline(st.price);
            } else {
                st.updateLiveDailyKline(st.price);
            }
            if (close) {
                st.pushLiveKline();
                // Virtual volume is written only onto the just-closed candle; it never enters any economic settlement path.
                double[] completed = st.lastPushedKline();
                st.addVirtualVolume(cn.oremarket.common.VirtualVolume.amount(
                        s.code(), st.klinePushes(), completed[1], completed[2], 2000.0));
                closed.put(s.code(), st.lastPushedKline());
            }
        }
        if (close) {
            data.klinePeriodTick = 0;
        }
        return closed;
    }

    /** Closes one real trading-day candle for every active, non-halted stock. */
    private static Map<String, double[]> closeDailyKlines(MarketSavedData data) {
        Map<String, double[]> closed = new LinkedHashMap<>();
        for (Stock s : StockRegistry.getActiveStocks()) {
            StockState st = data.state(s);
            if (st == null || st.liveDailyKlineFilled() <= 0) {
                continue;
            }
            st.pushLiveDailyKline();
            double[] completed = st.lastPushedDailyKline();
            st.addVirtualDailyVolume(cn.oremarket.common.VirtualVolume.amount(
                    s.code(), st.dailyKlinePushes(), completed[1], completed[2], 2000.0));
            closed.put(s.code(), st.lastPushedDailyKline());
        }
        return closed;
    }

    /** Remaining halted ticks right now (trade validation and the UI share this basis, derived from server events). */
    public static int suspendTicksLeft(MarketSavedData data, Stock stock) {
        return EventEngine.suspendTicksLeft(data, stock);
    }

    /** Build the market push packet once per marketTick (identical content for every watcher; one NBT reused). */
    private static MarketTickPacket buildTickPacket(MarketSavedData data, Map<String, double[]> closed,
                                                     Map<String, double[]> dailyClosed) {
        return MarketTickPacket.forTick(data, StockRegistry.server().getActiveStocks(), closed, dailyClosed);
    }

    private static void pushUpdate(MinecraftServer server, MarketSavedData data,
                                   Map<String, double[]> closedThisTick,
                                   Map<String, double[]> closedDailyThisTick) {
        OreMarketConfig.SyncModel sm = OreMarketConfig.SYNC_MODEL.get();
        List<ServerPlayer> targets = pushTargets(server, sm);
        if (targets.isEmpty()) {
            // no recipients at all (no players / no player with the UI open): neither build nor send the packet.
            // In single-player with the screen closed, we used to keep composing a market packet plus a full snapshot every marketTick for nothing.
            PUSH_SEEN = false;
            return;
        }
        MarketTickPacket tick = buildTickPacket(data, closedThisTick, closedDailyThisTick);
        PushFrame frame = PushFrame.capture(data);
        if (sm == OreMarketConfig.SyncModel.FULL) {
            // the market packet goes out once per tick; personal snapshots per player (derived data shares one frame)
            for (ServerPlayer p : targets) {
                var target = net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p);
                OreMarketNetwork.CHANNEL.send(target, tick);
                OreMarketNetwork.CHANNEL.send(target, S2CSnapshotPacket.forPlayer(data, p.getUUID(), frame));
            }
            return;
        }
        if (sm == OreMarketConfig.SyncModel.ON_DEMAND) {
            boolean changed = !PUSH_SEEN;
            for (Stock s : StockRegistry.getActiveStocks()) {
                StockState st = data.state(s);
                if (st == null) {
                    continue;
                }
                Double prev = LAST_PUSHED.get(s.code());
                if (prev == null || prev <= 0 || Math.abs(st.price - prev) / prev > 0.01) {
                    changed = true;
                }
                LAST_PUSHED.put(s.code(), st.price);
                // Halt badge freshness: a halt starting or ending must reach the client even when the price sits
                // still (the first observation is not a flip; the price path already pushes those).
                boolean suspNow = frame.suspended(s);
                Boolean prevSusp = LAST_PUSHED_SUSPEND.put(s.code(), suspNow);
                if (prevSusp != null && prevSusp.booleanValue() != suspNow) {
                    changed = true;
                }
            }
            PUSH_SEEN = true;
            if (!changed) {
                return;
            }
            pushToWatchers(targets, data, tick, true, frame);
            return;
        }
        // INCREMENTAL: every marketTick push market packets + personal snapshots to players with the UI open
        pushToWatchers(targets, data, tick, false, frame);
    }

    /**
     * This tick's recipient list (FULL = every online player; otherwise = players with the UI open).
     * An empty list means this whole push can be skipped.
     */
    private static List<ServerPlayer> pushTargets(MinecraftServer server, OreMarketConfig.SyncModel sm) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (sm == OreMarketConfig.SyncModel.FULL || WATCHERS.contains(p.getUUID())) {
                out.add(p);
            }
        }
        return out;
    }

    private static void pushToWatchers(List<ServerPlayer> targets, MarketSavedData data,
                                       MarketTickPacket tick, boolean full, PushFrame frame) {
        for (ServerPlayer p : targets) {
            var target = net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p);
            OreMarketNetwork.CHANNEL.send(target, tick);
            OreMarketNetwork.CHANNEL.send(target, S2CSnapshotPacket.forPlayer(data, p.getUUID(), frame));
        }
    }

    /** Push back a fresh view right after a player trade / exchange so the UI matches the server; also runs level-up detection and player-state push. */
    public static void pushAfterAction(MinecraftServer server, ServerPlayer player) {
        MarketSavedData data = MarketSavedData.get(server);
        // peak just changed: immediately lift highestLevel to max(old, realtime level) (grow-only), announce and push
        LevelService.raise(data, player.getUUID());
        LogService.syncTier(server, data, player);
        var target = net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player);
        OreMarketNetwork.CHANNEL.send(target, S2CSnapshotPacket.forPlayer(data, player.getUUID()));
        LevelService.push(data, player);
    }

    /** Market snapshot for screen reopen / explicit request (full history + candles, dynamic length). */
    public static MarketSnapshotPacket snapshotPacket(MarketSavedData data) {
        return MarketSnapshotPacket.forStocks(data, StockRegistry.server().getActiveStocks());
    }

    /**
     * After listing / delisting / re-listing, proactively push the freshest full snapshot to every player with the UI open.
     * If a registry churn only ships SyncConfigPacket, the client adds the new stock to its list but the quote cache has no entry
     * (MarketTickPacket skips codes missing from the cache), and the UI then shows '--' / 'insufficient data' while shorts can still be opened.
     * The proactive full snapshot fills the gap instantly.
     */
    public static void pushSnapshotToWatchers(MinecraftServer server) {
        if (server == null) {
            return;
        }
        MarketSavedData data = MarketSavedData.get(server);
        MarketSnapshotPacket snap = snapshotPacket(data);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (WATCHERS.contains(p.getUUID())) {
                OreMarketNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p), snap);
            }
        }
    }
}
