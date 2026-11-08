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

package cn.oremarket.client;

import cn.oremarket.common.LoanRules;
import cn.oremarket.market.LoanService;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;
import cn.oremarket.common.StockState;
import cn.oremarket.market.event.MarketEventView;
import cn.oremarket.market.log.MarketLogEntry;
import cn.oremarket.net.packet.LoanInfo;
import cn.oremarket.net.packet.S2CLeaderboardPacket;
import cn.oremarket.net.packet.S2COrderBookPacket;
import cn.oremarket.net.packet.MarketSnapshotPacket;
import cn.oremarket.net.packet.MarketTickPacket;
import cn.oremarket.net.packet.S2CSnapshotPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client only caches state pushed by the server; it does no quote computation, trade settlement or config reading.
 *
 * Changes (quote sync packets + dynamic registry):
 * - **quote cache keyed by stock code**: price / history (<=60) / klineHistory (<=80); built-ins and custom stocks share one Map;
 * the last entry of klineHistory may be the in-progress candle, and MarketTickPacket's klineComplete flag decides append vs overwrite.
 * - **player data cache likewise keyed by code**: sellable / frozen / average cost / realised P&L / suspension / price limits.
 * - the arrays the UI needs are **derived views**: rebuilt against the client registry's active list on each merged redraw,
 * fully dynamic length (getActiveStocks().size()), no fixed 8.
 * - every packet handler only mutates caches and sets the redraw flag; array rebuild and redraw are merged per 250ms by ClientEvents' tick.
 * - level display and unlocks use the server-pushed **highestLevel** (SyncPlayerStatePacket, LongTag, never decreases);
 * reads clamp <1 to 1; a missing peakOreCoins field counts as 0.
 */
public final class MarketClientState {

    /** Global singleton (accessed on the client main thread; packet handling hops back via Minecraft#execute first). */
    public static final MarketClientState INSTANCE = new MarketClientState();

    /** Snapshot status: after the screen opens, wait for MarketSnapshotPacket; nothing within 5 seconds -> FAILED (show the retry button). */
    public enum SnapshotStatus { LOADING, READY, FAILED }

    // ---------------------------------------------------------- quote cache (keyed by code)

    /** Quote cache entry of a single stock. */
    public static final class Quote {
        public double price;
        /** Trend history points (old -> new, at most 60). */
        public final List<Double> history = new ArrayList<>();
        /** Short-term K-lines (old -> new, at most 80; the last candle may be in progress). */
        public final List<double[]> klineHistory = new ArrayList<>();
        /** Independent trading-day K-lines. */
        public final List<double[]> dailyKlineHistory = new ArrayList<>();
        /** Completion flags for the two independent streams. */
        boolean lastKlineComplete = true;
        boolean lastDailyKlineComplete = true;
        /** Server-authoritative total display volume for the currently forming candle. */
        double liveVolume;
        /** Server-authoritative real player volume for the currently forming candle. */
        double livePlayerVolume;
        boolean liveVolumePresent;
        boolean livePlayerVolumePresent;
        /**
         * Quote content version: any history / klineHistory write increments it.
         * Derived arrays ({@code rebuildView}) reuse by version instead of copying 60 points + 80 candles per stock per redraw --
         * the longer the stock list (more random listings), the costlier that copy used to be.
         */
        long revision;
        private double[] historySnapshot = null;
        private double[][] klineSnapshot = null;
        private double[][] dailyKlineSnapshot = null;
        private long snapshotRevision = -1L;

        /** Every call site that mutates history / klineHistory must go through here. */
        void mutated() {
            revision++;
        }

        /** Trend snapshot (the same array is reused while the version is unchanged; the UI reads only). */
        double[] historyArray() {
            ensureSnapshots();
            return historySnapshot;
        }

        /** Short-term K-line snapshot (same rule). */
        double[][] klineArray() {
            ensureSnapshots();
            return klineSnapshot;
        }

        /** Trading-day K-line snapshot (same rule). */
        double[][] dailyKlineArray() {
            ensureSnapshots();
            return dailyKlineSnapshot;
        }

        private void ensureSnapshots() {
            if (snapshotRevision == revision && historySnapshot != null) {
                return;
            }
            double[] arr = new double[history.size()];
            for (int j = 0; j < arr.length; j++) {
                arr[j] = history.get(j);
            }
            double[][] bars = new double[klineHistory.size()][];
            for (int j = 0; j < bars.length; j++) {
                bars[j] = klineHistory.get(j).clone();
            }
            double[][] dailyBars = new double[dailyKlineHistory.size()][];
            for (int j = 0; j < dailyBars.length; j++) {
                dailyBars[j] = dailyKlineHistory.get(j).clone();
            }
            historySnapshot = arr;
            klineSnapshot = bars;
            dailyKlineSnapshot = dailyBars;
            snapshotRevision = revision;
        }

        boolean hasData() {
            return !history.isEmpty() || !klineHistory.isEmpty();
        }
    }

    /** Player-side data of a single stock. */
    private static final class Pos {
        int available;
        int frozen;
        /** Sellable shares locked by orders/limit orders (logical lock). */
        int locked;
        double avgCost;
        double realized;
        double volume;
        double playerVolume;
        boolean playerVolumePresent;
        int trend;
        int suspendTicks;
        double limitPct = 0.10;
        /** Server-wide pure-share pool snapshot per stock; -1 = the server never sent it (treat as uninitialised). */
        int poolMax = -1;
        int poolLeft = -1;
    }

    /** Short position cache (own). */
    public static final class ShortInfo {
        public final String code;
        public final int quantity;
        public final double openPrice;
        public final long margin;
        public final double liquidationPrice;
        /** Server-issued unique position id; close requests echo it back and the server re-verifies by id. */
        public final long id;
        /**
         * The initial margin frozen by the server at opening (margin + fees/taxes).
         * It is separate from currentRemainingMargin, which includes interest and floating P&L. A missing key falls back safely to the stored margin.
         */
        public final double lockedMargin;
        /** Initial margin shown in the table; this is the open-time locked collateral anchor. */
        public final double initialMargin;
        /** Server-calculated current equity: collateral after interest/partial closes plus floating P&L. */
        public final double currentRemainingMargin;
        /** Server-calculated maintenance threshold used by liquidation condition two. */
        public final double liquidationThreshold;
        /** The server-frozen condition-two ratio, used to keep the display current between snapshots. */
        public final double liquidationThresholdRatio;
        /** Server-side cumulative closed short P&L, excluding the still-open floating portion. */
        public final double realizedPnl;

