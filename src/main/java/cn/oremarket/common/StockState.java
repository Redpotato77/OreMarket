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

package cn.oremarket.common;
import cn.oremarket.market.Stock;

/**
 * Quote state of a single stock: current price, base price, trend (-1/0/1), trend remaining ticks, a 60-entry history,
 * plus the K-line data added. History is a ring buffer: read back oldest..newest after append.
 *
 * K-lines: klineHistory is a ring array of length 80, each element a double[6]
 * = {open, high, low, close, total volume, player volume}; entries beyond 80 evict the oldest one and it never grows unbounded.
 * The current unfinished candle lives in liveK (also 4 doubles); liveFilled records how many
 * marketTicks it has accumulated (0 = nothing accumulating). Period advancement is driven centrally by
 * MarketEngine's global counter - all players and all stocks share one tick count;
 * this class only stores data and keeps no count of its own. All state persists with MarketSavedData and
 * is restored from the save on server restart, so charts are never blank.
 */
public final class StockState {
    public static final int HISTORY_LEN = 60;
    /** K-line history cap: keep at most the latest 80 complete candles. */
    public static final int KLINE_LEN = 80;
    /** K-line array slots: open/high/low/close/total volume/player volume. Both volumes are pure share counts. */
    public static final int KLINE_FIELDS = 6;
    /** Total volume slot: real fills plus the cosmetic virtual forecast on completed bars. */
    public static final int PLAYER_VOLUME_SLOT = 5;

    public final Stock stock;
    public double price;
    public int trend;
    public int trendTicksLeft;
    private final double[] history = new double[HISTORY_LEN];
    private int head;      // next write position
    private int filled;    // entries written so far (<= HISTORY_LEN)
    /** Ring of complete K-lines: elements are double[6] = {open, high, low, close, total volume, player volume}, oldest first. */
    private final double[][] klines = new double[KLINE_LEN][KLINE_FIELDS];
    private int klineHead;      // write position of the next complete candle
    private int klineFilled;    // complete candles retained so far (<= 80)
    /** Total complete candles ever pushed (monotonically increasing): the client uses it to detect 'did I miss one'. */
    private long klinePushes;
    /** Current unfinished short-term K-line: {open, high, low, close, total volume, player volume}. */
    private final double[] liveK = new double[KLINE_FIELDS];
    /** Fills arriving before the next short-term candle starts belong to that candle. */
    private double pendingVolume;
    /** Player fills arriving before the next short-term candle starts belong to that candle. */
    private double pendingPlayerVolume;
    /** Independent ring of completed trading-day candles; daily data is never reconstructed from the short-term view. */
    private final double[][] dailyKlines = new double[KLINE_LEN][KLINE_FIELDS];
    private int dailyKlineHead;
    private int dailyKlineFilled;
    private long dailyKlinePushes;
    /** Current unfinished trading-day candle. */
    private final double[] liveDailyK = new double[KLINE_FIELDS];
    private double pendingDailyVolume;
    private double pendingDailyPlayerVolume;
    private int liveDailyFilled;
    /** marketTicks accumulated in the current unfinished short-term candle (0 = no candle). */
    private int liveFilled;
    /** Whether the first day's volatility has not yet been applied on this stock's first marketTick after (re)listing. */
    private boolean firstDayPending;
    /** Consecutive ticks clipped by the same short-term price limit; transient auction state, never an economic balance. */
    private int limitHitStreak;
    /** Direction of the current transient limit streak: +1 upper limit, -1 lower limit, 0 none. */
    private int limitHitDirection;

    public StockState(Stock stock) {
        this.stock = stock;
        this.price = stock.basePrice();
        this.trend = 0;
        this.trendTicksLeft = 20 + (int) (Math.random() * 41);
        this.history[0] = this.price;
        this.head = 1;
        this.filled = 1;
    }

    /**
     * Initial state for a new stock (custom stock registration, or the re-listing reset):
     * price = base price, and history and klineHistory are EMPTY ARRAYS - never padded with 80 zeros.
     */
    public static StockState empty(Stock stock) {
        StockState st = new StockState(stock, true);
        st.price = stock.basePrice();
        st.firstDayPending = true;
        return st;
    }

