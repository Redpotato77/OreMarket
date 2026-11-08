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

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Leaderboard: REAL-TIME from on, no longer computed once at day close.
 * - refresh(server, data) recomputes the three boards every 5 seconds from ServerTickEvent.END;
 * - daily gain = calculateTotalAssets(player) - dailyBaseline, with all four sites (write, read, leaderboard,
 * top-bar display) sharing the single total-asset formula in MarketSavedData.calculateTotalAssets;
 * - players missing dailyBaseline (offline and never backfilled) get Long.MIN_VALUE as their daily gain, shown as "--";
 * - ranking follows realtime daily gain; the total-assets and holdings boards are recomputed every time too.
 */
public final class LeaderboardService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("OreMarket/Leaderboard");

    /** Sentinel for 'offline / no baseline' rows: the UI renders "--" from it. */
    public static final long NO_BASELINE = Long.MIN_VALUE;

    public static final class Entry {
        public final UUID id;
        public final String name;
        public final long value;

        Entry(UUID id, String name, long value) {
            this.id = id;
            this.name = name;
            this.value = value;
        }
    }

    public static final class Board {
        /** Top N of each board (N = config leaderboardSize). */
        public final List<Entry> top;
        /** The full sorted result: lets any player's rank be queried. */
        public final List<Entry> full;
        private final Map<UUID, Integer> ranks;
        private final Map<UUID, Long> values;

        Board(List<Entry> sortedAll, int limit) {
            // sortedAll is a fresh list owned by this constructor; a read-only wrapper replaces List.copyOf's second full array copy.
            this.full = Collections.unmodifiableList(sortedAll);
            this.top = List.copyOf(sortedAll.subList(0, Math.min(limit, sortedAll.size())));
            this.ranks = new HashMap<>(sortedAll.size());
            this.values = new HashMap<>(sortedAll.size());
            for (int i = 0; i < sortedAll.size(); i++) {
                Entry e = sortedAll.get(i);
                ranks.put(e.id, i + 1);
                values.put(e.id, e.value);
            }
        }

        /** Rank (1-based); returns -1 when the player is not on the board. */
        public int rankOf(UUID id) {
            return ranks.getOrDefault(id, -1);
        }

        /** The player's own value (offline players filled with 0). */
        public long valueOf(UUID id) {
            return values.getOrDefault(id, 0L);
        }

        public static Board empty() {
            return new Board(List.of(), 0);
        }
    }

    public static final class Snapshot {
        public final Board assets;
        public final Board daily;
        public final Board positions;
        /** New v2: the debt board (principal-and-interest due + unwaived bankruptcy debt; higher ranks first). */
        public final Board debt;
        /** New v2: the credit board (credit score 300~950; higher ranks first). */
        public final Board credit;

        Snapshot(Board assets, Board daily, Board positions, Board debt, Board credit) {
            this.assets = assets;
            this.daily = daily;
            this.positions = positions;
            this.debt = debt;
            this.credit = credit;
        }

        public static Snapshot empty() {
            return new Snapshot(Board.empty(), Board.empty(), Board.empty(), Board.empty(), Board.empty());
        }
    }

    private static volatile Snapshot LAST = Snapshot.empty();
    /** Game tick of the last successful recompute; used to judge cache staleness right after the screen opens. */
    private static volatile long lastRefreshTick = Long.MIN_VALUE;
    /** Daily-gain computation log: printed only for the first 10 leaderboard refreshes. */
    private static int dailyLogRefreshes;

    private LeaderboardService() {
    }

    public static Snapshot last() {
        return LAST;
    }

    /**
     * Server-side packing (call on the server main thread only): routes everything through
     * MarketSavedData.calculateTotalAssets; the old function name stays for the snapshot packet. Beyond this single
     * delegation a second total-asset formula is never allowed.
     */
    public static long totalAssets(MarketSavedData data, UUID id) {
        return data.calculateTotalAssets(id);
    }

    /** Unified function-name entry for total asset calculation. */
    public static long calculateTotalAssets(MarketSavedData data, UUID id) {
        return data.calculateTotalAssets(id);
    }

    /** Market value of all holdings (ordinary positions: sellable + frozen, current price x quantity). */
    private static double positionValue(MarketSavedData data, UUID id) {
        return data.positionMarketValue(id);
    }

    /**
     * At the start of each new trading day (when a full day's marketTicks elapse) record the ONLINE players'
     * current total assets as dailyBaseline; offline players are not written here - their baseline backfills on login (once, only when missing).
     */
    public static void recordDailyBaselines(MinecraftServer server, MarketSavedData data) {
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            data.writeDailyBaseline(p.getUUID(), "tradeDayStart");
        }
        data.setDirty();
    }

    /** Old call-name compatibility: day close routes into the same entry. */
    public static void recordBaselines(MinecraftServer server, MarketSavedData data) {
        recordDailyBaselines(server, data);
    }

    /**
     * Recompute the three boards in real time (every 5 seconds + once at close). Player counts are small and
     * everything is computed in memory, so it runs directly on the server main thread - no async thread
     * (an async thread would still read the same mutable data, while real-time refresh demands ordering consistent with the tick).
     */
    public static void refresh(MinecraftServer server, MarketSavedData data) {
        // Candidates = every player with balance / holdings / frozen shares / shorts / loan records.
        // The name list is rebuilt every time (the union itself is cheap), so a cached list can never miss someone when players leave and enter together.
        Set<UUID> ids = new HashSet<>(data.balances.size() + data.holdings.size()
                + data.frozen.size() + data.shorts.size() + data.loans.size());
        ids.addAll(data.balances.keySet());
        ids.addAll(data.holdings.keySet());
        ids.addAll(data.frozen.keySet());
        ids.addAll(data.shorts.keySet());
        ids.addAll(data.loans.keySet());
        Map<UUID, String> names = new HashMap<>(data.playerNames);
        Map<UUID, Long> assets = new HashMap<>();
        Map<UUID, Long> positionValues = new HashMap<>();
        // (no loan means 0, but to make 'who owes most' visible at a glance, zero-debt players still occupy the board, ranked last).
        Map<UUID, Long> debts = new HashMap<>();
        Map<UUID, Long> credits = new HashMap<>();
        for (UUID id : ids) {
            assets.put(id, data.calculateTotalAssets(id));
            positionValues.put(id, (long) Math.max(0, positionValue(data, id)));
            LoanData loan = data.loanOrNull(id);
            // principal-and-interest, unwaived bankruptcy debt, and the forced-liquidation shortfall. The client's total debt
            // (MarketClientState.totalDebt = loanPayoff + clawbackDebt + shortDebt) and the total-asset formula both subtract the third,
            // and only the leaderboard missed it: players who got liquidated looked 'cleaner' here than in reality - one column name, two definitions.
            long debt = loan == null ? 0L
                    : Math.max(0L, loan.payoff()) + Math.max(0L, loan.clawbackDebt)
                            + Math.max(0L, loan.shortDebtDue());
            debts.put(id, debt);
            credits.put(id, (long) LoanService.creditScore(data, id));
        }
        int limit = MarketSettings.server().leaderboardSize;
        // With trading days shorter than 1200 ticks, '10 INFO lines per day' equals spam every few seconds,
        // so the same criterion as setDailyBaseline mutes it; board computation keeps running, only the INFO logging switches off.
        boolean logThisRefresh = dailyLogRefreshes < 10
                && !MarketSettings.quietDailyLogs(MarketSettings.server());
        if (logThisRefresh) {
            dailyLogRefreshes++;
        }
        // The three input tables are already read on this thread (SavedData is main-thread only);
        // sorting and Board building are pure in-memory work, so an async thread does them and the callback publishes on the main thread
        // (leaderboard computation runs outside the main thread after its inputs are captured).
        final Map<UUID, Long> assetsF = assets;
        final Map<UUID, Long> positionValuesF = positionValues;
        final Map<UUID, Long> debtsF = debts;
        final Map<UUID, Long> creditsF = credits;
        final Map<UUID, String> namesF = names;
        final int limitF = limit;
        final List<Entry> dailyInput = buildDailyInput(data, assets, names, logThisRefresh);
        if (ASYNC_ENABLED && server != null) {
            java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> new Snapshot(
                            new Board(sorted(new java.util.LinkedHashMap<>(assetsF), namesF), limitF),
                            new Board(sortedDaily(new ArrayList<>(dailyInput)), limitF),
                            new Board(sorted(new java.util.LinkedHashMap<>(positionValuesF), namesF), limitF),
                            new Board(sorted(new java.util.LinkedHashMap<>(debtsF), namesF), limitF),
                            new Board(sorted(new java.util.LinkedHashMap<>(creditsF), namesF), limitF)))
                    .thenAccept(snap -> server.execute(() -> {
                        LAST = snap;
                        lastRefreshTick = server.getTickCount();
                    }));
            return;
        }
        List<Entry> assetList = sorted(assets, names);
        List<Entry> positionList = sorted(positionValues, names);
        LAST = new Snapshot(new Board(assetList, limit),
                new Board(sortedDaily(buildDailyInput(data, assets, names, logThisRefresh)), limit),
                new Board(positionList, limit),
                new Board(sorted(debts, names), limit),
                new Board(sorted(credits, names), limit));
        if (server != null) {
            lastRefreshTick = server.getTickCount();
        }
    }

    /**
     * Server stopping: clear the board snapshots and the refresh timestamp.
     * LAST holds entries for every player of the previous world; lastRefreshTick is an absolute game tick -
     * if not cleared, after a world switch / restart tickCount starts from 0, getTickCount() - lastRefreshTick goes negative,
     * and refreshIfStale reads the board as 'fresh' until the old value is caught up (possibly very long), showing players the previous world's boards.
     */
    public static void clearAll() {
        LAST = Snapshot.empty();
        lastRefreshTick = Long.MIN_VALUE;
        dailyLogRefreshes = 0;
    }

    /** Async switch (on by default; tests and troubleshooting may turn it off, which returns to pure main-thread synchronous computation). */
    public static volatile boolean ASYNC_ENABLED = true;

    /** Daily-gain input (data read on the main thread; sorting happens on the async thread). */
    private static List<Entry> buildDailyInput(MarketSavedData data, Map<UUID, Long> assets,
                                               Map<UUID, String> names, boolean log) {
        List<Entry> dailyList = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : assets.entrySet()) {
            Long baseline = data.dailyBaseline.get(e.getKey());
            long value = baseline == null ? NO_BASELINE : e.getValue() - baseline;
            if (log) {
                LOG.info("daily pnl: player {} assets {} baseline {} pnl {}",
                        e.getKey(), e.getValue(), baseline == null ? "missing" : baseline,
                        baseline == null ? "--" : value);
            }
            dailyList.add(new Entry(e.getKey(), names.getOrDefault(e.getKey(), e.getKey().toString()), value));
        }
        return dailyList;
    }

    /** Ranking follows realtime daily gain; baseline-less players (--) rank last. */
    private static List<Entry> sortedDaily(List<Entry> dailyList) {
        dailyList.sort(Comparator.comparingLong((Entry e) -> e.value).reversed());
        return dailyList;
    }

    /** Called when a player just opens the screen: while nobody observes, the boards may not have refreshed, so recompute immediately if needed. */
    public static void refreshIfStale(MinecraftServer server, MarketSavedData data) {
        if (server == null || data == null || lastRefreshTick == Long.MIN_VALUE
                || server.getTickCount() - lastRefreshTick >= 100) {
            refresh(server, data);
        }
    }

    /** Old call-name compatibility: post-close board refresh and the timed refresh share the same realtime entry. */
    public static void schedule(MinecraftServer server, MarketSavedData data) {
        refresh(server, data);
    }

    private static List<Entry> sorted(Map<UUID, Long> values, Map<UUID, String> names) {
        List<Entry> list = new ArrayList<>();
        values.forEach((id, value) -> list.add(new Entry(id, names.getOrDefault(id, id.toString()), value)));
        list.sort(Comparator.comparingLong((Entry e) -> e.value).reversed());
        return list;
    }

    public static int rankOf(List<Entry> entries, UUID id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) {
                return i + 1;
            }
        }
        return -1;
    }

}