        ShortInfo(String code, int quantity, double openPrice, long margin, double liquidationPrice) {
            this(code, quantity, openPrice, margin, liquidationPrice, 0L, margin, margin, 0.0, 0.0, 0.0);
        }

        ShortInfo(String code, int quantity, double openPrice, long margin, double liquidationPrice,
                  long id, double lockedMargin) {
            this(code, quantity, openPrice, margin, liquidationPrice, id, lockedMargin, margin, 0.0, 0.0, 0.0);
        }

        ShortInfo(String code, int quantity, double openPrice, long margin, double liquidationPrice,
                  long id, double initialMargin, double currentRemainingMargin,
                  double liquidationThreshold, double liquidationThresholdRatio, double realizedPnl) {
            this.code = code;
            this.quantity = quantity;
            this.openPrice = openPrice;
            this.margin = margin;
            this.liquidationPrice = liquidationPrice;
            this.id = id;
            this.lockedMargin = Double.isFinite(initialMargin) ? initialMargin : Math.max(0L, margin);
            this.initialMargin = this.lockedMargin;
            this.currentRemainingMargin = Double.isFinite(currentRemainingMargin) ? currentRemainingMargin : margin;
            this.liquidationThreshold = Double.isFinite(liquidationThreshold) ? Math.max(0.0, liquidationThreshold) : 0.0;
            this.liquidationThresholdRatio = Double.isFinite(liquidationThresholdRatio)
                    ? Math.max(0.0, liquidationThresholdRatio) : 0.0;
            this.realizedPnl = Double.isFinite(realizedPnl) ? realizedPnl : 0.0;
        }
    }

    /** My limit-order cache. */
    public static final class LimitInfo {
        public final long id;
        public final String code;
        public final boolean buy;
        public final double triggerPrice;
        public final int quantity;

        LimitInfo(long id, String code, boolean buy, double triggerPrice, int quantity) {
            this.id = id;
            this.code = code;
            this.buy = buy;
            this.triggerPrice = triggerPrice;
            this.quantity = quantity;
        }
    }

    /** Order book entry (server-wide visibility + own marker). */
    public static final class BookInfo {
        public final long id;
        public final String code;
        public final boolean buy;
        public final int quantity;
        public final double price;
        public final String ownerName;
        /** Publisher UUID string; the own test uses {@link #mine()} computed at read time, independent of packet arrival order. */
        public final String owner;

        BookInfo(long id, String code, boolean buy, int quantity, double price, String ownerName,
                 String owner) {
            this.id = id;
            this.code = code;
            this.buy = buy;
            this.quantity = quantity;
            this.price = price;
            this.ownerName = ownerName;
            this.owner = owner;
        }

        /**
         * Whether this is my own order. {@link #ownerUuid} may not be written yet right after the screen opens, so this is recomputed on
         * every read and never baked into the entry -- otherwise my own orders would look like someone else's.
         */
        public boolean mine() {
            String self = INSTANCE.ownerUuid;
            return owner != null && self != null && !self.isEmpty() && owner.equals(self);
        }
    }

    /** Leaderboard entry. */
    public static final class RankInfo {
        public final String name;
        public final long value;

        RankInfo(String name, long value) {
            this.name = name;
            this.value = value;
        }
    }

    public final List<ShortInfo> shorts = new ArrayList<>();
    public final List<LimitInfo> limitOrders = new ArrayList<>();
    public List<BookInfo> orderBook = new ArrayList<>();
    public final List<RankInfo> assetsBoard = new ArrayList<>();
    public final List<RankInfo> dailyBoard = new ArrayList<>();
    public final List<RankInfo> positionsBoard = new ArrayList<>();
    public final List<RankInfo> debtBoard = new ArrayList<>();
    public final List<RankInfo> creditBoard = new ArrayList<>();
    /** My UUID string (for the order book's Mine marker; set on the client main thread). */
    public volatile String ownerUuid = "";
    public long lockedCoins;
    public long marginTotal;
    public double shortFloat;
    public long leaderboardVersion;
    public int selfRankAssets = -1;
    public int selfRankDaily = -1;
    public int selfRankPositions = -1;
    public int selfRankDebt = -1;
    public int selfRankCredit = -1;

    /** Volatile reference to the quote cache: wholesale atomic swap when the snapshot replaces it (readers always get one complete map). */
    private volatile Map<String, Quote> quotes = new LinkedHashMap<>();
    private final Map<String, Pos> positions = new HashMap<>();

    // ---------------------------------------------------------- redraw merging / race / timeout

    /** Packet handlers should only set this flag; the actual view-array rebuild is merged per 250ms in the tick. */
    public volatile boolean redrawPending;
    /** Timestamp of the latest server push (isResponse=0): a response packet less than 2 seconds after a push is dropped outright. */
    public volatile long lastSyncPushTime;
    public volatile long lastSnapshotPushTime;

    /** Quote snapshot status (each screen open re-runs LOADING -> READY/FAILED). */
    public SnapshotStatus snapshotStatus = SnapshotStatus.LOADING;
    /** Client tick when the request was sent (beyond 100 ticks = 5 seconds without READY means failure). */
    public long snapshotRequestedTick = -1;
    /** Quote packets parked until the first snapshot after the screen opens (skipped when the code is not cached; nothing needs parking here). */
    public long configRequestedTick = -1;

    // ---------------------------------------------------------- global player fields

    public boolean hasData = false;
    /** Whether SyncPlayerStatePacket has been received (player-state cache readiness; the UI shows loading while either cache is missing). */
    public boolean hasPlayerState = false;
    public long tick = -1;
    public long balance;
    public long peak;
    /** highestLevel (the sole basis for display and permission); reads clamp <1 to 1. */
    public long highestLevel = 1;
    /** Top-bar total P&L: computed as double on the server (ordinary + short realized and floating); never narrowed to int. */
    public double totalPnl;
    /** Server-calculated short-only total P&L, including realized history and remaining open floating P&L. */
    public double shortPnlTotal;
    /** Top-bar total assets: the server's single calculateTotalAssets result. */
    public long totalAssets;
    /** dailyBaseline at the start of my trading day; -1 = missing (UI daily-gain shows --). */
    public long dailyBaseline = -1;
    public LoanInfo loan = LoanInfo.empty();
    public MarketEventView[] events = new MarketEventView[0];
    /** Client tick until which the bankruptcy banner shows (3-second banner on the loan page). */
    public long bankruptBannerUntilTick = 0;
    /** Whether the previous snapshot carried a loan: the 0 -> non-zero flip detects bankruptcy/settlement. */
    private boolean hadLoanLastPush = false;