    private StockState(Stock stock, boolean emptyInit) {
        this.stock = stock;
        this.price = emptyInit ? stock.basePrice() : stock.basePrice();
        this.trend = 0;
        this.trendTicksLeft = emptyInit ? 20 : 20 + (int) (Math.random() * 41);
        this.head = emptyInit ? 0 : 1;
        this.filled = emptyInit ? 0 : 1;
        if (!emptyInit) {
            this.history[0] = this.price;
        }
    }

    /** Re-listing reset: price returns to the new base price, K-lines and trend history are cleared, everything else unchanged. */
    public void resetFresh() {
        resetFresh(stock.basePrice());
    }

    /**
     * Re-listing reset (explicit base price): after a registry rebuild the same code's base price may have changed with the config;
     * the old {@link #stock} reference still points at the pre-rebuild instance, so the current base price must overwrite it - never read the stale object.
     */
    public void resetFresh(double newBasePrice) {
        price = newBasePrice > 0 ? newBasePrice : stock.basePrice();
        trend = 0;
        trendTicksLeft = 20;
        limitHitStreak = 0;
        limitHitDirection = 0;
        loadHistory(new double[0], 0);
        loadKlines(new double[0][0], 0, new double[KLINE_FIELDS], 0, klinePushes);
        loadDailyKlines(new double[0][0], 0, new double[KLINE_FIELDS], 0, dailyKlinePushes);
        firstDayPending = true;
    }

    /**
     * Records a short-term limit hit without persisting it as market data. A direction change starts a new streak.
     * The return value is the current consecutive-hit count.
     */
    public int noteLimitHit(int direction) {
        int safeDirection = direction < 0 ? -1 : direction > 0 ? 1 : 0;
        if (safeDirection == 0) {
            limitHitStreak = 0;
            limitHitDirection = 0;
            return 0;
        }
        if (safeDirection != limitHitDirection) {
            limitHitDirection = safeDirection;
            limitHitStreak = 1;
        } else if (limitHitStreak < Integer.MAX_VALUE) {
            limitHitStreak++;
        }
        return limitHitStreak;
    }

    /** Clears the transient limit streak after an auction-relief tick. */
    public void clearLimitHitStreak() {
        limitHitStreak = 0;
        limitHitDirection = 0;
    }

    public int limitHitStreak() {
        return limitHitStreak;
    }

    public void appendHistory(double value) {
        history[head] = value;
        head = (head + 1) % HISTORY_LEN;
        if (filled < HISTORY_LEN) {
            filled++;
        }
    }

    /** Return the filled history oldest-to-newest (length <= 60). */
    public double[] recentHistory() {
        double[] out = new double[filled];
        int start = (head - filled + HISTORY_LEN * 2) % HISTORY_LEN;
        for (int i = 0; i < filled; i++) {
            out[i] = history[(start + i) % HISTORY_LEN];
        }
        return out;
    }

    public double latest() {
        return filled == 0 ? price : history[(head - 1 + HISTORY_LEN) % HISTORY_LEN];
    }

    public double previous() {
        if (filled < 2) {
            return latest();
        }
        return history[(head - 2 + HISTORY_LEN) % HISTORY_LEN];
    }

    /** Change percentage against the previous recorded point. */
    public double changePercent() {
        double prev = previous();
        return prev <= 0 ? 0 : (latest() - prev) / prev * 100.0;
    }

    public void loadHistory(double[] values, int count) {
        java.util.Arrays.fill(history, 0);
        filled = Math.min(HISTORY_LEN, Math.max(0, count));
        for (int i = 0; i < filled; i++) {
            history[i] = i < values.length ? values[i] : 0;
        }
        head = filled % HISTORY_LEN;
    }

    public int historyCount() {
        return filled;
    }

    public double historyAt(int i) {
        return history[Math.floorMod(i, HISTORY_LEN)];
    }

    // ------------------------------------------------------------ K-lines