    /** Deadline client tick of the button-press highlight (purely visual, 5 ticks). */
    private final Map<Integer, Integer> pressUntil = new HashMap<>();

    // ---------------------------------------------------------- derived view arrays (rebuilt by refreshView)

    /** Stock count of the current view (= active stocks in the client registry, dynamic). */
    public int viewSize() {
        return codes.length;
    }

    /** Code -> index (swapped together with {@code codes} inside {@link #rebuildView()}, never misaligned). */
    private Map<String, Integer> codeIndex = Map.of();
    public String[] codes = new String[0];
    public Stock[] stocks = new Stock[0];
    public double[] price = new double[0];
    public double[] base = new double[0];
    public int[] trend = new int[0];
    public double[][] history = new double[0][];
    public int[] historyCount = new int[0];
    public double[][][] klines = new double[0][][];
    public double[][][] dailyKlines = new double[0][][];
    /** Whether this index's quote cache has data (false -> the row shows -- and the K-line says insufficient data). */
    public boolean[] hasQuote = new boolean[0];
    public int[] available = new int[0];
    public int[] frozen = new int[0];
    /** Shares tied up on submitted orders and not sellable for now (derived server-side, shipped with the snapshot); really sellable = available - lockedShares. */
    public int[] lockedShares = new int[0];
    public double[] avgCost = new double[0];
    public double[] realizedByStock = new double[0];
    /** Server-side volume of the currently forming candle, display-only. */
    public double[] currentVolume = new double[0];
    public int[] suspendTicks = new int[0];
    public double[] priceLimits = new double[0];
    /** Per-stock pool cap / remaining borrowable shares; -1 = the server never sent it (uninitialised). */
    public int[] poolMax = new int[0];
    public int[] poolLeft = new int[0];

    private MarketClientState() {
    }

    // ---------------------------------------------------------- cache access (keyed by code)

    /** Whether the quote cache holds this code (null/empty returns false). */
    public boolean hasQuoteCache(String code) {
        return code != null && quotes.containsKey(code);
    }

    /** Fetch a quote entry by cached code; no cache returns null. */
    public Quote quoteOf(String code) {
        return code == null ? null : quotes.get(code);
    }

    /** Relist scenarios evict the stale entry from the quote cache and wait for the next snapshot to rebuild it. */
    public void evictQuote(String code) {
        if (code != null && quotes.remove(code) != null) {
            redrawPending = true;
        }
    }

    /** Clear all caches (called on screen close; reusing stale data across opens is forbidden). */
    public void clearMarketCache() {
        quotes = new LinkedHashMap<>();
        redrawPending = false;
        lastSyncPushTime = 0;
        lastSnapshotPushTime = 0;
        snapshotStatus = SnapshotStatus.LOADING;
        snapshotRequestedTick = -1;
        configRequestedTick = -1;
    }

    // ---------------------------------------------------------- packet handling (mutate caches + set flags only)

    /**
     * MarketTickPacket (per-marketTick quote delta) merge rules, implemented point by point as required:
     * - only called with the screen open and registry + quote cache ready (the caller ClientPlay gates the screen);
     * - code missing from the cache or delisted -> skip the whole entry; history/klineHistory fields missing -> skip;
     * - the price always updates; if lastHistory equals the cache's last point, the append is skipped;
     * - history beyond 60 drops the oldest;
     * - klineHistory empty -> append regardless of complete; non-empty + complete=1 -> append (beyond 80 drops the oldest);
     * non-empty + complete=0 -> overwrite the last candle;
     * - only set redrawPending; never rebuild arrays inline.
     */
    public void applyMarketTick(MarketTickPacket p) {
        ListTag stocks = p.stocks();
        Map<String, Quote> cache = quotes;
        boolean touched = false;
        for (int i = 0; i < stocks.size(); i++) {
            if (!(stocks.get(i) instanceof CompoundTag e)) {
                continue;
            }
            String code = e.getString("code");
            if (code == null || code.isEmpty() || StockRegistry.client().isRetired(code)) {
                continue;                       // delisted: skip
            }
            Quote q = cache.get(code);
            if (q == null) {
                // The registry has it but the cache misses (just listed / relisted, full snapshot not arrived yet)
                // -> build a skeleton from this delta packet on the spot, otherwise the quote page stays -- and the K-line says insufficient data.
                if (StockRegistry.client().getByCode(code) == null) {
                    continue;
                }
                q = new Quote();
                cache.put(code, q);
            }
            if (!e.contains("price") || !e.contains("lastHistory")) {
                continue;                       // quote fields missing: skip, wait for the next packet
            }
            q.price = e.getDouble("price");     // the price always updates
            touched = true;

            double lastHistory = e.getDouble("lastHistory");
            int h = q.history.size();
            if (h == 0 || q.history.get(h - 1) != lastHistory) {
                q.history.add(lastHistory);
                while (q.history.size() > StockState.HISTORY_LEN) {
                    q.history.remove(0);        // beyond 60 drop the oldest
                }
                q.mutated();                    // derived arrays invalidate by version
            }

            mergeDailyBar(q, e);
            boolean hasKline = e.contains("klineOpen") && e.contains("klineHigh")
                    && e.contains("klineLow") && e.contains("klineClose")
                    && e.contains("klineComplete");
            if (!hasKline) {
                // A halted stock may have no forming short-term candle. Daily data, if present, was merged above.
                continue;
            }
            double[] bar = {e.getDouble("klineOpen"), e.getDouble("klineHigh"),
                    e.getDouble("klineLow"), e.getDouble("klineClose"),
                    e.contains("klineVolume")
                            ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("klineVolume"))
                            : 0.0,
                    e.contains("klinePlayerVolume")
                            ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("klinePlayerVolume"))
                            : Double.NaN};
            if (e.contains("klineVolume")) {
                q.liveVolume = bar[cn.oremarket.common.MarketVolume.VOLUME_SLOT];
                q.liveVolumePresent = true;
            }
            if (e.contains("klinePlayerVolume")) {
                q.livePlayerVolume = bar[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT];
                q.livePlayerVolumePresent = true;
            }
            boolean complete = e.getByte("klineComplete") != 0;
            if (q.klineHistory.isEmpty()) {
                q.klineHistory.add(bar);
                q.lastKlineComplete = complete;
                q.mutated();
            } else if (complete) {
                if (!q.lastKlineComplete) {
                    // A closing packet corresponds to the client's last forming candle: seal it in place instead of adding a duplicate.
                    int last = q.klineHistory.size() - 1;
                    q.klineHistory.set(last, bar);
                } else {
                    q.klineHistory.add(bar);
                }
                while (q.klineHistory.size() > StockState.KLINE_LEN) {
                    q.klineHistory.remove(0);
                }
                q.lastKlineComplete = true;
                q.mutated();
            } else if (!q.lastKlineComplete) {
                int last = q.klineHistory.size() - 1;
                double[] prev = q.klineHistory.get(last);
                int copy = Math.min(bar.length, prev.length);
                System.arraycopy(bar, 0, prev, 0, copy);
                q.lastKlineComplete = false;
                q.mutated();
            } else {
                // The first new candle after a closing packet must be appended, never overwrite the just-closed candle.
                q.klineHistory.add(bar);
                while (q.klineHistory.size() > StockState.KLINE_LEN) {
                    q.klineHistory.remove(0);
                }
                q.lastKlineComplete = false;
                q.mutated();
            }
        }
        if (touched) {
            redrawPending = true;
        }
    }

    private static void mergeDailyBar(Quote q, CompoundTag e) {
        boolean hasDaily = e.contains("dailyOpen") && e.contains("dailyHigh")
                && e.contains("dailyLow") && e.contains("dailyClose") && e.contains("dailyComplete");
        if (!hasDaily) return;
        double[] bar = {e.getDouble("dailyOpen"), e.getDouble("dailyHigh"), e.getDouble("dailyLow"),
                e.getDouble("dailyClose"), e.contains("dailyVolume")
                ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("dailyVolume")) : 0.0,
                e.contains("dailyPlayerVolume")
                ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("dailyPlayerVolume")) : Double.NaN};
        boolean complete = e.getByte("dailyComplete") != 0;
        if (q.dailyKlineHistory.isEmpty()) {
            q.dailyKlineHistory.add(bar);
        } else if (complete && !q.lastDailyKlineComplete) {
            q.dailyKlineHistory.set(q.dailyKlineHistory.size() - 1, bar);
        } else if (complete || q.lastDailyKlineComplete) {
            q.dailyKlineHistory.add(bar);
        } else {
            q.dailyKlineHistory.set(q.dailyKlineHistory.size() - 1, bar);
        }
        while (q.dailyKlineHistory.size() > StockState.KLINE_LEN) q.dailyKlineHistory.remove(0);
        q.lastDailyKlineComplete = complete;
        q.mutated();
    }

    /**
     * MarketSnapshotPacket (full quotes): replaces the whole quote cache **atomically**; only called with the screen open
     * (the caller handles screen gating and the park-until-registry logic when the registry is still empty).
     */
    public void applyMarketSnapshot(MarketSnapshotPacket p) {
        Map<String, Quote> next = new LinkedHashMap<>();
        ListTag stocks = p.stocks();
        for (int i = 0; i < stocks.size(); i++) {
            if (!(stocks.get(i) instanceof CompoundTag e)) {
                continue;
            }
            String code = e.getString("code");
            if (code.isEmpty()) {
                continue;
            }
            Quote q = new Quote();
            if (e.contains("price")) {
                q.price = e.getDouble("price");
            }
            Tag hist = e.get("history");
            if (hist instanceof ListTag hl) {
                int from = Math.max(0, hl.size() - StockState.HISTORY_LEN);
                for (int j = from; j < hl.size(); j++) {
                    q.history.add(hl.getDouble(j));
                }
            }
            Tag kl = e.get("klineHistory");
            if (kl instanceof ListTag klList) {
                readBars(q.klineHistory, klList);
                q.lastKlineComplete = e.getByte("klineLive") == 0;
                updateLiveVolume(q, q.klineHistory);
            }
            Tag daily = e.get("dailyKlineHistory");
            if (daily instanceof ListTag dailyList) {
                readBars(q.dailyKlineHistory, dailyList);
                q.lastDailyKlineComplete = e.getByte("dailyKlineLive") == 0;
            }
            next.put(code, q);
        }
        quotes = next;                          // volatile reference swapped wholesale; readers never see a half-state
        redrawPending = true;
        snapshotStatus = SnapshotStatus.READY;
    }

    private static void readBars(List<double[]> target, ListTag source) {
        int from = Math.max(0, source.size() - StockState.KLINE_LEN);
        for (int j = from; j < source.size(); j++) {
            if (source.get(j) instanceof CompoundTag bar) {
                target.add(new double[]{bar.getDouble("open"), bar.getDouble("high"),
                        bar.getDouble("low"), bar.getDouble("close"),
                        cn.oremarket.common.MarketVolume.sanitize(bar.getDouble("volume")),
                        bar.contains("playerVolume")
                                ? cn.oremarket.common.MarketVolume.sanitize(bar.getDouble("playerVolume"))
                                : Double.NaN});
            }
        }
    }

    private static void updateLiveVolume(Quote q, List<double[]> bars) {
        if (bars.isEmpty()) return;
        double[] latest = bars.get(bars.size() - 1);
        q.liveVolume = cn.oremarket.common.MarketVolume.of(latest);
        q.livePlayerVolume = latest.length > StockState.PLAYER_VOLUME_SLOT
                ? cn.oremarket.common.MarketVolume.sanitize(latest[StockState.PLAYER_VOLUME_SLOT]) : 0.0;
        q.liveVolumePresent = true;
        q.livePlayerVolumePresent = latest.length > StockState.PLAYER_VOLUME_SLOT
                && Double.isFinite(latest[StockState.PLAYER_VOLUME_SLOT]);
    }

    /** Player data snapshot (S2CSnapshotPacket, dynamic length): full refresh of personal data + prices merged into the cache. */
    public void applySnapshot(S2CSnapshotPacket p) {
        CompoundTag d = p.data;
        hasData = true;
        tick = d.getLong("tick");
        balance = d.getLong("balance");
        peak = d.getLong("peak");
        totalPnl = d.getDouble("totalPnl");
        totalAssets = d.getLong("totalAssets");
        if (d.get("loan") instanceof CompoundTag lt) {
            applyLoan(LoanInfo.fromCompound(lt));
        }
        positions.clear();
        for (int i = 0; i < p.stocks().size(); i++) {
            if (!(p.stocks().get(i) instanceof CompoundTag e)) {
                continue;
            }
            String code = e.getString("code");
            if (code.isEmpty()) {
                continue;
            }
            Pos pos = new Pos();
            pos.available = e.getInt("avail");
            pos.frozen = e.getInt("frozen");
            pos.locked = e.getInt("locked");
            pos.avgCost = e.getDouble("avg");
            pos.realized = e.getDouble("realized");
            pos.volume = e.contains("volume")
                    ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("volume")) : 0.0;
            pos.playerVolume = e.contains("playerVolume")
                    ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("playerVolume")) : 0.0;
            pos.playerVolumePresent = e.contains("playerVolume");
            Quote quote = quotes.get(code);
            if (quote != null && e.contains("volume")) {
                // The post-trade snapshot is authoritative for the forming candle, so a fill becomes visible
                // immediately instead of waiting for the next marketTick packet.
                quote.liveVolume = pos.volume;
                quote.livePlayerVolume = e.contains("playerVolume")
                        ? cn.oremarket.common.MarketVolume.sanitize(e.getDouble("playerVolume")) : 0.0;
                quote.liveVolumePresent = true;
                quote.livePlayerVolumePresent = e.contains("playerVolume");
                if (!quote.klineHistory.isEmpty() && !quote.lastKlineComplete) {
                    double[] current = quote.klineHistory.get(quote.klineHistory.size() - 1);
                    if (current.length > cn.oremarket.common.MarketVolume.VOLUME_SLOT) {
                        current[cn.oremarket.common.MarketVolume.VOLUME_SLOT] = pos.volume;
                        if (e.contains("playerVolume") && current.length > cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT) {
                            current[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT] = quote.livePlayerVolume;
                        }
                    }
                    quote.mutated();
                }
            }
            pos.trend = e.getInt("trend");
            pos.suspendTicks = e.getInt("suspend");
            pos.limitPct = e.getDouble("limit");
            // With the key missing, getInt = 0 would misread never-sent as pool empty -- use contains to tell them apart
            pos.poolMax = e.contains("poolMax") ? e.getInt("poolMax") : -1;
            pos.poolLeft = e.contains("poolLeft") ? e.getInt("poolLeft") : -1;
            positions.put(code, pos);
            Quote q = quotes.get(code);
            if (q != null) {
                q.price = e.getDouble("price");     // cache already has this code: merge the price in
            }
        }
        ListTag evs = d.getList("events", Tag.TAG_COMPOUND);
        MarketEventView[] views = new MarketEventView[evs.size()];
        for (int i = 0; i < evs.size(); i++) {
            views[i] = evs.get(i) instanceof CompoundTag c ? MarketEventView.fromCompound(c) : null;
        }
        events = views;
        // legendary cooldown remaining in game days (hint card)
        legendCooldownDays = d.getInt("legendCdDays");

        // ---------- short positions / limit orders / my orders / locked resources
        shorts.clear();
        for (Tag t : d.getList("shorts", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag sc) {
                long m = sc.getLong("Margin");
                double initial = sc.contains("InitialMargin")
                        ? sc.getDouble("InitialMargin")
                        : (sc.contains("LockedMargin") ? sc.getDouble("LockedMargin") : (double) m);
                double current = sc.contains("CurrentRemainingMargin")
                        ? sc.getDouble("CurrentRemainingMargin") : (double) m;
                double threshold = sc.contains("LiquidationThreshold")
                        ? sc.getDouble("LiquidationThreshold") : 0.0;
                double thresholdRatio = sc.contains("LiquidationThreshold2")
                        ? sc.getDouble("LiquidationThreshold2") : 0.0;
                shorts.add(new ShortInfo(sc.getString("Code"), sc.getInt("Quantity"),
                        sc.getDouble("OpenPrice"), m,
                        sc.getDouble("OpenPrice") * sc.getDouble("LiquidationRatio"),
                        sc.getLong("Id"), initial, current, threshold, thresholdRatio,
                        sc.getDouble("RealizedShortPnl")));
            }
        }
        limitOrders.clear();
        for (Tag t : d.getList("limitOrders", Tag.TAG_COMPOUND)) {
            if (t instanceof CompoundTag lc) {
                limitOrders.add(new LimitInfo(lc.getLong("Id"), lc.getString("Code"),
                        lc.getBoolean("Buy"), lc.getDouble("TriggerPrice"), lc.getInt("Quantity")));
            }
        }
        // the order book list only accepts the server-wide data from S2COrderBookPacket; myOrders inside the personal snapshot
        // is no longer merged into orderBook, avoiding duplicate appends of the same order on every quote refresh.
        lockedCoins = d.getLong("lockedCoins");
        marginTotal = d.getLong("marginTotal");
        shortFloat = d.getDouble("shortFloat");
        shortPnlTotal = d.contains("shortPnlTotal") ? d.getDouble("shortPnlTotal") : shortFloat;
        redrawPending = true;
    }

    /** Order book (server-wide) packet application: the server-wide list overwrites, own entries are kept (carried by the personal snapshot). */
    public void applyOrderBook(S2COrderBookPacket p) {
        List<BookInfo> next = new ArrayList<>();
        for (Tag t : p.orders()) {
            if (t instanceof CompoundTag o) {
                next.add(new BookInfo(o.getLong("Id"), o.getString("Code"), o.getBoolean("Buy"),
                        o.getInt("Quantity"), o.getDouble("Price"), o.getString("OwnerName"),
                        o.getString("Owner")));
            }
        }
        orderBook = next;
        redrawPending = true;
    }

    /** Leaderboard packet application. */
    public void applyLeaderboard(S2CLeaderboardPacket p) {
        fillBoard(assetsBoard, p.board("assets"));
        fillBoard(dailyBoard, p.board("daily"));
        fillBoard(positionsBoard, p.board("positions"));
        fillBoard(debtBoard, p.board("debt"));
        fillBoard(creditBoard, p.board("credit"));
        selfRankAssets = p.payload.getInt("rankAssets");
        selfRankDaily = p.payload.getInt("rankDaily");
        selfRankPositions = p.payload.getInt("rankPositions");
        selfRankDebt = p.payload.getInt("rankDebt");
        selfRankCredit = p.payload.getInt("rankCredit");
        leaderboardVersion++;
        redrawPending = true;
    }

    private static void fillBoard(List<RankInfo> target, ListTag src) {
        target.clear();
        for (Tag t : src) {
            if (t instanceof CompoundTag e) {
                target.add(new RankInfo(e.getString("name"), e.getLong("value")));
            }
        }
    }

    /** Log entry: insert at the head of the in-memory queue, cap at 100; the self-incrementing version triggers UI cache rebuild. */
    public void addLog(MarketLogEntry entry) {
        if (entry == null) {
            return;
        }
        logs.addFirst(entry);
        while (logs.size() > Math.max(10, MarketSettings.current().logMaxEntries)) {
            logs.removeLast();
        }
        logVersion++;
    }

    /** Player logout: clear the client log queue so the next session starts fresh; version self-increments. */
    public void clearLogs() {
        logs.clear();
        logVersion++;
    }

    /** Screen close: clear quote caches, player data caches and every session state except logs (no cross-open reuse). */
    public void clearOnClose() {
        clearMarketCache();
        // button highlight timestamps do not survive across opens either (fixed marker keys, tiny map, plain hygiene)
        pressUntil.clear();
        positions.clear();
        events = new MarketEventView[0];
        hasData = false;
        balance = 0;
        peak = 0;
        highestLevel = 1;
        totalPnl = 0;
        totalAssets = 0;
        loan = LoanInfo.empty();
        hadLoanLastPush = false;
        hasPlayerState = false;
        redrawPending = false;
        // previous-session player-scoped data likewise never survives (cross-server / cross-save residue fix):
        // the order book, the three leaderboards, short/limit lists, the own marker, legendary cooldown and the bankruptcy banner.
        // these are only filled by packets while the screen is open and always rebuilt by server responses after a reopen; clearing costs nothing.
        orderBook = new ArrayList<>();
        shorts.clear();
        limitOrders.clear();
        assetsBoard.clear();
        dailyBoard.clear();
        positionsBoard.clear();
        debtBoard.clear();
        creditBoard.clear();
        ownerUuid = "";
        lockedCoins = 0;
        marginTotal = 0;
        shortFloat = 0;
        shortPnlTotal = 0;
        selfRankAssets = -1;
        selfRankDaily = -1;
        selfRankPositions = -1;
        selfRankDebt = -1;
        selfRankCredit = -1;
        tick = -1;
        dailyBaseline = -1;
        legendCooldownDays = 0;
        bankruptBannerUntilTick = 0;
        rebuildView();
    }

    /**
     * Apply server-pushed loan/trading-day data: refresh immediately on response.
     * Bankruptcy detection: the loan flipping from present to absent while instantly in bankruptcy cooldown -> 3-second settlement banner.
     */
    private void applyLoan(LoanInfo info) {
        loan = info;
        boolean has = loan.loanAmount > 0;
        if (hadLoanLastPush && !has && loan.cooldownEndDay > loan.tradeDay) {
            bankruptBannerUntilTick = ClientEvents.clientTick() + 60;
        }
        hadLoanLastPush = has;
    }

    public boolean showBankruptBanner() {
        return ClientEvents.clientTick() < bankruptBannerUntilTick;
    }

    /** SyncPlayerStatePacket reading rules: highestLevel missing or <1 -> clamp to 1; peakOreCoins missing -> 0. */
    public void applyPlayerState(long highestLevelIn, long peakOreCoinsIn) {
        this.highestLevel = Math.max(1, highestLevelIn);
        this.peak = Math.max(0, peakOreCoinsIn);
        hasPlayerState = true;
        redrawPending = true;
    }

    // ---------------------------------------------------------- derived view rebuild (called on merged redraw)

    private static double selectedVolume(double[] bar) {
        if (bar == null || bar.length <= cn.oremarket.common.MarketVolume.VOLUME_SLOT) {
            return 0.0;
        }
        double total = cn.oremarket.common.MarketVolume.of(bar);
        double player = bar.length > cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT
                ? cn.oremarket.common.MarketVolume.sanitize(
                        bar[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT]) : 0.0;
        return selectedVolume(total, player, bar.length > cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT
                && Double.isFinite(bar[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT]));
    }

    private static double selectedVolume(double total, double player, boolean playerPresent) {
        MarketSettings cfg = MarketSettings.client();
        if (cfg != null && cfg.klineRealPlayerVolumeOnly) {
            return playerPresent
                    ? cn.oremarket.common.MarketVolume.sanitize(player) : 0.0;
        }
        return !Double.isFinite(total) || total < 0.0 ? 0.0 : total;
    }

    /** Public chart helper: applies the server-synced display switch without changing the stored bar. */
    public double volumeForDisplay(double[] bar) {
        return selectedVolume(bar);
    }

    /** Rebuild arrays from the client registry's active list + the two caches. Client main thread only. */
    public void rebuildView() {
        List<Stock> act = StockRegistry.client().getActiveStocks();
        int n = act.size();
        String[] c = new String[n];
        Stock[] sk = new Stock[n];
        double[] pr = new double[n];
        double[] bs = new double[n];
        int[] tr = new int[n];
        double[][] hist = new double[n][];
        int[] hc = new int[n];
        double[][][] kl = new double[n][][];
        double[][][] dailyKl = new double[n][][];
        boolean[] hq = new boolean[n];
        // (short position rows, selection checks, order book rows); the old path linearly scanned the whole stock array per lookup.
        Map<String, Integer> index = new HashMap<>(n * 2);
        int[] av = new int[n];
        int[] fz = new int[n];
        int[] lk = new int[n];
        double[] ac = new double[n];
        double[] rz = new double[n];
        double[] vo = new double[n];
        int[] su = new int[n];
        double[] lm = new double[n];
        int[] pmax = new int[n];
        int[] pleft = new int[n];
        Map<String, Quote> cache = quotes;
        for (int i = 0; i < n; i++) {
            Stock s = act.get(i);
            c[i] = s.code();
            index.put(c[i], i);
            sk[i] = s;
            bs[i] = s.basePrice();
            Quote q = cache.get(s.code());
            if (q != null) {
                pr[i] = q.price;
                // the same quote data is no longer copied and re-allocated between redraws.
                double[] arr = q.historyArray();
                hist[i] = arr;
                hc[i] = arr.length;
                kl[i] = q.klineArray();
                dailyKl[i] = q.dailyKlineArray();
                double[] latestBar = q.klineHistory.isEmpty() ? null
                        : q.klineHistory.get(q.klineHistory.size() - 1);
                vo[i] = q.liveVolumePresent ? selectedVolume(q.liveVolume, q.livePlayerVolume, q.livePlayerVolumePresent)
                        : (latestBar == null ? 0.0 : selectedVolume(latestBar));
                hq[i] = true;
            } else {
                hist[i] = new double[0];
                kl[i] = new double[0][];
                dailyKl[i] = new double[0][];
                hq[i] = false;
            }
            pmax[i] = -1;
            pleft[i] = -1;
            Pos pos = positions.get(s.code());
            if (pos != null) {
                tr[i] = pos.trend;
                av[i] = pos.available;
                fz[i] = pos.frozen;
                lk[i] = pos.locked;
                ac[i] = pos.avgCost;
                rz[i] = pos.realized;
                // The personal snapshot is the immediate post-trade authority; use it when quote data is not newer.
                if (q == null || !q.liveVolumePresent) {
                    vo[i] = selectedVolume(pos.volume, pos.playerVolume, pos.playerVolumePresent);
                }
                su[i] = pos.suspendTicks;
                lm[i] = pos.limitPct;
                pmax[i] = pos.poolMax;
                pleft[i] = pos.poolLeft;
            } else {
                lm[i] = 0.10;
            }
        }
        codes = c;
        stocks = sk;
        price = pr;
        base = bs;
        trend = tr;
        history = hist;
        historyCount = hc;
        klines = kl;
        dailyKlines = dailyKl;
        hasQuote = hq;
        available = av;
        frozen = fz;
        lockedShares = lk;
        avgCost = ac;
        realizedByStock = rz;
        currentVolume = vo;
        suspendTicks = su;
        priceLimits = lm;
        poolMax = pmax;
        poolLeft = pleft;
        codeIndex = index;
    }

    /** Latest K-line of view index i (in the merged model the in-progress candle IS the last one); null when there is no data. */
    public double[] lastKline(int i) {
        if (i < 0 || i >= klines.length) {
            return null;
        }
        double[][] bars = klines[i];
        return bars == null || bars.length == 0 ? null : bars[bars.length - 1];
    }

    /**
     * Index-safe read: whether the quote has a cache (false -> UI shows -- / insufficient data).
     * The current price must be a positive finite value to count as a valid quote -- 0 / NaN is treated like a cache miss.
     */
    public boolean quoteAt(int i) {
        return i >= 0 && i < hasQuote.length && hasQuote[i]
                && i < price.length && price[i] > 0 && Double.isFinite(price[i]);
    }

    /** The stock code of this index; out of range returns null. */
    public String codeAt(int i) {
        return i >= 0 && i < codes.length ? codes[i] : null;
    }

    /** The code's index in the view; -1 when absent (O(1) lookup; the index rebuilds in lockstep with the view arrays). */
    public int indexOfCode(String code) {
        if (code == null) {
            return -1;
        }
        Integer i = codeIndex.get(code);
        return i == null ? -1 : i;
    }

    // ------------------------------------------------------------ loan (display derivation only, no settlement)

    /** Credit line: server config value picked by highestLevel (one tier per level 2/3/4; level 1 is 0). */
    public long creditLimit() {
        MarketSettings cs = MarketSettings.client();
        return cs == null ? 0 : cs.creditLimit(tier());
    }

    /** Borrow eligibility: only highestLevel >= 2 (the level-2 peak threshold is read from config; the text never hardcodes a number). */
    public boolean canBorrowByLevel() {
        return tier() >= LoanRules.requiredTier();
    }

    /** Borrow-button disabled hint: when the level is insufficient the text is composed with the live threshold. */
    public String borrowLevelHint() {
        MarketSettings cs = MarketSettings.client();
        long need = cs == null ? 200L : cs.levelThreshold(LoanRules.requiredTier());
        return cn.oremarket.common.LogFormatter.format("oremarket.hint.borrow_peak", need);
    }

    /** Whether there is an outstanding loan. */
    public boolean hasLoan() {
        return loan.loanAmount > 0;
    }

    /** Trading days left until maturity (0 = due today). */
    public long loanDaysLeft() {
        return loan.loanDueDay - loan.tradeDay;
    }

    /** Whether borrowed today (including loans already repaid). */
    public boolean borrowedToday() {
        return loan.borrowedToday;
    }

    /** Whether in the bankruptcy cooldown. */
    public boolean inBankruptCooldown() {
        return loan.tradeDay < loan.cooldownEndDay;
    }

    /** Days left in the bankruptcy cooldown (same convention as the server's LoanService). */
    public long cooldownDaysLeft() {
        return LoanService.cooldownLeft(loan.cooldownEndDay, loan.tradeDay);
    }

    // ---------------------------------------------------------- loan v2 derivations (display only, no settlement)

    /** Available quota = dynamic quota - outstanding principal (clamped non-negative; dirty data never shows negative). */
    public long availableQuota() {
        return Math.max(0, loan.creditLimit - loan.loanAmount);
    }

    /** Used quota (= outstanding principal). */
    public long usedQuota() {
        return Math.max(0, loan.loanAmount);
    }

    /**
     * Total debt. Part 1: the server's
     * {@link cn.oremarket.net.packet.LoanInfo#loanPayoff} already computes through
     * {@code LoanCalculator.payoff} before sending (fresh per snapshot, not a stale cache); the client adds unforgiven bankruptcy debt
     * and forced-liquidation shortfall.
     * Identity: total debt = principal + interest + penalty + residual liabilities.
     */
    public long totalDebt() {
        if (loan == null) {
            return 0L;
        }
        long a = LoanRules.saturatingAdd(loan.loanPayoff, loan.clawbackDebt);
        return LoanRules.saturatingAdd(a, loan.shortDebt);
    }

    /** Whether there is any debt (including unforgiven bankruptcy debt and the forced-liquidation shortfall). */
    public boolean hasDebt() {
        return hasLoan() || loan.clawbackDebt > 0 || loan.shortDebt > 0;
    }

    /**
     * Interest (excluding penalty). Part 1: shared with the repayment schedule / server payoff through {@code LoanCalculator},
     * taken as planned interest total - repaid interest, under the identity total debt = principal + interest + penalty.
     */
    public long loanInterest() {
        if (loan == null || loan.principal <= 0) {
            return 0L;
        }
        return cn.oremarket.common.LoanCalculator
                .remainingInterestOwed(loan.schedule(), loan.paidInterest);
    }

    /** Repayment schedule: the client rebuilds it with the exact same integer algorithm as the server (packets carry only parameters). */
    public java.util.List<cn.oremarket.common.LoanSchedule.Row> loanSchedule() {
        return loan.schedule();
    }

    /** Product language key / repayment-mode language key (keys come from the server's convention; the UI only translates). */
    public String loanProductKey() {
        return LoanService.productKey(loan.product);
    }

    public String loanModeKey() {
        return LoanService.modeKey(loan.mode);
    }

    /** Credit-tier language key. */
    public String loanScoreKey() {
        return loan.scoreKey();
    }

    /** Number of currently active events. */
    public int eventCount() {
        return events.length;
    }

    /** The live price-limit fraction (decimal) of stock i; abnormal data falls back to the default 10%. */
    public double limitPct(int i) {
        double v = i >= 0 && i < priceLimits.length ? priceLimits[i] : 0.10;
        return v < 0.05 || v > 0.25 ? 0.10 : v;
    }

    /** Whether stock i is suspended (the display check only uses the server-supplied tick count). */
    public boolean suspended(int i) {
        return i >= 0 && i < suspendTicks.length && suspendTicks[i] > 0;
    }

    // ------------------------------------------------------------ level (single source)

    /**
     * The level number shown in the top bar and dots = highestLevel (the server-pushed historical maximum).
     * When the field is missing (old server / packet not received yet) it reads as 1 and never displays 0.
     */
    public int tier() {
        return highestLevel < 1 ? 1 : (int) Math.min(Stock.MAX_TIER, highestLevel);
    }

    /** Whether stock i is unlocked: the same source as the top-bar level and the row dots (one rule for built-ins and customs). */
    public boolean unlocked(int i) {
        Stock s = i >= 0 && i < stocks.length ? stocks[i] : null;
        return s != null && tier() >= s.tier();
    }

    /**
     * Locked-stock hint: unlock at a peak of X ore coins.
     * X comes from the server config cache (category 1); when the cache is absent it falls back to the default threshold, never a hardcoded number.
     */
    public String unlockHint(int i) {
        Stock s = i >= 0 && i < stocks.length ? stocks[i] : null;
        if (s == null) {
            return "";
        }
        MarketSettings cs = MarketSettings.client();
        long need = cs == null ? s.tierUnlockPeak() : cs.levelThreshold(s.tier());
        return cn.oremarket.common.LogFormatter.format("oremarket.hint.unlock_peak", need);
    }

    // ------------------------------------------------------------ position derivations (by current view index)

    /** Total position of stock idx (sellable + frozen). */
    public int totalShares(int idx) {
        if (idx < 0 || idx >= codes.length) {
            return 0;
        }
        return available[idx] + frozen[idx];
    }

    /**
     * Shares of stock idx that are **actually sellable**: the position minus what is already tied up on submitted orders.
     * Same convention as the server's {@code availableUnlocked}; the UI uses only this to decide button enablement.
     */
    public int sellable(int idx) {
        if (idx < 0 || idx >= available.length) {
            return 0;
        }
        int locked = idx < lockedShares.length ? lockedShares[idx] : 0;
        return Math.max(0, available[idx] - locked);
    }

    /** Coins not occupied by any buy order (same convention as the server's availableCoins). */
    public long freeCoins() {
        return Math.max(0L, balance - lockedCoins);
    }

    /** Find an order in the book by id; null once it was filled/cancelled (the selection becomes stale). */
    public BookInfo bookOrder(long id) {
        if (id < 0) {
            return null;
        }
        for (BookInfo b : orderBook) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    /** Floating P&L of stock idx = total position x (current price - average cost). Returns 0 with no position. */
    public double floatPnl(int idx) {
        int sh = totalShares(idx);
        return sh <= 0 ? 0 : sh * (price[idx] - avgCost[idx]);
    }

    /** Floating P&L summed over all positions (trustworthy only when every held stock's cache is complete). */
    public double totalFloatPnl() {
        double sum = 0;
        for (int i = 0; i < viewSize(); i++) {
            sum += floatPnl(i);
        }
        return sum;
    }

    /** Total position value (current price x total position). */
    public double totalMarketValue() {
        double sum = 0;
        for (int i = 0; i < viewSize(); i++) {
            sum += (double) totalShares(i) * price[i];
        }
        return sum;
    }

    /** Whether any stock is held. */
    public boolean hasPosition() {
        for (int i = 0; i < viewSize(); i++) {
            if (totalShares(i) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Total cost basis of all positions (shares x average cost). */
    public double totalCost() {
        double sum = 0;
        for (int i = 0; i < viewSize(); i++) {
            sum += totalShares(i) * avgCost[i];
        }
        return sum;
    }

    /** Any held stock missing its quote cache -> the top bar shows -- for total assets / total P&L. */
    public boolean positionCacheMissing() {
        for (int i = 0; i < viewSize(); i++) {
            if (totalShares(i) > 0 && !hasQuote[i]) {
                return true;
            }
        }
        for (ShortInfo shortInfo : shorts) {
            if (shortInfo != null && shortInfo.quantity > 0 && !hasQuoteCache(shortInfo.code)) {
                return true;
            }
        }
        return false;
    }

    public String trendText(int idx) {
        return switch (trend[idx]) {
            case 1 -> cn.oremarket.common.RawText.t("oremarket.text.0055");
            case -1 -> cn.oremarket.common.RawText.t("oremarket.text.0056");
            default -> cn.oremarket.common.RawText.t("oremarket.text.0050");
        };
    }

    public MarketEventView eventAt(int i) {
        return i >= 0 && i < events.length ? events[i] : null;
    }

    /** Legendary cooldown remaining in game days (0 = hide the hint card; snapshot key legendCdDays). */
    public int legendCooldownDays = 0;

    // ------------------------------------------------------------ logs (in memory only, newest at the head)

    public final Deque<MarketLogEntry> logs = new ArrayDeque<>();
    public int logVersion = 0;

    // ------------------------------------------------------------ button press highlight

    /** Records a button staying press-highlighted until clientTick + 5 (the only animation, purely visual). */
    public void press(int buttonId, int clientTick) {
        pressUntil.put(buttonId, clientTick + 5);
    }

    public boolean isPress(int buttonId, int clientTick) {
        Integer until = pressUntil.get(buttonId);
        return until != null && clientTick < until;
    }

    /** Price change = last point - second-to-last; returns 0 with fewer than two points. */
    public double changeAmount(int i) {
        if (i < 0 || i >= historyCount.length) {
            return 0;
        }
        int c = historyCount[i];
        return c < 2 ? 0 : history[i][c - 1] - history[i][c - 2];
    }

    /** Quote change percentage (against history's second-to-last point; returns 0 with fewer than two points). */
    public double changePercent(int i) {
        if (i < 0 || i >= historyCount.length) {
            return 0;
        }
        int c = historyCount[i];
        if (c < 2) {
            return 0;
        }
        double prev = history[i][c - 2];
        double last = history[i][c - 1];
        return prev <= 0 ? 0 : (last - prev) / prev * 100.0;
    }
}