    /** Period start: the new candle's prices use the first tick and delayed fills are carried into its volume. */
    public void startLiveKline(double price) {
        liveK[0] = price;
        liveK[1] = price;
        liveK[2] = price;
        liveK[3] = price;
        liveK[4] = MarketVolume.sanitize(pendingVolume);
        liveK[PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(pendingPlayerVolume);
        pendingVolume = 0.0;
        pendingPlayerVolume = 0.0;
        liveFilled = 1;
    }

    /** Record a quote-moving fill in both the short-term and daily ledgers. */
    public void addTradedVolume(long shares) {
        addVolume(shares, false);
    }

    /** Record a quote-moving player fill in both total and player-only ledgers. */
    public void addPlayerTradedVolume(long shares) {
        addVolume(shares, true);
    }

    /** Record an actual player fill without changing either total-volume ledger. */
    public void addPlayerOnlyVolume(long shares) {
        if (shares <= 0) {
            return;
        }
        if (liveFilled > 0) {
            liveK[PLAYER_VOLUME_SLOT] = MarketVolume.add(liveK[PLAYER_VOLUME_SLOT], shares);
        } else {
            pendingPlayerVolume = MarketVolume.add(pendingPlayerVolume, shares);
        }
        if (liveDailyFilled > 0) {
            liveDailyK[PLAYER_VOLUME_SLOT] = MarketVolume.add(liveDailyK[PLAYER_VOLUME_SLOT], shares);
        } else {
            pendingDailyPlayerVolume = MarketVolume.add(pendingDailyPlayerVolume, shares);
        }
    }

    private void addVolume(long shares, boolean playerFill) {
        if (shares <= 0) {
            return;
        }
        if (liveFilled > 0) {
            liveK[MarketVolume.VOLUME_SLOT] = MarketVolume.add(liveK[MarketVolume.VOLUME_SLOT], shares);
            if (playerFill) {
                liveK[PLAYER_VOLUME_SLOT] = MarketVolume.add(liveK[PLAYER_VOLUME_SLOT], shares);
            }
        } else {
            pendingVolume = MarketVolume.add(pendingVolume, shares);
            if (playerFill) {
                pendingPlayerVolume = MarketVolume.add(pendingPlayerVolume, shares);
            }
        }
        if (liveDailyFilled > 0) {
            liveDailyK[MarketVolume.VOLUME_SLOT] = MarketVolume.add(liveDailyK[MarketVolume.VOLUME_SLOT], shares);
            if (playerFill) {
                liveDailyK[PLAYER_VOLUME_SLOT] = MarketVolume.add(liveDailyK[PLAYER_VOLUME_SLOT], shares);
            }
        } else {
            pendingDailyVolume = MarketVolume.add(pendingDailyVolume, shares);
            if (playerFill) {
                pendingDailyPlayerVolume = MarketVolume.add(pendingDailyPlayerVolume, shares);
            }
        }
    }

    /** Current live total volume, including fills waiting for the next candle after a period boundary. */
    public double liveVolume() {
        return liveFilled > 0 ? MarketVolume.of(liveK) : MarketVolume.sanitize(pendingVolume);
    }

    /** Current live player-only volume; legacy state with no player ledger safely returns zero. */
    public double livePlayerVolume() {
        return liveFilled > 0 ? MarketVolume.sanitize(liveK[PLAYER_VOLUME_SLOT])
                : MarketVolume.sanitize(pendingPlayerVolume);
    }

    /**
     * Display-only volume for the forming candle. The virtual part is recomputed from the current high/low,
     * so a new candle never appears as a misleading zero before its first real fill. It is never stored in
     * {@link #liveK}, never used by price/economy/event logic, and is replaced by the final value on close.
     */
    public double displayVolume() {
        double actual = liveVolume();
        if (liveFilled <= 0) {
            return actual;
        }
        double projected = VirtualVolume.amount(stock.code(), nextKlineSequence(),
                liveK[1], liveK[2], 2000.0);
        double sum = actual + projected;
        if (!Double.isFinite(sum) || sum >= MarketVolume.MAX_VOLUME) {
            return MarketVolume.MAX_VOLUME;
        }
        return MarketVolume.sanitize(sum);
    }

    /** Current live player-only volume, without the cosmetic forecast. */
    public double displayPlayerVolume() {
        return livePlayerVolume();
    }

    private long nextKlineSequence() {
        return klinePushes == Long.MAX_VALUE ? Long.MAX_VALUE : klinePushes + 1L;
    }

    /** Adds cosmetic volume to the newest completed short-term candle only. */
    public void addVirtualVolume(double amount) {
        if (klineFilled <= 0 || !Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        int index = Math.floorMod(klineHead - 1, KLINE_LEN);
        double[] bar = klines[index];
        if (bar != null && bar.length > MarketVolume.VOLUME_SLOT) {
            double current = MarketVolume.of(bar);
            double next = current + MarketVolume.sanitize(amount);
            bar[MarketVolume.VOLUME_SLOT] = !Double.isFinite(next)
                    ? MarketVolume.MAX_VOLUME : Math.min(MarketVolume.MAX_VOLUME, next);
        }
    }

    /** Adds cosmetic volume to the newest completed daily candle only; it never changes the quote or economy. */
    public void addVirtualDailyVolume(double amount) {
        if (dailyKlineFilled <= 0 || !Double.isFinite(amount) || amount <= 0.0) {
            return;
        }
        int index = Math.floorMod(dailyKlineHead - 1, KLINE_LEN);
        double[] bar = dailyKlines[index];
        if (bar != null && bar.length > MarketVolume.VOLUME_SLOT) {
            double next = MarketVolume.of(bar) + MarketVolume.sanitize(amount);
            bar[MarketVolume.VOLUME_SLOT] = !Double.isFinite(next)
                    ? MarketVolume.MAX_VOLUME : Math.min(MarketVolume.MAX_VOLUME, next);
        }
    }

    /**
     * Per marketTick within the short-term period: high = max(current high, current price),
     * low = min(current low, current price), close = current price (open untouched).
     */
    public void updateLiveKline(double price) {
        if (liveFilled <= 0) {
            startLiveKline(price);
            return;
        }
        liveK[1] = Math.max(liveK[1], price);
        liveK[2] = Math.min(liveK[2], price);
        liveK[3] = price;
        liveFilled++;
    }

    /**
     * Records a price change that happens between two scheduled marketTicks (trade, event or settlement).
     * The next candle starts at the pre-change quote when the previous candle has already closed; when a
     * candle is already forming, only the new quote is added so the existing open is preserved.
     * This updates OHLC only and never creates traded volume.
     */
    public void recordPriceChange(double previousPrice, double newPrice) {
        if (!(previousPrice > 0.0) || !Double.isFinite(previousPrice)
                || !(newPrice > 0.0) || !Double.isFinite(newPrice)
                || previousPrice == newPrice) {
            return;
        }
        if (liveFilled <= 0) {
            startLiveKline(previousPrice);
            updateLiveKline(newPrice);
        } else {
            updateLiveKline(newPrice);
        }
        if (liveDailyFilled <= 0) {
            startLiveDailyKline(previousPrice);
            updateLiveDailyKline(newPrice);
        } else {
            updateLiveDailyKline(newPrice);
        }
    }

    /** Starts the independent trading-day candle; its volume includes fills waiting at the day boundary. */
    public void startLiveDailyKline(double price) {
        liveDailyK[0] = price;
        liveDailyK[1] = price;
        liveDailyK[2] = price;
        liveDailyK[3] = price;
        liveDailyK[4] = MarketVolume.sanitize(pendingDailyVolume);
        liveDailyK[PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(pendingDailyPlayerVolume);
        pendingDailyVolume = 0.0;
        pendingDailyPlayerVolume = 0.0;
        liveDailyFilled = 1;
    }

    /** Updates the daily candle with a real quote point. */
    public void updateLiveDailyKline(double price) {
        if (liveDailyFilled <= 0) {
            startLiveDailyKline(price);
            return;
        }
        liveDailyK[1] = Math.max(liveDailyK[1], price);
        liveDailyK[2] = Math.min(liveDailyK[2], price);
        liveDailyK[3] = price;
        liveDailyFilled++;
    }

    public double liveDailyVolume() {
        return liveDailyFilled > 0 ? MarketVolume.of(liveDailyK) : MarketVolume.sanitize(pendingDailyVolume);
    }

    public double liveDailyPlayerVolume() {
        return liveDailyFilled > 0 ? MarketVolume.sanitize(liveDailyK[PLAYER_VOLUME_SLOT])
                : MarketVolume.sanitize(pendingDailyPlayerVolume);
    }

    public double displayDailyVolume() {
        double actual = liveDailyVolume();
        if (liveDailyFilled <= 0) {
            return actual;
        }
        double projected = VirtualVolume.amount(stock.code(), nextDailyKlineSequence(),
                liveDailyK[1], liveDailyK[2], 2000.0);
        double sum = actual + projected;
        return !Double.isFinite(sum) || sum >= MarketVolume.MAX_VOLUME
                ? MarketVolume.MAX_VOLUME : MarketVolume.sanitize(sum);
    }

    private long nextDailyKlineSequence() {
        return dailyKlinePushes == Long.MAX_VALUE ? Long.MAX_VALUE : dailyKlinePushes + 1L;
    }

    public boolean firstDayPending() {
        return firstDayPending;
    }

    public void setFirstDayPending(boolean value) {
        firstDayPending = value;
    }

    /** Period end: push the completed candle into klineHistory (beyond 80 the oldest is evicted); the current one is cleared for the next period to rebuild. */
    public void pushLiveKline() {
        if (liveFilled <= 0) {
            return;
        }
        klines[klineHead] = liveK.clone();
        klineHead = (klineHead + 1) % KLINE_LEN;
        if (klineFilled < KLINE_LEN) {
            klineFilled++;
        }
        klinePushes++;
        liveFilled = 0;
        java.util.Arrays.fill(liveK, 0.0);
    }

    /** Closes the current trading-day candle; no bar is invented for a stock with no quote point. */
    public void pushLiveDailyKline() {
        if (liveDailyFilled <= 0) {
            return;
        }
        dailyKlines[dailyKlineHead] = liveDailyK.clone();
        dailyKlineHead = (dailyKlineHead + 1) % KLINE_LEN;
        if (dailyKlineFilled < KLINE_LEN) {
            dailyKlineFilled++;
        }
        dailyKlinePushes++;
        liveDailyFilled = 0;
        java.util.Arrays.fill(liveDailyK, 0.0);
    }

    /** Complete K-line count (<= 80). */
    public int klineCount() {
        return klineFilled;
    }

    /** Total ever pushed (monotonic, lets the client detect missed packets). */
    public long klinePushes() {
        return klinePushes;
    }

    /** Return the retained complete short-term K-lines oldest to newest. */
    public double[][] recentKlines() {
        return recentRing(klines, klineHead, klineFilled);
    }

    /** Return the retained complete trading-day K-lines oldest to newest. */
    public double[][] recentDailyKlines() {
        return recentRing(dailyKlines, dailyKlineHead, dailyKlineFilled);
    }

    private static double[][] recentRing(double[][] source, int head, int filled) {
        double[][] out = new double[filled][];
        int start = Math.floorMod(head - filled, KLINE_LEN);
        for (int i = 0; i < filled; i++) {
            out[i] = source[(start + i) % KLINE_LEN].clone();
        }
        return out;
    }

    /** The i-th candle (oldest to newest, i < klineCount()). */
    public double[] klineAt(int i) {
        int start = Math.floorMod(klineHead - klineFilled, KLINE_LEN);
        return klines[(start + i) % KLINE_LEN];
    }

    /** The last complete candle pushed (for recovering a missed delta packet); returns all-zero when there is none. */
    public double[] lastPushedKline() {
        if (klineFilled == 0) {
            return new double[KLINE_FIELDS];
        }
        return klines[Math.floorMod(klineHead - 1, KLINE_LEN)].clone();
    }

    /** Current unfinished short-term K-line; use together with liveKlineFilled(). */
    public double[] liveKline() {
        return liveK;
    }

    public int liveKlineFilled() {
        return liveFilled;
    }

    /** Current unfinished trading-day K-line; use together with liveDailyKlineFilled(). */
    public double[] liveDailyKline() {
        return liveDailyK;
    }

    public int liveDailyKlineFilled() {
        return liveDailyFilled;
    }

    public long dailyKlinePushes() {
        return dailyKlinePushes;
    }

    public double[] lastPushedDailyKline() {
        if (dailyKlineFilled == 0) {
            return new double[KLINE_FIELDS];
        }
        return dailyKlines[Math.floorMod(dailyKlineHead - 1, KLINE_LEN)].clone();
    }

    public void loadFirstDayPending(boolean pending) {
        this.firstDayPending = pending;
    }

    /** Restore K-lines from a save: bars are the complete candles oldest-to-newest, live and liveCount the unfinished one, pushes the total ever pushed. */
    public void loadKlines(double[][] bars, int barCount, double[] live, int liveCount, long pushes) {
        klineHead = 0;
        klineFilled = Math.min(KLINE_LEN, Math.max(0, barCount));
        for (int i = 0; i < klineFilled; i++) {
            double[] src = i < bars.length ? bars[i] : null;
            klines[i] = new double[KLINE_FIELDS];
            if (src != null && src.length >= 4) {
                System.arraycopy(src, 0, klines[i], 0, Math.min(KLINE_FIELDS, src.length));
            }
            klines[i][MarketVolume.VOLUME_SLOT] = MarketVolume.sanitize(klines[i][MarketVolume.VOLUME_SLOT]);
            klines[i][PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(klines[i][PLAYER_VOLUME_SLOT]);
            klineHead = (klineHead + 1) % KLINE_LEN;
        }
        // After restore klineHead points at the next write slot, consistent with recentKlines' oldest-to-newest reading.
        klinePushes = Math.max(0, pushes);
        pendingVolume = 0.0;
        pendingPlayerVolume = 0.0;
        if (live != null && live.length >= 4 && liveCount > 0) {
            System.arraycopy(live, 0, liveK, 0, Math.min(KLINE_FIELDS, live.length));
            liveK[MarketVolume.VOLUME_SLOT] = MarketVolume.sanitize(liveK[MarketVolume.VOLUME_SLOT]);
            liveK[PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(liveK[PLAYER_VOLUME_SLOT]);
            liveFilled = liveCount;
        } else {
            java.util.Arrays.fill(liveK, 0.0);
            liveFilled = 0;
        }
    }

    /** Restores the independent daily ring and its unfinished candle. */
    public void loadDailyKlines(double[][] bars, int barCount, double[] live, int liveCount, long pushes) {
        dailyKlineHead = 0;
        dailyKlineFilled = Math.min(KLINE_LEN, Math.max(0, barCount));
        for (int i = 0; i < dailyKlineFilled; i++) {
            double[] src = i < bars.length ? bars[i] : null;
            dailyKlines[i] = new double[KLINE_FIELDS];
            if (src != null && src.length >= 4) {
                System.arraycopy(src, 0, dailyKlines[i], 0, Math.min(KLINE_FIELDS, src.length));
            }
            dailyKlines[i][MarketVolume.VOLUME_SLOT] = MarketVolume.sanitize(dailyKlines[i][MarketVolume.VOLUME_SLOT]);
            dailyKlines[i][PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(dailyKlines[i][PLAYER_VOLUME_SLOT]);
            dailyKlineHead = (dailyKlineHead + 1) % KLINE_LEN;
        }
        dailyKlinePushes = Math.max(0, pushes);
        pendingDailyVolume = 0.0;
        pendingDailyPlayerVolume = 0.0;
        if (live != null && live.length >= 4 && liveCount > 0) {
            System.arraycopy(live, 0, liveDailyK, 0, Math.min(KLINE_FIELDS, live.length));
            liveDailyK[MarketVolume.VOLUME_SLOT] = MarketVolume.sanitize(liveDailyK[MarketVolume.VOLUME_SLOT]);
            liveDailyK[PLAYER_VOLUME_SLOT] = MarketVolume.sanitize(liveDailyK[PLAYER_VOLUME_SLOT]);
            liveDailyFilled = liveCount;
        } else {
            java.util.Arrays.fill(liveDailyK, 0.0);
            liveDailyFilled = 0;
        }
    }
}
