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

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import cn.oremarket.common.StockState;

/**
 * All market state lives in the overworld SavedData. From on, every "per stock"
 * piece of data is keyed by the **stock code string** (stocks are no longer an enum but a
 * dynamic class, so EnumMap and fixed arrays are out of the question):
 *
 *  - states: Map&lt;code, market state&gt; (current price, trend, history, candlestick; shared by built-in and custom stocks);
 *  - holdings / frozen: Map&lt;UUID, Map&lt;code, shares&gt;&gt;;
 *  - averageCost / realizedPnl: Map&lt;UUID, Map&lt;code, number&gt;&gt;;
 *  - retired flag: Set&lt;code&gt; (kept in the save, hidden in the UI);
 *  - Ore Coin balances, peak values, highestLevel, loans, Events and the rest follow exactly the previous version's terms.
 *
 * Global data version DataVersion = 2: older saves below 2 get a **load-time** one-off
 * migration (only the built-8 mapping of index 0..7 / lowercase legacy id -> uppercase code),
 * and 2 is written back once the migration completes.
 * Migration can only happen after StockRegistry's static init and before rebuild() (loading
 * rebuilds the objects anyway, so this holds naturally).
 *
 * Note: old saves lacking the highestLevel field are **not backfilled here** - that moved to
 * the highestLevel recalc stage of ServerTickEvent.END (see LevelService.recalcOnLogin); the
 * load stage never writes levels.
 *
 * T+1: shares bought on a day sit in frozen and unfreeze into available at Trading Day close.
 * Trading Day: its length derives from the config tradingDayTicks / marketTickInterval; Ore
 * Coins are not an item and are recorded by UUID only.
 */
public final class MarketSavedData extends SavedData {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Data");

    public static final String DATA_NAME = "oremarket_market";
    /**
     * Global data version: 4 (loans v2: product / repayment mode / installments /
     * penalty interest / credit history / borrowed-funds purse / un-forgiven bankruptcy debt).
     *
     * <p>All loan fields read and write by **field name + default value**, so v3 saves load directly:
     * an old loan comes out as "credit loan + bullet repayment + single installment + no credit
     * history" and behaves exactly like the old version; bumping to 4 only makes the migrate branch
     * (legacy index / legacy event-shape fixes) run once on the first load of the new version.
     */
    public static final int DATA_VERSION = 4;
    /** One candlestick period = 10 marketTicks (this global counter is shared by every player and every stock). */
    public static final int MARKET_TICKS_PER_KLINE = 10;

    /** Legacy save index -> built-in code mapping (0..7 = the order of Stock.BUILTIN_TEMPLATES). */
    static String builtinCodeByLegacyIndex(int index) {
        return index >= 0 && index < Stock.BUILTIN_CODES.size()
                ? Stock.BUILTIN_CODES.get(index) : null;
    }

    /**
     * Old-format stock key -> code string: numeric index 0..7 and lowercase legacy built-in ids
     * (coal etc.) both map; anything else returns upper-cased (whether it is an "unknown code" is
     * decided by the caller against the registry).
     */
    static String resolveLegacyStockKey(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.trim();
        if (s.length() == 1 && s.charAt(0) >= '0' && s.charAt(0) <= '9') {
            String code = builtinCodeByLegacyIndex(s.charAt(0) - '0');
            if (code != null) {
                return code;
            }
        }
        for (String code : Stock.BUILTIN_CODES) {
            if (code.equalsIgnoreCase(s)) {
                return code;
            }
        }
        return s.toUpperCase(Locale.ROOT);
    }

    private static MarketSavedData CACHE;

    /** Per-stock market state: key = the stock code string. */
    public final Map<String, StockState> states = new LinkedHashMap<>();
    public final List<ActiveEvent> events = new ArrayList<>();
    public final Map<UUID, Long> balances = new HashMap<>();
    /** Sellable holdings (the part already unfrozen under T+1): Map&lt;UUID, Map&lt;code, shares&gt;&gt;. */
    public final Map<UUID, Map<String, Integer>> holdings = new HashMap<>();
    /** Frozen holdings: bought today, cannot be sold today. */
    public final Map<UUID, Map<String, Integer>> frozen = new HashMap<>();
    /** All-time peak of the Ore Coin balance: only rises, never falls; only exchange and sell proceeds count (loans and starter coins do not). */
    public final Map<UUID, Long> peakBalances = new HashMap<>();
    /** Highest level ever reached, highestLevel: persisted at the same status as the peak balance, only rises, never falls; 1 for new players missing the field. */
    public final Map<UUID, Long> highestLevels = new HashMap<>();
    /** Average cost basis, averageCost (fees amortized in): Map&lt;UUID, Map&lt;code, cost per share&gt;&gt;. */
    public final Map<UUID, Map<String, Double>> costBasis = new HashMap<>();
    /** Realized P&L, realizedPnl: Map&lt;UUID, Map&lt;code, cumulative&gt;&gt; in Ore Coins, may be negative. */
    public final Map<UUID, Map<String, Double>> realizedPnl = new HashMap<>();
    /** Closed short price P&L by stock code; an accounting ledger only, never a cash-movement instruction. */
    public final Map<UUID, Map<String, Double>> realizedShortPnl = new HashMap<>();
    /** Per-player loan records: principal, interest, due day and bankruptcy cooldown all live here. */
    public final Map<UUID, LoanData> loans = new HashMap<>();
    /** Retirement markers: attached at load when an unknown code shows up; lifted after rebuild() for codes still present in the config. */
    public final Set<String> retiredCodes = new LinkedHashSet<>();

    /** Player Short Positions: player UUID -> stock code -> position. */
    public final Map<UUID, Map<String, ShortPosition>> shorts = new HashMap<>();

    /**
     * The server-wide <b>share cap</b> per stock (maxShortPools).
     * [Business rule] the per-player "short quota" is abolished for one pure share pool per
     * stock; the cap is computed <b>at listing time</b> by inverse-scaling that stock's
     * <b>own base price</b> (see ShortPoolCalculator) and never depends on the live price.
     * Keys missing from old saves are recomputed on the spot by ensureShortPool from the
     * formula; readers never get an NPE.
     */
    public final Map<String, Integer> shortPoolMax = new HashMap<>();
    /** Shares still available to borrow (currentShortPools). Deducted on opening, restored on Close Position / Forced Liquidation, regenerating daily. */
    public final Map<String, Integer> shortPoolLeft = new HashMap<>();

    /**
     * The auto-increment generator for unique Short Position IDs
     * (0 is reserved for the "unassigned" meaning, hence starts at 1). New opens and old-save
     * loads obtain their id through {@link #allocShortId(ShortPosition)}; persisted as NextShortId.
     */
    public long nextShortId = 1;

    /**
     * Mint a globally unique positive ID. Corrupt/overflowed saved counters and duplicate IDs from a damaged save
     * are skipped instead of producing an ID that the close-packet validator can never accept.
     */
    public void allocShortId(ShortPosition pos) {
        if (pos == null) {
            return;
        }
        if (pos.id <= 0 || shortIdInUse(pos.id)) {
            long candidate = nextShortId > 0 ? nextShortId : 1L;
            long start = candidate;
            do {
                if (!shortIdInUse(candidate)) {
                    pos.id = candidate;
                    nextShortId = candidate == Long.MAX_VALUE ? 1L : candidate + 1L;
                    return;
                }
                candidate = candidate == Long.MAX_VALUE ? 1L : candidate + 1L;
            } while (candidate != start);
            throw new IllegalStateException("no free short position id");
        }
        nextShortId = pos.id == Long.MAX_VALUE ? 1L : Math.max(nextShortId, pos.id + 1L);
    }

    private boolean shortIdInUse(long id) {
        if (id <= 0) {
            return false;
        }
        for (Map<String, ShortPosition> map : shorts.values()) {
            for (ShortPosition existing : map.values()) {
                if (existing != null && existing.id == id) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Trading Day when the post-bankruptcy shorting cooldown ends: new Short Positions are blocked while the current Trading Day is below this value (N full Trading Days counted from the day after bankruptcy). */
    public final Map<UUID, Long> shortsCooldownEnd = new HashMap<>();

    /** System loan reserve: while loanDestroyPrincipal/Interest is false, repayments flow in here and are lent out first for subsequent loans. */
    public long loanReserve;

    /**
     * Peer-to-peer loan listings: listingId -> listing. The funds move
     * from the lender's balance into the {@link #p2pEscrow} pool at listing time, so a
     * "phantom listing" cannot exist; a take-off pays out from escrow.
     */
    public final java.util.Map<Long, P2PListing> p2pListings = new java.util.LinkedHashMap<>();
    /** Auto-increment id of the next listing (0 is reserved for the "invalid id" meaning, hence starts at 1). */
    public long p2pNextId = 1;
    /** Total in the P2P escrow pool (= sum of coins across all unfilled listings; for self-check and post-crash reconciliation). */
    public long p2pEscrow;
    /** Orders placed by players; quantity is the unfilled remainder. */
    public final List<PendingOrder> pendingOrders = new ArrayList<>();
    /** Player Limit Orders; quantity is the unfilled remainder. */
    public final List<LimitOrder> limitOrders = new ArrayList<>();
    /** Dynamic stock definitions; kept as delisting records while listed=false. */
    public final Map<String, DynamicStockDef> dynamicStocks = new LinkedHashMap<>();
    /** Count of consecutive Trading Days below the delisting threshold. */
    public final Map<String, Integer> delistCounters = new HashMap<>();
    /**
     * Quote anchor scale per stock code (effective anchor = configured basePrice x this scale).
     * The anchor is what the mean-reversion term pulls toward and what the long-run floor/ceiling band and
     * the delisting threshold are measured from. A split or a dividend moves the share-level quote without
     * changing real value, so the anchor must move by exactly the same ratio; otherwise reversion drags the
     * ex-rights quote back to the old anchor and MINTS (or burns) every holder's market value at the
     * exchange's expense. Missing entry == scale 1.0 (no adjustment yet).
     */
    public final Map<String, Double> quoteAnchorScales = new HashMap<>();
    /** Built-in stock delisting records (code -> the Trading Day of the delisting): written only while listing.builtinDynamicListing is on; cleared once relisted. */
    public final Map<String, Long> builtinDelistedDays = new LinkedHashMap<>();
    /** Total-net-worth baseline taken at the start of each Trading Day. */
    public final Map<UUID, Long> dailyBaseline = new HashMap<>();
    /** Monotonic sequence over orders and their submission time. */
    public long orderSequence;
    /** The marketTick of the last Limit Order matching run. */
    public long lastOrderMatchMarketTick;
    /** Most recent known player name, used when the Leaderboard shows offline players. */
    public final Map<UUID, String> playerNames = new HashMap<>();
    /** Whether the starter Ore Coins were already granted. Persisted in the overworld save so rejoining after a lost player-NBT cannot grant them twice. */
    public final Set<UUID> starterGrantedPlayers = new HashSet<>();
    /**
     * Auto-cancelled orders that happened while the owner was OFFLINE (expiry / delisting / repeated
     * trigger failure): int[0] = pending-order count, int[1] = limit-order count. Consumed and cleared
     * at the owner's next login (OrderNoticeService.onPlayerLogin), so the cancellation is never silent.
     */
    public final Map<UUID, int[]> orderCancelNotice = new HashMap<>();

    /** Player-facing notices queued while the owner was offline. Each entry stores a translation key and string arguments. */
    public static final class OfflineNotice {
        public final String key;
        public final List<String> args;

        public OfflineNotice(String key, List<String> args) {
            this.key = key == null ? "" : key;
            this.args = args == null ? List.of() : List.copyOf(args);
        }
    }

    public final Map<UUID, List<OfflineNotice>> offlineNotices = new HashMap<>();
    private static final int MAX_OFFLINE_NOTICES = 32;

    /** Queues one translated notice for the player's next login, capped to prevent save growth. */
    public void noteOfflineNotice(UUID id, String key, Object... args) {
        if (id == null || key == null || key.isEmpty()) {
            return;
        }
        List<String> values = new ArrayList<>();
        if (args != null) {
            for (Object arg : args) {
                values.add(String.valueOf(arg));
            }
        }
        List<OfflineNotice> queue = offlineNotices.computeIfAbsent(id, k -> new ArrayList<>());
        if (queue.size() >= MAX_OFFLINE_NOTICES) {
            queue.remove(0);
        }
        queue.add(new OfflineNotice(key, values));
        setDirty();
    }

    /** Removes and returns all queued player notices for login replay. */
    public List<OfflineNotice> takeOfflineNotices(UUID id) {
        if (id == null) {
            return List.of();
        }
        List<OfflineNotice> queue = offlineNotices.remove(id);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }
        setDirty();
        return new ArrayList<>(queue);
    }

    /** Adds one offline-window auto-cancellation to the login catch-up counter (limit = limit order, else pending order). */
    public void noteOrderCancel(UUID id, boolean limit, int count) {
        if (id == null || count <= 0) {
            return;
        }
        int[] n = orderCancelNotice.computeIfAbsent(id, k -> new int[2]);
        n[limit ? 1 : 0] += count;
        setDirty();
    }

    /** Removes and returns the login catch-up counter ({pending, limit}); null when nothing is pending or all zero. */
    public int[] takeOrderCancelNotice(UUID id) {
        if (id == null) {
            return null;
        }
        int[] n = orderCancelNotice.remove(id);
        if (n == null || (n[0] <= 0 && n[1] <= 0)) {
            return null;
        }
        return new int[]{Math.max(0, n[0]), Math.max(0, n[1])};
    }

    /** This save's global data version (read at load; below 3 triggers the one-off migration). */
    public int dataVersion;
    /** Migration-log pending flag: migration runs at load time (no server context yet); the log line is written by a tick after startup completes. */
    public boolean migrationLogged = true;

    public long dayProgress;
    public long tickCounter;
    /** marketTicks accumulated so far: drives the Trading Day clock (one close per full cycle, unfreezing the T+1 frozen holdings). */
    public long marketTicks;
    /** marketTicks accumulated inside the current candlestick period (0..9, closes and resets at 10); a single global copy. */
    public int klinePeriodTick;
    /** Trading Day counter: starts at 1 on the server's very first launch. */
    public long tradeDay = 1;
    /** Trading Day of the last legendary Event: after any legendary Event fires, no legendary one appears for the next 3 in-game days. */
    public long lastLegendaryDay = Long.MIN_VALUE / 4;
    /** Game tick of the last legendary Event (legacy-save compatibility field, no longer used in decisions). */
    public long lastLegendaryTick = Long.MIN_VALUE / 4;
    /** Combo/chain keys already fired (comboId|parentUidA|parentUidB): pruned as soon as a parent Event leaves the Event pool, so it never accumulates indefinitely. */
    public final Set<String> comboFiredKeys = new java.util.LinkedHashSet<>();
    /** In-game day the last dividend Event executed on: dividends carry a global 30 in-game day cooldown. */
    public long lastDividendDay = Long.MIN_VALUE / 4;
    /** marketTick of each stock's most recent trading halt: two consecutive halts are at least 200 marketTicks apart. */
    public final Map<String, Long> suspendCooldown = new HashMap<>();
    /** Event instance sequence: the server assigns it centrally in multiplayer; clients just use it as a label. */
    public int eventSeq = 0;
    /** Game-tick basis under which active event durations were created; zero means a new save has not established it yet. */
    public int eventTickInterval = 0;

    public MarketSavedData() {
        this.dataVersion = DATA_VERSION;
        for (Stock s : Stock.BUILTIN_TEMPLATES) {
            states.put(s.code(), new StockState(s));
        }
    }

    /** Fetches the data from the overworld; the cache is cleared on server shutdown so switching saves cannot mix data. */
    public static MarketSavedData get(MinecraftServer server) {
        if (CACHE == null) {
            CACHE = server.overworld().getDataStorage().computeIfAbsent(
                    MarketSavedData::load, MarketSavedData::new, DATA_NAME);
        }
        return CACHE;
    }

    /** Takes the current instance when the server-side registry rebuilds; returns null before initialization and never triggers creation. */
    public static MarketSavedData currentForRegistry() {
        return CACHE;
    }

    public static void invalidateCache() {
        CACHE = null;
    }

    // ---------------------------------------------------------------- state access (by code)

    /** Market state by code; returns null when absent. */
    public StockState state(String code) {
        return states.get(code);
    }

    /** Market state by Stock object; returns null when absent. */
    public StockState state(Stock stock) {
        return stock == null ? null : states.get(stock.code());
    }

    /** Whether the save already has a market-state record for this code (used to tell a relisting from a brand-new listing). */
    public boolean hasStateRecord(String code) {
        return states.containsKey(code);
    }

    /** Makes sure every stock in the registry has a state; returns the ones created now (for new-stock initialization notices). */
    public List<Stock> ensureStatesPresent() {
        List<Stock> created = new ArrayList<>();
        for (Stock s : StockRegistry.server().getActiveStocks()) {
            if (!states.containsKey(s.code())) {
                states.put(s.code(), StockState.empty(s));
                created.add(s);
            }
        }
        if (!created.isEmpty()) {
            setDirty();
        }
        return created;
    }

    // ---------------------------------------------------------------- retirement markers

    public boolean isCodeRetiredFlagged(String code) {
        return retiredCodes.contains(code);
    }

    public void markCodeRetired(String code) {
        if (retiredCodes.add(code)) {
            setDirty();
        }
    }

    public void clearCodeRetired(String code) {
        if (retiredCodes.remove(code)) {
            setDirty();
        }
    }

    // ---------------------------------------------------------------- Ore Coins and peak

    public long balance(UUID id) {
        return Math.max(0L, balances.getOrDefault(id, 0L));
    }

    /** Every balance change passes through here: the peak is refreshed the moment the balance moves (peak only rises, never falls). */
    public void addBalance(UUID id, long delta) {
        long now = adjustBalance(id, delta);
        bumpPeak(id, now);
        setDirty();
    }

    /**
     * Only for spending "your own money" (exchanging minerals): deducts the balance but does
     * **not** drain the loan-funds purse. Routed through plain addBalance, one exchange would
     * also write off the borrowed money sitting in the purse, and "borrow -> exchange -> borrow
     * -> exchange" could turn loans into real assets without limit (a key link 's
     * anti-abuse measure).
     */
    public void spendOwnCoins(UUID id, long cost) {
        if (cost <= 0) {
            return;
        }
        LoanData loan = loans.get(id);
        long purseBefore = loan == null ? 0 : Math.max(0, loan.loanFunds);
        adjustBalance(id, -cost);
        if (loan != null) {
            loan.loanFunds = Math.max(0, purseBefore);   // restore the purse amount (exchanging never touches borrowed funds)
        }
        setDirty();
    }

    /**
     * The server-side hard pre-check entry before a
     * deduction - money is taken only when balance >= cost, under exactly the same rule as
     * {@link #spendOwnCoins} (exchanging never drains the loan-funds purse); if any condition
     * fails not a single coin moves and false is returned. After the deduction, adjustBalance's
     * saturating arithmetic keeps the balance always >= 0 - it can never go negative nor be
     * overwritten with a smaller figure.
     */
    public boolean spendOwnCoinsIfEnough(UUID id, long cost) {
        if (id == null || cost <= 0) {
            return false;
        }
        if (balance(id) < cost) {
            return false;
        }
        spendOwnCoins(id, cost);
        return true;
    }

    /**
     * Only for posting a P2P loan listing: deducts the balance but **does not** drain the
     * loan-funds purse (same rule as spendOwnCoins). Listings may only spend "your own money";
     * see the exchangeableCoins check in LoanService.p2pList.
     */
    public void lockOwnCoins(UUID id, long coins) {
        if (coins <= 0) {
            return;
        }
        LoanData loan = loans.get(id);
        long purseBefore = loan == null ? 0 : Math.max(0, loan.loanFunds);
        adjustBalance(id, -coins);
        if (loan != null) {
            loan.loanFunds = Math.max(0, purseBefore);
        }
        setDirty();
    }

    /**
     * Loan disbursement comes through here: Ore Coins enter the balance but do **not** count
     * toward the all-time peak - only exchange proceeds and stock-sale proceeds raise the peak,
     * blocking "leveling up with borrowed money".
     */
    public void addBalanceNoPeak(UUID id, long delta) {
        adjustBalance(id, delta);
        setDirty();
    }

    /**
     * Credit stock-sale proceeds without letting borrowed principal become freely exchangeable.
     * A loan-funded buy/sell round trip must keep the recovered principal in the loan purse.
     */
    public void creditSaleProceeds(UUID id, long gain) {
        if (gain <= 0L) {
            return;
        }
        LoanData loan = loans.get(id);
        long purse = loan == null ? 0L : Math.max(0L, loan.loanFunds);
        long outstanding = loan == null ? 0L : Math.max(0L, loan.amount);
        long locked = Math.min(gain, Math.max(0L, outstanding - purse));
        long now = adjustBalance(id, gain);
        if (loan != null && locked > 0L) {
            long restored = purse > Long.MAX_VALUE - locked
                    ? Long.MAX_VALUE : purse + locked;
            loan.loanFunds = Math.max(purse, Math.min(outstanding, restored));
        }
        // Only the non-loan portion may establish a new historical peak or level.
        bumpPeak(id, Math.max(0L, now - locked));
        setDirty();
    }

    /** The balance is a non-negative economic value; every add/sub goes through saturating arithmetic so gigantic admin-set numbers cannot wrap the long into negative. */
    private long adjustBalance(UUID id, long delta) {
        if (id == null) {
            return 0L;
        }
        long before = Math.max(0L, balances.getOrDefault(id, 0L));
        long value;
        if (delta > 0L && before > Long.MAX_VALUE - delta) {
            value = Long.MAX_VALUE;
        } else if (delta < 0L && (delta == Long.MIN_VALUE || before < -delta)) {
            value = 0L;
        } else {
            value = before + delta;
        }
        // Purse money is never allowed to exchange for minerals (see the own-check in TradeService.withdraw),
        // but buying stocks, paying fees and repaying loans may all use it - so every deduction trims the purse right here.
        if (delta < 0L) {
            LoanData loan = loans.get(id);
            if (loan != null && loan.loanFunds > 0) {
                loan.loanFunds = Math.max(0L, loan.loanFunds + delta);
            }
        }
        if (value <= 0L) {
            balances.remove(id);
            return 0L;
        }
        balances.put(id, value);
        return value;
    }

    /** Sets the balance outright (used by the bankruptcy zeroing); the peak only rises, never falls, and zeroing does not affect the level. */
    public void setBalance(UUID id, long value) {
        if (value <= 0) {
            balances.remove(id);
        } else {
            balances.put(id, value);
        }
        setDirty();
    }

    private void bumpPeak(UUID id, long balance) {
        long v = Math.max(balance, 0);
        long before = peakBalances.getOrDefault(id, 0L);
        if (v <= before) {
            return;
        }
        peakBalances.put(id, v);
        // the moment the peak moves, recompute the level from the **live thresholds** and write it back monotonically (the key step that separates decision from display)
        raiseHighestLevel(id, LevelService.realtimeLevel(v));
    }

    /**
     * Lifts highestLevel to max(old value, given level): monotonic, level 1 is the floor.
     * Returns whether a lift happened. Backfilling old saves without the highestLevel field also
     * goes through here (called from the recalculation stage on ServerTickEvent.END).
     */
    public boolean raiseHighestLevel(UUID id, long level) {
        long v = Math.max(1, level);
        long cur = highestLevelOf(id);
        if (v <= cur) {
            return false;
        }
        highestLevels.put(id, v);
        setDirty();
        return true;
    }

    /** The all-time highest level (1 when the field is missing; stored values below 1 are clamped to 1). */
    public long highestLevelOf(UUID id) {
        long v = highestLevels.getOrDefault(id, 1L);
        if (v < 1) {
            highestLevels.put(id, 1L);
            return 1;
        }
        return v;
    }

    /** Whether the player already has a persisted highestLevel record (false for old saves missing the field; the backfill condition). */
    public boolean hasHighestLevelRecord(UUID id) {
        return highestLevels.containsKey(id);
    }

    /** Backfill a record and persist it (used by the login recalculation stage; level floors at 1). */
    public void backfillHighestLevel(UUID id, long level) {
        highestLevels.put(id, Math.max(1, level));
        setDirty();
    }

    public long peak(UUID id) {
        return peakBalances.getOrDefault(id, 0L);
    }

    /** Unlock check (permission): always judged on the all-time highest level; once unlocked, never revoked. */
    public boolean unlocked(UUID id, Stock stock) {
        return playerTier(id) >= stock.tier();
    }

    /** Level number used for display and permissions = highestLevel (only rises, never falls). */
    public int playerTier(UUID id) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, highestLevelOf(id)));
    }

    /** Real-time tier (used for decisions only, never for display): live peak compared against live config thresholds. */
    public int realtimeTier(UUID id) {
        return Stock.tierForPeak(peak(id));
    }

    // ---------------------------------------------------------------- holdings (sellable / frozen)

    /** A player's sellable holdings: code -> shares (read-only view, may be empty). */
    public Map<String, Integer> holdingsOf(UUID id) {
        return holdings.getOrDefault(id, Map.of());
    }

    /** A player's frozen holdings: code -> shares (read-only view, may be empty). */
    public Map<String, Integer> frozenOf(UUID id) {
        return frozen.getOrDefault(id, Map.of());
    }

    /** Sellable quantity. */
    public int shares(UUID id, Stock stock) {
        return stock == null ? 0 : holdings.getOrDefault(id, Map.of()).getOrDefault(stock.code(), 0);
    }

    public int available(UUID id, Stock stock) {
        return shares(id, stock);
    }

    /** Frozen shares bought today that have not unfrozen yet. */
    public int frozenShares(UUID id, Stock stock) {
        return stock == null ? 0 : frozen.getOrDefault(id, Map.of()).getOrDefault(stock.code(), 0);
    }

    /** Total holdings = sellable + frozen, saturated so two valid buckets cannot wrap into a negative count. */
    public int totalShares(UUID id, Stock stock) {
        long total = (long) shares(id, stock) + frozenShares(id, stock);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    public void addShares(UUID id, Stock stock, int delta) {
        if (stock == null) {
            return;
        }
        Map<String, Integer> map = holdings.computeIfAbsent(id, k -> new HashMap<>());
        // long-addition cap: huge numbers from any external source (admin commands and the like) can never overflow the int back into negative territory
        mergeCapped(map, stock.code(), delta);
        if (map.isEmpty()) {
            holdings.remove(id);
        }
        setDirty();
    }

    /** Buying: newly bought shares always go into the frozen bucket (T+1). */
    public void addFrozen(UUID id, Stock stock, int delta) {
        if (delta <= 0 || stock == null) {
            return;
        }
        Map<String, Integer> map = frozen.computeIfAbsent(id, k -> new HashMap<>());
        mergeCapped(map, stock.code(), delta);
        setDirty();
    }

    /** Trading Day close: all frozen shares unfreeze into the sellable holdings. */
    public void unfreezeAll() {
        if (frozen.isEmpty()) {
            return;
        }
        frozen.forEach((id, map) -> {
            Map<String, Integer> avail = holdings.computeIfAbsent(id, k -> new HashMap<>());
            map.forEach((code, qty) -> mergeCapped(avail, code, Math.max(0, qty)));
        });
        frozen.clear();
        setDirty();
    }

    // ---------------------------------------------------------------- admin test hooks: freeze / unfreeze / adjust holdings
    //
    // For the /oremarket hold command family only (permission level >= 2); it rides the very same data
    // structures as ordinary trading: holdings = sellable, frozen = frozen (not sellable); the cost basis
    // resets under the "clear" rule only when total holdings reach zero.

    /** Merge share counts (long addition capped at Integer.MAX_VALUE, key dropped at zero; integer overflow can never wrap). */
    private static void mergeCapped(Map<String, Integer> map, String code, int delta) {
        long now = map.getOrDefault(code, 0).longValue() + delta;
        if (now <= 0) {
            map.remove(code);
        } else {
            map.put(code, (int) Math.min(now, Integer.MAX_VALUE));
        }
    }

    /**
     * Admin unfreeze: move **all** of one player's frozen shares of one stock into the sellable
     * bucket (same destination as the close-of-day unfreeze). Returns the shares unfrozen; 0 means
     * that stock had no frozen holdings to begin with.
     */
    public int unfreezePlayerShares(UUID id, Stock stock) {
        if (id == null || stock == null) {
            return 0;
        }
        Map<String, Integer> fMap = frozen.get(id);
        if (fMap == null) {
            return 0;
        }
        int qty = Math.max(0, fMap.getOrDefault(stock.code(), 0));
        if (qty <= 0) {
            return 0;
        }
        mergeCapped(fMap, stock.code(), -qty);
        if (fMap.isEmpty()) {
            frozen.remove(id);
        }
        addShares(id, stock, qty);
        setDirty();
        return qty;
    }

    /** Admin unfreeze: all of a player's frozen holdings turn sellable; returns the total shares unfrozen. */
    public int unfreezePlayerAll(UUID id) {
        if (id == null) {
            return 0;
        }
        Map<String, Integer> fMap = frozen.remove(id);
        if (fMap == null || fMap.isEmpty()) {
            return 0;
        }
        Map<String, Integer> avail = holdings.computeIfAbsent(id, k -> new HashMap<>());
        int total = 0;
        for (Map.Entry<String, Integer> e : fMap.entrySet()) {
            int qty = Math.max(0, e.getValue());
            if (qty <= 0) {
                continue;
            }
            mergeCapped(avail, e.getKey(), qty);
            total = (int) Math.min((long) Integer.MAX_VALUE, total + (long) qty);
        }
        if (avail.isEmpty()) {
            holdings.remove(id);
        }
        setDirty();
        return total;
    }

    /**
     * Admin freeze: all of one player's **sellable** shares of one stock move into the frozen
     * bucket (unsellable, and no longer lockable by orders). Returns the shares frozen; 0 means
     * there was nothing to freeze.
     */
    public int freezePlayerShares(UUID id, Stock stock) {
        if (id == null || stock == null) {
            return 0;
        }
        int qty = shares(id, stock);
        if (qty <= 0) {
            return 0;
        }
        addShares(id, stock, -qty);
        addFrozen(id, stock, qty);
        clampSellOrders(id, stock.code());
        setDirty();
        return qty;
    }

    /** Admin freeze: all of a player's sellable holdings move into the frozen bucket; returns the total shares frozen. */
    public int freezePlayerAll(UUID id) {
        if (id == null) {
            return 0;
        }
        Map<String, Integer> map = holdings.get(id);
        if (map == null || map.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (String code : new java.util.ArrayList<>(map.keySet())) {
            int qty = Math.max(0, map.getOrDefault(code, 0));
            if (qty <= 0) {
                continue;
            }
            mergeCapped(map, code, -qty);
            mergeCapped(frozen.computeIfAbsent(id, k -> new HashMap<>()), code, qty);
            clampSellOrders(id, code);
            total = (int) Math.min((long) Integer.MAX_VALUE, total + (long) qty);
        }
        if (map.isEmpty()) {
            holdings.remove(id);
        }
        setDirty();
        return total;
    }

    /**
     * Admin shrink: deduct a given amount from one player's one stock's **total position**
     * (sellable first, frozen after, never below 0). When the total position reaches zero the
     * average cost resets under the "clear" rule; returns the shares actually removed (a request
     * bigger than the position simply drains it to 0).
     */
    public int reducePlayerPosition(UUID id, Stock stock, int amount) {
        if (id == null || stock == null || amount <= 0) {
            return 0;
        }
        int fromAvail = Math.min(amount, Math.max(0, shares(id, stock)));
        if (fromAvail > 0) {
            addShares(id, stock, -fromAvail);
        }
        int fromFrozen = 0;
        int rest = amount - fromAvail;
        if (rest > 0) {
            Map<String, Integer> fMap = frozen.get(id);
            int f = fMap == null ? 0 : Math.max(0, fMap.getOrDefault(stock.code(), 0));
            fromFrozen = Math.min(rest, f);
            if (fromFrozen > 0) {
                mergeCapped(fMap, stock.code(), -fromFrozen);
                if (fMap.isEmpty()) {
                    frozen.remove(id);
                }
            }
        }
        int removed = fromAvail + fromFrozen;
        if (removed > 0) {
            clampSellOrders(id, stock.code());
            if (totalShares(id, stock) <= 0) {
                clearPositionCost(id, stock, "clear");
            }
            setDirty();
        }
        return removed;
    }

    /**
     * Trim this player's sell orders (both pending and Limit Orders) for this code down to the
     * current sellable holdings. Admin shrink / freeze and bankruptcy zeroing can make holdings
     * vanish before the orders do; untrimmed leftovers would sell shares the player no longer
     * owns (ghost sell orders). Zero-quantity orders get removed by their own cleanup cycles.
     */
    public void clampSellOrders(UUID id, String code) {
        if (id == null || code == null) {
            return;
        }
        int budget = Math.max(0, holdings.getOrDefault(id, Map.of()).getOrDefault(code, 0));
        boolean changed = false;
        for (PendingOrder o : pendingOrders) {
            if (!o.buy && o.owner.equals(id) && code.equals(o.code) && o.quantity > 0) {
                if (o.quantity > budget) {
                    o.quantity = budget;
                    changed = true;
                }
                budget -= o.quantity;
            }
        }
        for (LimitOrder o : limitOrders) {
            if (!o.buy && o.owner.equals(id) && code.equals(o.code) && o.quantity > 0) {
                if (o.quantity > budget) {
                    o.quantity = budget;
                    changed = true;
                }
                budget -= o.quantity;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    /** Bankruptcy zeroing: sellable and frozen holdings plus average costs are all wiped; the peak stands and the level never drops. Costs are logged under the clear rule. */
    public void clearPositions(UUID id) {
        java.util.Set<String> codes = new java.util.HashSet<>();
        Map<String, Integer> held = holdings.get(id);
        if (held != null) {
            codes.addAll(held.keySet());
        }
        Map<String, Integer> frz = frozen.get(id);
        if (frz != null) {
            codes.addAll(frz.keySet());
        }
        holdings.remove(id);
        frozen.remove(id);
        // Holdings are gone but the old sell orders remain: trim each code to 0 and let the next matching cycle clean up, eradicating ghost sell orders
        for (String code : codes) {
            clampSellOrders(id, code);
        }
        Map<String, Double> costs = costBasis.remove(id);
        if (costs != null) {
            costs.forEach((code, old) -> LOG.info("setCost: player {} stock {} old {} new 0 reason clear",
                    id, code, String.format(Locale.ROOT, "%.4f", old)));
        }
        setDirty();
    }

    // ---------------------------------------------------------------- Short Positions

    /** All of a player's Short Positions (read-only view, may be empty). */
    public Map<String, ShortPosition> shortsOf(UUID id) {
        return shorts.getOrDefault(id, Map.of());
    }

    public ShortPosition shortPosition(UUID id, Stock stock) {
        return stock == null ? null : shortsOf(id).get(stock.code());
    }

    /**
     * Books one short position without ever overwriting an existing owner/code
     * position. The boolean result lets the caller roll back an already prepared
     * opening if a duplicate reaches this data-layer guard.
     */
    public boolean openShort(ShortPosition pos) {
        if (pos == null || pos.quantity <= 0 || pos.owner == null || pos.code.isEmpty()) {
            return false;
        }
        Map<String, ShortPosition> mine = shorts.computeIfAbsent(pos.owner, k -> new HashMap<>());
        if (mine.containsKey(pos.code)) {
            return false;
        }
        // A unique ID is minted at open time (Close Position requests re-check by ID; repeat calls are side-effect free - an existing ID is never re-numbered)
        allocShortId(pos);
        mine.put(pos.code, pos);
        setDirty();
        return true;
    }

    public void removeShort(UUID id, String code) {
        Map<String, ShortPosition> map = shorts.get(id);
        if (map != null && map.remove(code) != null) {
            if (map.isEmpty()) {
                shorts.remove(id);
            }
            setDirty();
        }
    }

    /** Sum of the player's Short Position margins (part of the Leaderboard total-assets rule). */
    public long shortMarginTotal(UUID id) {
        long sum = 0;
        for (ShortPosition p : shortsOf(id).values()) {
            long margin = Math.max(0L, p.margin);
            sum = saturatingAdd(sum, margin);
        }
        return sum;
    }

    /** Sum of the player's Short Position floating P&L. */
    public double shortFloatingPnl(UUID id) {
        double sum = 0;
        for (ShortPosition p : shortsOf(id).values()) {
            StockState st = state(p.code);
            // With the current price missing / non-positive, count the open price (floating P&L = 0); never price at 0 to fabricate a profit.
            double px = (st != null && st.price > 0 && Double.isFinite(st.price))
                    ? st.price : p.openPrice;
            sum += p.floatingPnl(px);
        }
        return sum;
    }

    /** Trading Day when the player's post-bankruptcy shorting cooldown ends (0 when no record; new Short Positions are blocked while the current Trading Day < this value). */
    public long shortsCooldownEndDay(UUID id) {
        Long v = shortsCooldownEnd.get(id);
        return v == null ? 0L : v;
    }

    /** Records the post-bankruptcy shorting cooldown end day (written at bankruptcy liquidation; keeps the later value if an earlier one exists). */
    public void setShortsCooldownEnd(UUID id, long endDay) {
        if (id != null && endDay > shortsCooldownEndDay(id)) {
            shortsCooldownEnd.put(id, endDay);
            setDirty();
        }
    }

    // ---------------------------------------------------------------- short pool

    /**
     * The pool cap of one stock; returns -1 when missing (old save / stock never went through a
     * listing Event). Callers must then run {@link #ensureShortPool(String, double)} to fill it
     * in first - -1 is never allowed into business arithmetic.
     */
    public int shortPoolMax(String code) {
        Integer v = code == null ? null : shortPoolMax.get(code);
        return v == null ? -1 : Math.max(0, v);
    }

    /** The pool remainder of one stock; recomputed from the base price when missing, so readers never hit an NPE. */
    public int shortPoolLeftOrInit(String code, double basePrice) {
        if (code == null) {
            return 0;
        }
        if (!shortPoolMax.containsKey(code)) {
            ensureShortPool(code, basePrice);
        }
        Integer left = shortPoolLeft.get(code);
        int max = Math.max(0, shortPoolMax.getOrDefault(code, 0));
        if (left == null) {
            return max;
        }
        return Math.min(Math.max(0, left), max);
    }

    /**
     * Read-only fast cache of the pool remainder (every business call site has already keyed it
     * through {@link #ensureShortPool}; no recomputation here). Clamped to [0, max]; returns 0 on
     * a missing key - for critical-section checks and Debug Snapshot printing, pure read, zero
     * side effects.
     */
    public int shortPoolLeftNow(String code) {
        int max = Math.max(0, shortPoolMax.getOrDefault(code, 0));
        if (max <= 0) {
            return 0;
        }
        return Math.min(Math.max(0, shortPoolLeft.getOrDefault(code, max)), max);
    }

    /**
     * Creates the short pool of one stock when absent (the shared entry point for old-save
     * compatibility and the listing Event). [Business rule] a pure share-count formula:
     * {@code clamp(floor(defaultBaseShares / base price of the stock), absoluteMinShares, absoluteMaxShares)},
     * treating a base price <= 0 as 1.0 to guard against division by zero. An existing pool
     * (including shares already on loan) **gets its key filled in but its balance is never
     * reset**, so a hot reload cannot swallow shares players have already borrowed.
     *
     * @return true = this call really created the pool; false = it already existed (balance kept).
     */
    public boolean ensureShortPool(String code, double basePrice) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        if (shortPoolMax.containsKey(code)) {
            if (!shortPoolLeft.containsKey(code)) {
                shortPoolLeft.put(code, shortPoolMax.get(code));
                setDirty();
            }
            return false;
        }
        cn.oremarket.market.MarketSettings ms =
                cn.oremarket.market.MarketSettings.server();
        int max = cn.oremarket.common.ShortPoolCalculator
                .maxSharesFor(basePrice, ms.shortPoolDefaultShares,
                        ms.shortPoolAbsoluteMax, ms.shortPoolAbsoluteMin);
        shortPoolMax.put(code, max);
        shortPoolLeft.put(code, max);
        setDirty();
        return true;
    }

    /**
     * Consumes the pool when opening a position (called on the server main thread; the caller has
     * already serialized the checks under the same lock). [Formula example] coal pool has 4934
     * left, 66 shares borrowed -> 4868 remain after the deduction; with insufficient balance it
     * returns false and **not a single share moves**.
     */
    public boolean shortPoolConsume(String code, int qty) {
        if (code == null || qty <= 0) {
            return false;
        }
        int left = Math.max(0, shortPoolLeft.getOrDefault(code, 0));
        if (left < qty) {
            return false;
        }
        shortPoolLeft.put(code, left - qty);
        setDirty();
        return true;
    }

    /**
     * Returns shares to the pool on Close Position / Forced Liquidation / delisting: clamped at the
     * cap, so leftover stale positions can never over-issue. [Formula example] cap 1000, 980 left,
     * close 50 shares -> min(980 + 50, 1000) = 1000.
     */
    public void shortPoolReturn(String code, int qty) {
        if (code == null || qty <= 0) {
            return;
        }
        int max = Math.max(0, shortPoolMax.getOrDefault(code, 0));
        if (max <= 0) {
            return;     // pool already dropped (stock delisted): nowhere to return to, ignore silently
        }
        int left = Math.max(0, shortPoolLeft.getOrDefault(code, 0));
        long sum = (long) left + (long) qty;
        shortPoolLeft.put(code, sum > (long) max ? max : (int) sum);
        setDirty();
    }

    /** Delisting: the whole pool of the stock is withdrawn (positions already force-closed, the pool lends no more shares). */
    public void dropShortPool(String code) {
        if (code != null && (shortPoolMax.remove(code) != null || shortPoolLeft.remove(code) != null)) {
            setDirty();
        }
    }

    /**
     * Day-roll refill (only the Trading Day close Settlement may call it; never inside a render
     * loop or a query method). [Business rule] the classic minimal recovery: every stock refills
     * 10% of **its own pool cap**, auto-clamped when the pool is full. [Formula example]
     * max=1000, left=0 -> 100 back this pass; max=105 -> floor(10.5)=10 shares/day.
     */
    public void refillAllShortPools() {
        if (shortPoolMax.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<String, Integer> e : new java.util.ArrayList<>(shortPoolMax.entrySet())) {
            String code = e.getKey();
            int max = Math.max(0, e.getValue());
            int left = Math.max(0, shortPoolLeft.getOrDefault(code, max));
            int next = cn.oremarket.common.ShortPoolCalculator
                    .refill(left, max);
            if (next != left) {
                shortPoolLeft.put(code, next);
                changed = true;
            }
        }
        if (changed) {
            setDirty();
        }
    }

    /**
     * Short-pool Snapshot (for S2C dispatch and the debugstatus command dump): code -> {max, left}.
     * A read-only walk: no data touched, no dirty flag.
     */
    public Map<String, int[]> shortPoolSnapshot() {
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : shortPoolMax.entrySet()) {
            int max = Math.max(0, e.getValue());
            int left = Math.min(Math.max(0, shortPoolLeft.getOrDefault(e.getKey(), max)), max);
            out.put(e.getKey(), new int[]{max, left});
        }
        return out;
    }

    /** Server-wide sum of player Ore Coin balances (for the debugstatus command; read-only). */
    public long totalPlayerCoins() {
        long sum = 0;
        for (long v : balances.values()) {
            if (v > 0) {
                sum = saturatingAdd(sum, v);
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------- locked resources (pending orders + Limit Orders)

    /**
     * Total locked Ore Coins: the money pinned by the player's open buy orders and buy Limit
     * Orders (price x quantity). The lock is just a logical marker - the coins still sit in the
     * account, derived here dynamically from the order data, so cancelling unlocks them at once.
     */
    public long lockedCoins(UUID id) {
        long sum = 0;
        for (PendingOrder o : pendingOrders) {
            if (o.buy && o.owner.equals(id)) {
                sum = saturatingAdd(sum, o.lockedCoins());
            }
        }
        // the Limit Order share is delegated to the pure static criterion (the offline verification harness runs the very same code)
        return saturatingAdd(sum,
                LimitOrder.lockedCoinsOf(limitOrders, id, LimitOrder.NO_EXCLUSION));
    }

    /** Total shares locked by orders against one stock (sell orders + sell Limit Orders). */
    public int lockedShares(UUID id, String code) {
        int sum = 0;
        if (code == null) {
            return 0;
        }
        for (PendingOrder o : pendingOrders) {
            if (!o.buy && o.owner.equals(id) && code.equals(o.code)) {
                sum = cappedIntAdd(sum, o.quantity);
            }
        }
        return cappedIntAdd(sum,
                LimitOrder.lockedSharesOf(limitOrders, id, code, LimitOrder.NO_EXCLUSION));
    }

    /**
     * A player's order summary, computed in one walk over both order tables (replacing the four
     * full scans packaging used to do): per-code locked shares, total locked Ore Coins, the
     * player's pending-order list and the player's Limit Order list. Each figure matches exactly
     * what {@link #lockedShares}/{@link #lockedCoins}/{@code ordersFor} would compute one by one.
     *
     * <p>Buy reservations are always the worst-case settlement charge at the current fee and tax
     * rates, not merely the nominal share value. This is the only way two resting orders owned by
     * one player cannot pledge the same Ore Coins independently.</p>
     */
    public static final class PlayerOrders {
        public final Map<String, Integer> lockedShares;
        public final long lockedCoins;
        public final List<PendingOrder> orders;
        public final List<LimitOrder> limitOrders;

        private PlayerOrders(Map<String, Integer> lockedShares, long lockedCoins,
                             List<PendingOrder> orders, List<LimitOrder> limitOrders) {
            this.lockedShares = lockedShares;
            this.lockedCoins = lockedCoins;
            this.orders = orders;
            this.limitOrders = limitOrders;
        }

        /** Shares locked against one stock (0 when nothing is locked). */
        public int lockedSharesOf(String code) {
            Integer v = code == null ? null : lockedShares.get(code);
            return v == null ? 0 : v;
        }
    }

    private static int cappedIntAdd(int left, int right) {
        if (right <= 0) {
            return left;
        }
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }


    /** Empty order summary (used when the player is null or both tables are empty; zero allocation). */
    private static final PlayerOrders NO_ORDERS =
            new PlayerOrders(Map.of(), 0L, List.of(), List.of());

    /** Builds this player's order summary in one pass; returns the shared empty summary straight away when both order tables are empty. */
    public PlayerOrders playerOrders(UUID id) {
        if (id == null || (pendingOrders.isEmpty() && limitOrders.isEmpty())) {
            return NO_ORDERS;
        }
        Map<String, Integer> locked = new HashMap<>();
        long coins = 0;
        List<PendingOrder> mine = new ArrayList<>();
        List<LimitOrder> mineLimits = new ArrayList<>();
        for (PendingOrder o : pendingOrders) {
            if (!o.owner.equals(id)) {
                continue;
            }
            if (o.buy) {
                coins = saturatingAdd(coins, o.lockedCoins());
            } else {
                mergeCapped(locked, o.code, o.quantity);
            }
            mine.add(o);
        }
        for (LimitOrder o : limitOrders) {
            if (!o.owner.equals(id)) {
                continue;
            }
            if (o.buy) {
                coins = saturatingAdd(coins, o.lockedCoins());
            } else {
                mergeCapped(locked, o.code, o.quantity);
            }
            mineLimits.add(o);
        }
        return new PlayerOrders(locked, coins, mine, mineLimits);
    }

    /** Ore Coins still tradable (not locked by any pending order or Limit Order). */
    public long availableCoins(UUID id) {
        long balance = balance(id);
        long locked = lockedCoins(id);
        return locked >= balance ? 0L : balance - locked;
    }

    /** Shares that are sellable and not locked. */
    public int availableUnlocked(UUID id, Stock stock) {
        if (stock == null) {
            return 0;
        }
        return Math.max(0, available(id, stock) - lockedShares(id, stock.code()));
    }

    /**
     * Limit Order matching kept using it, that amounts to demanding the player prepare the money
     * twice over - a fully funded buy Limit Order would forever read as insufficient funds and be
     * silently cancelled after {@code limitOrderFailRetries} failed attempts. Locks of the other
     * orders and Limit Orders are still deducted as before, so nothing is over-spent.
     */
    public long availableCoinsExceptOrder(UUID id, long limitOrderId) {
        long locked = 0L;
        for (PendingOrder o : pendingOrders) {
            if (o.buy && o.owner.equals(id) && o.id != limitOrderId) {
                locked = saturatingAdd(locked, o.lockedCoins());
            }
        }
        locked = saturatingAdd(locked, LimitOrder.lockedCoinsOf(limitOrders, id, limitOrderId));
        long balance = balance(id);
        return locked >= balance ? 0L : balance - locked;
    }

    /** Same as above, but the excluded lock is the sell Limit <b>order's own</b> reserved shares (otherwise a fully funded sell Limit Order would forever read as "nothing sellable"). */
    public int availableUnlockedExceptOrder(UUID id, Stock stock, long limitOrderId) {
        if (stock == null) {
            return 0;
        }
        int locked = 0;
        for (PendingOrder o : pendingOrders) {
            if (!o.buy && o.owner.equals(id) && o.id != limitOrderId
                    && stock.code().equals(o.code)) {
                locked = cappedIntAdd(locked, o.quantity);
            }
        }
        locked = cappedIntAdd(locked,
                LimitOrder.lockedSharesOf(limitOrders, id, stock.code(), limitOrderId));
        return Math.max(0, available(id, stock) - locked);
    }

    /** Number of pending orders the player has out (count of the player's own listings). */
    public int orderCount(UUID id) {
        int n = 0;
        for (PendingOrder o : pendingOrders) {
            if (o.owner.equals(id)) {
                n++;
            }
        }
        return n;
    }

    public int limitOrderCount(UUID id) {
        int n = 0;
        for (LimitOrder o : limitOrders) {
            if (o.owner.equals(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Cancel every pending order of the player (delisting / bankruptcy path); returns how many were
     * withdrawn. No silent removal: every cancellation reaches the owner through OrderNoticeService
     * (online: log line + toast, offline: persisted login catch-up).
     */
    public int cancelAllOrders(MinecraftServer server, UUID id) {
        int before = pendingOrders.size() + limitOrders.size();
        java.util.Map<UUID, Integer> onlineOrders = new HashMap<>();
        java.util.Map<UUID, Integer> onlineLimits = new HashMap<>();
        for (int i = pendingOrders.size() - 1; i >= 0; i--) {
            PendingOrder o = pendingOrders.get(i);
            if (o.owner.equals(id)) {
                LOG.info("Order removed: ID {}", o.id);
                pendingOrders.remove(i);
                if (o.quantity > 0 && noticeCancel(server, o.owner, false, "oremarket.log.order_forced_cancelled",
                        o.code, o.buy, o.quantity, o.price)) {
                    onlineOrders.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        for (int i = limitOrders.size() - 1; i >= 0; i--) {
            LimitOrder o = limitOrders.get(i);
            if (o.owner.equals(id)) {
                limitOrders.remove(i);
                if (o.quantity > 0 && noticeCancel(server, o.owner, true, "oremarket.log.limit_forced_cancelled",
                        o.code, o.buy, o.quantity, o.triggerPrice)) {
                    onlineLimits.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        int after = pendingOrders.size() + limitOrders.size();
        if (after != before || !onlineOrders.isEmpty() || !onlineLimits.isEmpty()) {
            setDirty();
        }
        onlineOrders.forEach((owner, n) -> OrderNoticeService.summary(server, owner, false, n));
        onlineLimits.forEach((owner, n) -> OrderNoticeService.summary(server, owner, true, n));
        return before - after;
    }

    /** Cancel every order on one stock (delisting cleanup); same no-silent-removal notice as cancelAllOrders. */
    public int cancelStockOrders(MinecraftServer server, String code) {
        int before = pendingOrders.size() + limitOrders.size();
        java.util.Map<UUID, Integer> onlineOrders = new HashMap<>();
        java.util.Map<UUID, Integer> onlineLimits = new HashMap<>();
        for (int i = pendingOrders.size() - 1; i >= 0; i--) {
            PendingOrder o = pendingOrders.get(i);
            if (o.code.equals(code)) {
                LOG.info("Order removed: ID {}", o.id);
                pendingOrders.remove(i);
                if (o.quantity > 0 && noticeCancel(server, o.owner, false, "oremarket.log.order_delisted",
                        o.code, o.buy, o.quantity, o.price)) {
                    onlineOrders.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        for (int i = limitOrders.size() - 1; i >= 0; i--) {
            LimitOrder o = limitOrders.get(i);
            if (o.code.equals(code)) {
                limitOrders.remove(i);
                if (o.quantity > 0 && noticeCancel(server, o.owner, true, "oremarket.log.limit_delisted",
                        o.code, o.buy, o.quantity, o.triggerPrice)) {
                    onlineLimits.merge(o.owner, 1, Integer::sum);
                }
            }
        }
        if (pendingOrders.size() + limitOrders.size() != before) {
            setDirty();
        }
        onlineOrders.forEach((owner, n) -> OrderNoticeService.summary(server, owner, false, n));
        onlineLimits.forEach((owner, n) -> OrderNoticeService.summary(server, owner, true, n));
        return before - (pendingOrders.size() + limitOrders.size());
    }

    /**
     * Player-facing notice for one system-cancelled order: true when the owner is online (a detail
     * log line was pushed and the caller may count it into the toast summary), false when offline
     * (the cancellation entered the persisted login catch-up counter).
     */
    private boolean noticeCancel(MinecraftServer server, UUID owner, boolean limit, String logKey,
                                 String code, boolean buy, int quantity, double price) {
        Stock stock = StockRegistry.server().getByCode(code);
        String name = stock != null ? stock.displayName() : code;
        return OrderNoticeService.record(server, this, owner, limit, logKey,
                name, buy ? "oremarket.text.0019" : "oremarket.text.0032",
                quantity, String.format(java.util.Locale.ROOT, "%.2f", price));
    }

    public long nextOrderSequence() {
        return ++orderSequence;
    }

    /** Records / refreshes a player name (for the Leaderboard and offline display in order lists). */
    public void rememberName(UUID id, String name) {
        if (id != null && name != null && !name.isEmpty() && !name.equals(playerNames.get(id))) {
            playerNames.put(id, name);
            setDirty();
        }
    }

    public String nameOf(UUID id) {
        return playerNames.getOrDefault(id, id.toString().substring(0, 8));
    }

    // ---------------------------------------------------------------- Trading Day

    /** Current Trading Day number (1-based). Every "day" rule in the code uses this. */
    public long tradeDay() {
        return tradeDay;
    }

    // ---------------------------------------------------------------- loans

    /** The player's loan record; creates an all-zero record on the fly when absent (without persisting). */
    public LoanData loan(UUID id) {
        return loans.computeIfAbsent(id, k -> new LoanData());
    }

    public LoanData loanOrNull(UUID id) {
        return loans.get(id);
    }

    // ---------------------------------------------------------------- cost basis and P&L
    //
    // Rule from: averageCost may only ever be written in three situations -
    //   buy   : after a buy fill, written as the weighted average (every buy-fill entry: TradeService,
    //           LimitOrderService and OrderBookService.settle all take this path)
    //   clear : reset when holdings reach zero (sold out / bankruptcy / Forced Liquidation)
    //   reAdd : when a custom stock is re-added under the same code, reset to the new base price (ListingService / StockRegistry)
    // Everywhere else (price engine, Events, login, UI, hot reload, migration, Leaderboard) it is strictly read-only; writes are forbidden.

    /** The recorded average cost; 0 when there is no record (the cost basis may never be inferred from the current price). */
    public double costOf(UUID id, Stock stock) {
        Map<String, Double> map = stock == null ? null : costBasis.get(id);
        if (map == null) {
            return 0;
        }
        Double v = map.get(stock.code());
        return v == null || v <= 0 ? 0 : v;
    }

    /** Old call basis preserved: returns -1 when no record exists and lets the caller fall back (read-only, writes nothing). */
    public double costOr(UUID id, Stock stock, double fallback) {
        double c = costOf(id, stock);
        return c <= 0 ? fallback : c;
    }

    /** Writes the average cost (the single entry point, logged). reason is only ever buy / reAdd. */
    public void setCost(UUID id, Stock stock, double avg, String reason) {
        if (stock == null) {
            return;
        }
        double old = costOf(id, stock);
        costBasis.computeIfAbsent(id, k -> new HashMap<>()).put(stock.code(), avg);
        setDirty();
        LOG.info("setCost: player {} stock {} old {} new {} reason {}",
                id, stock.code(), String.format(Locale.ROOT, "%.4f", old), String.format(Locale.ROOT, "%.4f", avg), reason);
    }

    /** Holdings zeroed: cost basis resets to 0 (deleting the record = reads treat it as 0; the single entry point, logged). */
    public void clearPositionCost(UUID id, Stock stock, String reason) {
        if (stock == null) {
            return;
        }
        Map<String, Double> map = costBasis.get(id);
        if (map != null) {
            Double old = map.get(stock.code());
            map.remove(stock.code());
            if (map.isEmpty()) {
                costBasis.remove(id);
            }
            setDirty();
            LOG.info("setCost: player {} stock {} old {} new 0 reason {}",
                    id, stock.code(), String.format(Locale.ROOT, "%.4f", old == null ? 0 : old), reason);
        }
    }

    /** Custom stock re-added under the same code: every player's cost basis for that code resets to the new base price (reason reAdd). */
    public void resetCostsForReAddedCode(String code, double newBasePrice) {
        if (code == null || newBasePrice <= 0) {
            return;
        }
        for (UUID id : new HashSet<>(costBasis.keySet())) {
            Map<String, Double> map = costBasis.get(id);
            if (map != null && map.containsKey(code)) {
                double old = map.get(code);
                map.put(code, newBasePrice);
                LOG.info("setCost: player {} stock {} old {} new {} reason reAdd",
                        id, code, String.format(Locale.ROOT, "%.4f", old), String.format(Locale.ROOT, "%.4f", newBasePrice));
            }
        }
        setDirty();
    }

    /** Realized P&L of one stock; 0 when there is no record. */
    public double realizedOf(UUID id, Stock stock) {
        Map<String, Double> map = stock == null ? null : realizedPnl.get(id);
        return map == null ? 0 : map.getOrDefault(stock.code(), 0.0);
    }

    /** Sum of realized P&L across all of the player's stocks (the realized part of the top-bar total P&L). */
    public double realizedTotal(UUID id) {
        Map<String, Double> map = realizedPnl.get(id);
        if (map == null) {
            return 0;
        }
        double sum = 0;
        for (double v : map.values()) {
            sum += v;
        }
        return sum;
    }

    /** Adds to the realized P&L of one stock when selling (averageCost stays put - only buys and clearings may touch it). */
    public void addRealized(UUID id, Stock stock, double delta) {
        if (delta == 0 || stock == null) {
            return;
        }
        realizedPnl.computeIfAbsent(id, k -> new HashMap<>())
                .merge(stock.code(), delta, Double::sum);
        setDirty();
    }

    /** Sum of realized short P&L for the player's short history. */
    public double realizedShortPnlTotal(UUID id) {
        return ShortPnlCalculator.realizedTotal(realizedShortPnl.get(id));
    }

    /** Closed short price P&L for one stock; no record reads zero. */
    public double realizedShortPnlOf(UUID id, String code) {
        Map<String, Double> map = realizedShortPnl.get(id);
        Double v = map == null || code == null ? null : map.get(code);
        return v == null || !Double.isFinite(v) ? 0.0 : v;
    }

    /** Adds one settled or force-liquidated short tranche's economic P&L without re-applying its cash settlement. */
    public void addRealizedShortPnl(UUID id, String code, double delta) {
        if (id == null || !Double.isFinite(delta) || delta == 0.0) {
            return;
        }
        Map<String, Double> map = realizedShortPnl.computeIfAbsent(id, k -> new HashMap<>());
        int before = map.size();
        double beforeSum = ShortPnlCalculator.realizedTotal(map);
        ShortPnlCalculator.add(map, code, delta);
        if (map.size() != before || ShortPnlCalculator.realizedTotal(map) != beforeSum) {
            if (map.isEmpty()) {
                realizedShortPnl.remove(id);
            }
            setDirty();
        }
    }

    /**
     * The single top-bar short contribution: settled price P&L + remaining open floating P&L.
     * Interest is excluded because it is already reflected in the refundable short margin pool.
     */
    public double shortTotalPnl(UUID id) {
        return realizedShortPnlTotal(id) + shortFloatingPnl(id);
    }

    // ---------------------------------------------------------------- total assets and daily baseline
    //
    // Rule from: exactly one total-assets formula exists in the whole project = calculateTotalAssets;
    // formula: Ore Coin balance + market value of ordinary holdings + short Margin + short floating P&L - unpaid loan principal and interest.
    // dailyBaseline lives in the player data (by UUID, at the same level as the Ore Coin balance) and is written only for three reasons:
    // tradeDayStart (Trading Day rollover) / firstLogin (first entry) / offlineLogin (retroactive offline record).

    /** Total-assets computation log counter: details are printed for the first 10 calls only. */
    private int totalAssetsLogCalls;
    /** Log-write cap: the first 10 calls only. */
    private static final int TOTAL_ASSETS_LOG_LIMIT = 10;

    /** Market value of ordinary holdings = sum over positions (shares x current price); sellable and frozen alike count at the current price. */
    public double positionMarketValue(UUID id) {
        double value = 0;
        for (Map.Entry<String, Integer> e : holdingsOf(id).entrySet()) {
            StockState st = state(e.getKey());
            value += e.getValue() * (st == null ? 0 : st.price);
        }
        for (Map.Entry<String, Integer> e : frozenOf(id).entrySet()) {
            StockState st = state(e.getKey());
            value += e.getValue() * (st == null ? 0 : st.price);
        }
        return value;
    }

    /**
     * The unified total-assets function (Ore Coins + position market value + short Margin + short
     * floating P&L - loan principal and interest). The Leaderboard, the top-bar display and the
     * dailyBaseline writes all call this single function only.
     */
    public long calculateTotalAssets(UUID id) {
        double coins = balance(id);
        double mv = positionMarketValue(id);
        double margin = shortMarginTotal(id);
        double shortFloat = shortFloatingPnl(id);
        // Loan liability is read straight from the record (hasLoan ? Payoff: 0, the same basis as LoanInfo.loanPayoff).
        // The old implementation detoured through LoanInfo.of(), but the credit-limit algorithm now needs total assets too - keeping one
        // LoanInfo dependency keeps a recursive chain LoanInfo -> creditLimit -> dynamicLimit -> total assets, and it has to be cut.
        LoanData loanRec = loanOrNull(id);
        long loan = loanRec != null && loanRec.hasLoan() ? loanRec.payoff() : 0L;
        // The old rule deducted only outstanding loan principal and interest, while the client-side "total debt" (MarketClientState.totalDebt = loanPayoff +
        // clawbackDebt + shortDebt) counted all three - one name "net assets" with two definitions on the two sides:
        //  1) the credit-limit asset bonus handed out money against inflated net assets (carrying an 8000 liquidation shortfall you could still borrow 20% x the inflation more);
        //  2) the emergency-loan gate (may borrow only while total assets <= 1000) in turn shut out the very people who need a way back;
        //  3) the Leaderboard and the daily gain reported inflated numbers along with it.
        // Bankruptcy arrears / liquidation shortfall carry no note and no due day, but they are hard liabilities that must be cleared first (the borrow() gate
        // is written on exactly these two); not deducting them means "the more you owe, the better your credit".
        long hangover = loanRec == null ? 0L
                : Math.max(0L, loanRec.clawbackDebt) + loanRec.shortDebtDue();
        double value = coins + mv + margin + shortFloat - loan - hangover;
        if (totalAssetsLogCalls < TOTAL_ASSETS_LOG_LIMIT) {
            totalAssetsLogCalls++;
            LOG.info("totalAssets: player {} coins {} holdings {} shortMargin {} shortPnl {} loan {} "
                            + "hangover {} result {}",
                    id, coins, String.format(Locale.ROOT, "%.2f", mv), margin, String.format(Locale.ROOT, "%.2f", shortFloat),
                    loan, hangover, Math.round(value));
        }
        return Math.round(value);
    }

    /** Player-facing entry point: login backfill, Trading Day rollover, the Snapshot and the Leaderboard all come through here (one formula; per-caller variants are forbidden). */
    public static long calculateTotalAssets(MarketSavedData data, net.minecraft.server.level.ServerPlayer player) {
        return data.calculateTotalAssets(player.getUUID());
    }

    /** Weighted-average cost of one stock across all holdings (for the price-update log; read-only, it never writes the cost basis). */
    public double stockWeightedCost(String code) {
        double shares = 0;
        double sum = 0;
        for (Map<UUID, Map<String, Integer>> outer : List.of(holdings, frozen)) {
            for (Map.Entry<UUID, Map<String, Integer>> e : outer.entrySet()) {
                Integer q = e.getValue().get(code);
                if (q == null || q <= 0) {
                    continue;
                }
                Map<String, Double> cm = costBasis.get(e.getKey());
                double c = cm == null ? 0 : cm.getOrDefault(code, 0.0);
                shares += q;
                sum += q * c;
            }
        }
        return shares <= 0 ? 0 : sum / shares;
    }

    /** Whether this player has already been granted the starter Ore Coins (the world save is the sole authority). */
    public boolean isStarterGranted(UUID id) {
        return id != null && starterGrantedPlayers.contains(id);
    }

    /** Mark the player's starter Ore Coins as granted and flush the mark into the world save right away. */
    public void markStarterGranted(UUID id) {
        if (id != null && starterGrantedPlayers.add(id)) {
            setDirty();
        }
    }

    /** Writes the daily baseline (the single entry point): the value is exactly what calculateTotalAssets returned. */
    public void writeDailyBaseline(UUID id, String reason) {
        long v = calculateTotalAssets(id);
        dailyBaseline.put(id, v);
        setDirty();
        // Part three: once the Trading Day was configured below 1200 ticks, this INFO line flooded latest.log every few seconds
        // (proven in latest.log). The data is still written; the log goes silent under such over-short Trading Days.
        if (!MarketSettings.quietDailyLogs(MarketSettings.server())) {
            LOG.info("setDailyBaseline: player {} value {} reason {}", id, v, reason);
        }
    }

    /** Login backfill: never overwrites an existing field (no repeated backfill per login; it fills exactly once when missing). */
    public void initDailyBaselineIfMissing(UUID id, String reason) {
        if (!dailyBaseline.containsKey(id)) {
            writeDailyBaseline(id, reason);
        }
    }

    // ---------------------------------------------------------------- Event helpers

    /** Allocate one Event instance sequence (numbered centrally by the server, so every player sees the same Event under the same sequence). */
    public int nextEventUid() {
        return ++eventSeq;
    }

    /** Record the marketTick at which one stock was halted this time, feeding the "two trading halts must be 200 marketTicks apart" check. */
    public void markSuspended(String code, long tick) {
        suspendCooldown.put(code, tick);
        setDirty();
    }

    /** The marketTick of the last trading halt; -1 if never halted. */
    public long lastSuspendTick(String code) {
        Long v = suspendCooldown.get(code);
        return v == null ? -1L : v;
    }

    // ---------------------------------------------------------------- price clamping

    /**
     * Effective quote anchor for the given configured base price: basePrice x the saved scale (default 1.0).
     * ONE convention for every anchor-sensitive calculation (reversion target, floor/ceiling band,
     * delisting threshold, liquidation guards, event settlement, client 'base' column): splits and dividends
     * re-scale it, listing / relisting reset it. Never recompute the ratio ad hoc at a call site.
     */
    public double anchorBase(String code, double configuredBase) {
        if (!(configuredBase > 0.0) || !Double.isFinite(configuredBase) || code == null) {
            return configuredBase;
        }
        Double f = quoteAnchorScales.get(code);
        if (f == null || !Double.isFinite(f) || f <= 0.0) {
            return configuredBase;
        }
        return configuredBase * f;
    }

    /** Anchor of a live registry stock (its basePrice is already config-resolved). */
    public double anchorBase(Stock stock) {
        return stock == null ? 0.0 : anchorBase(stock.code(), stock.basePrice());
    }

    /**
     * Re-scales one stock's anchor (split: x 1/ratio; dividend ex-rights: x (anchor - perShare) / anchor).
     * A dirty or extreme factor is dropped instead of poisoning the band (the anchor can never leave
     * (0, base x 1e6] or fall under base x 1e-6 through this entry).
     */
    public void scaleAnchor(String code, double factor) {
        if (code == null || code.isEmpty() || !(factor > 0.0) || !Double.isFinite(factor)) {
            return;
        }
        double current = anchorScaleOf(code);
        double next = current * factor;
        if (!Double.isFinite(next) || next > 1.0e6 || next < 1.0e-6) {
            LOG.warn("Anchor scale rejected (out of sane range): stock {} factor {} -> {}",
                    code, String.format(java.util.Locale.ROOT, "%.6f", factor),
                    String.format(java.util.Locale.ROOT, "%.6f", next));
            return;
        }
        quoteAnchorScales.put(code, next);
        setDirty();
    }

    /** Listing / relisting: the new round starts from the configured base price, so drop any historical scale. */
    public void resetAnchor(String code) {
        if (code != null && quoteAnchorScales.remove(code) != null) {
            setDirty();
        }
    }

    private double anchorScaleOf(String code) {
        Double f = quoteAnchorScales.get(code);
        return f == null || !Double.isFinite(f) || f <= 0.0 ? 1.0 : f;
    }

    /** Long-run band clamp: floor%~ceiling% of the ANCHOR price (config category four; one shared set of parameters for all stocks). */
    public double clampPrice(Stock stock, double price) {
        MarketSettings ms = MarketSettings.current();
        double base = anchorBase(stock);
        return Math.max(ms.priceFloor(base), Math.min(ms.priceCeiling(base), price));
    }

    /** Up/down limits: one tick may move at most +/- the configured magnitude versus the previous tick. */
    public static double applyPriceLimit(double price, double prevTickPrice) {
        return applyPriceLimit(price, prevTickPrice, MarketSettings.current().priceLimitFraction());
    }

    /** Up/down limits: one tick may move at most +/- limit versus the previous tick (an Event may override it). */
    public static double applyPriceLimit(double price, double prevTickPrice, double limit) {
        if (prevTickPrice <= 0) {
            return price;
        }
        MarketSettings ms = MarketSettings.current();
        // The amplitude band comes from config category nine (an Event may override it); out-of-range values clamp to
        // the configured event bounds. Keep a tiny positive fallback for malformed legacy snapshots.
        double minLimit = Math.max(0.001, ms.eventPriceLimitMin);
        double maxLimit = Math.max(minLimit, Math.max(0.001, ms.eventPriceLimitMax));
        double l = Math.max(minLimit, Math.min(maxLimit, limit));
        return Math.max(prevTickPrice * (1.0 - l), Math.min(prevTickPrice * (1.0 + l), price));
    }

    /** Per-tick price-multiplier cap for Events: +/-15%, with dirty values neutralized before settlement. */
    public static double clampEventMultiplier(double mult) {
        if (!Double.isFinite(mult)) {
            return 1.0;
        }
        return Math.max(0.85, Math.min(1.15, mult));
    }

    // ------------------------------------------------------------------ NBT

    public static MarketSavedData load(CompoundTag tag) {
        MarketSavedData data = new MarketSavedData();
        // first clear the default state seeded by the no-arg constructor: the real data is all restored from the save (which may carry custom stocks)
        data.states.clear();
        int dv = tag.contains("DataVersion") ? tag.getInt("DataVersion") : 0;
        data.dataVersion = dv;
        final boolean migrate = dv < DATA_VERSION;

        data.tickCounter = tag.getLong("TickCounter");
        data.marketTicks = tag.getLong("MarketTicks");
        data.klinePeriodTick = Math.max(0, Math.min(MarketSettings.server().klineTicksPerBar - 1, tag.getInt("KlinePeriodTick")));

        Tag stocksRaw = tag.get("Stocks");
        if (stocksRaw instanceof CompoundTag stocks) {
            for (String rawKey : stocks.getAllKeys()) {
                String code = resolveLegacyStockKey(rawKey);
                if (code == null) {
                    continue;
                }
                CompoundTag sc = stocks.getCompound(rawKey);
                StockState st = restoreState(code, sc);
                data.states.put(code, st);
                if (migrate && !isBuiltinCode(code)) {
                    // the old format has no custom stocks; any unknown code is kept as data but flagged under "unknown means retired",
                    // and codes that are still in the config after rebuild() get the flag lifted
                    data.retiredCodes.add(code);
                }
            }
        }
        // stocks the registry has but the save does not (newly installed built-ins etc.): seed their default state
        data.ensureStatesPresent();

        // -------- Events: legacy-format detection (IntArrayTag / ByteArrayTag)
        Tag eventsRaw = tag.get("Events");
        if (eventsRaw != null && !(eventsRaw instanceof ListTag)) {
            boolean nonEmpty = (eventsRaw instanceof net.minecraft.nbt.IntArrayTag ia && ia.getAsString().length() > 0)
                    || (eventsRaw instanceof net.minecraft.nbt.ByteArrayTag ba && !ba.getAsString().isEmpty());
            if (nonEmpty) {
                LOG.warn("[migration] activeEvents in legacy bitmask format cleared (cannot map to stock codes)");
                data.migrationLogged = false;
            }
        } else if (eventsRaw instanceof ListTag events) {
            if (migrate && !events.isEmpty()) {
                data.migrationLogged = false;   // migration log: Events saved again by code string
            }
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i) instanceof CompoundTag ec) {
                    ActiveEvent ae = ec.contains("Tpl") ? ActiveEvent.load(ec, migrate)
                            : ActiveEvent.loadLegacy(ec);
                    if (ae != null) {
                        data.events.add(ae);
                    }
                }
            }
        }
        data.lastLegendaryTick = tag.contains("LastLegendary") ? tag.getLong("LastLegendary") : Long.MIN_VALUE / 4;
        data.eventSeq = tag.getInt("EventSeq");
        data.eventTickInterval = tag.contains("EventTickInterval")
                ? Math.max(1, tag.getInt("EventTickInterval"))
                : 0;

        ListTag sc = tag.getList("SuspendCooldown", Tag.TAG_COMPOUND);
        for (int i = 0; i < sc.size(); i++) {
            CompoundTag e = sc.getCompound(i);
            String code = resolveLegacyStockKey(e.getString("Stock"));
            if (code != null) {
                data.suspendCooldown.put(code, e.getLong("Tick"));
            }
        }

        ListTag bal = tag.getList("Balances", Tag.TAG_COMPOUND);
        for (int i = 0; i < bal.size(); i++) {
            CompoundTag bc = bal.getCompound(i);
            String uuid = bc.getString("Id");
            if (!uuid.isEmpty()) {
                try {
                    UUID id = UUID.fromString(uuid);
                    long amount = Math.max(0L, bc.getLong("Amount"));
                    if (amount > 0L) {
                        data.balances.put(id, amount);
                    }
                    // old saves carry no peak records: fill them in from the current balance so the only-rises-never-falls rule cannot regress
                    long peakNow = Math.max(amount, data.peakBalances.getOrDefault(id, 0L));
                    data.peakBalances.put(id, peakNow);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ListTag pk = tag.getList("PeakBalances", Tag.TAG_COMPOUND);
        for (int i = 0; i < pk.size(); i++) {
            CompoundTag pc = pk.getCompound(i);
            String uuid = pc.getString("Id");
            if (!uuid.isEmpty()) {
                try {
                    UUID id = UUID.fromString(uuid);
                    long peak = pc.getLong("Amount");
                    data.peakBalances.merge(id, Math.max(peak, data.balance(id)), Math::max);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ListTag hl = tag.getList("HighestLevel", Tag.TAG_COMPOUND);
        for (int i = 0; i < hl.size(); i++) {
            CompoundTag hc = hl.getCompound(i);
            String uuid = hc.getString("Id");
            if (!uuid.isEmpty()) {
                try {
                    data.highestLevels.put(UUID.fromString(uuid), Math.max(1, hc.getLong("Level")));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // note: the missing highestLevel is **not backfilled** here. The backfill moved to
        // the highestLevel recalculation stage on ServerTickEvent.END (LevelService.recalcOnLogin),
        // because recalculation must use the "currently effective config thresholds" and the config snapshot may not be ready at load time.
        data.dayProgress = tag.getLong("DayProgress");

        data.holdings.putAll(readPositionMap(tag.getList("Holdings", Tag.TAG_COMPOUND), data,
                data.costBasis, migrate));
        data.frozen.putAll(readPositionMap(tag.getList("Frozen", Tag.TAG_COMPOUND), data,
                null, migrate));

        ListTag rp = tag.getList("RealizedPnl", Tag.TAG_COMPOUND);
        for (int i = 0; i < rp.size(); i++) {
            CompoundTag rc = rp.getCompound(i);
            String uuid = rc.getString("Id");
            if (uuid.isEmpty()) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(uuid);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ListTag entries = rc.getList("Entries", Tag.TAG_COMPOUND);
            if (entries.isEmpty() && rc.contains("Amount")) {
                // legacy save: one flat total per player, never split per stock - the whole amount is booked under coal;
                // the totals rule is unchanged (the top-bar total P&L behaves as before), only the details panel gives coal this extra history.
                data.realizedPnl.computeIfAbsent(id, k -> new HashMap<>())
                        .put("COAL", rc.getDouble("Amount"));
                continue;
            }
            for (int j = 0; j < entries.size(); j++) {
                CompoundTag en = entries.getCompound(j);
                String code = resolveLegacyStockKey(en.getString("Stock"));
                if (code != null) {
                    data.realizedPnl.computeIfAbsent(id, k -> new HashMap<>())
                            .merge(code, en.getDouble("Amount"), Double::sum);
                }
            }
        }

        readPlayerStockDoubleMap(tag.getList("RealizedShortPnl", Tag.TAG_COMPOUND), data.realizedShortPnl);
        data.tradeDay = tag.contains("TradeDay") ? Math.max(1, tag.getLong("TradeDay")) : 1;
        data.lastLegendaryDay = tag.contains("LastLegendaryDay")
                ? tag.getLong("LastLegendaryDay") : Long.MIN_VALUE / 4;
        data.lastDividendDay = tag.contains("LastDividendDay")
                ? tag.getLong("LastDividendDay") : Long.MIN_VALUE / 4;
        data.comboFiredKeys.clear();
        if (tag.get("ComboFiredKeys") instanceof ListTag cfk) {
            for (int i = 0; i < cfk.size() && i < 512; i++) {
                data.comboFiredKeys.add(cfk.getString(i));
            }
        }
        ListTag loans = tag.getList("Loans", Tag.TAG_COMPOUND);
        for (int i = 0; i < loans.size(); i++) {
            CompoundTag lc = loans.getCompound(i);
            String uuid = lc.getString("Id");
            if (!uuid.isEmpty()) {
                try {
                    data.loans.put(UUID.fromString(uuid), LoanData.fromTag(lc));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        // ---------- v2 - P2P: player-to-player loan listings (old saves without this list = zero listings)
        ListTag p2pList = tag.getList("P2PListings", Tag.TAG_COMPOUND);
        for (int i = 0; i < p2pList.size(); i++) {
            CompoundTag pc = p2pList.getCompound(i);
            P2PListing pl = P2PListing.fromTag(pc);
            if (pl.lender == null || pl.coins <= 0) {
                continue;            // dirty records are dropped outright (id and escrow totals are recomputed below from surviving records)
            }
            data.p2pListings.put(pl.id, pl);
            if (!pl.taken) {
                data.p2pEscrow += pl.coins;
            }
        }
        data.p2pNextId = Math.max(1, tag.getLong("P2PNextId"));
        for (P2PListing pl : data.p2pListings.values()) {
            if (pl.id >= data.p2pNextId) {
                data.p2pNextId = pl.id + 1;
            }
        }

        // ---------- added: shorts / pending orders / Limit Orders / dynamic stocks / daily baseline / player names
        data.nextShortId = Math.max(1, tag.getLong("NextShortId"));
        ListTag shortList = tag.getList("Shorts", Tag.TAG_COMPOUND);
        for (int i = 0; i < shortList.size(); i++) {
            CompoundTag sc2 = shortList.getCompound(i);
            String uuid = sc2.getString("Owner");
            if (uuid.isEmpty()) {
                continue;
            }
            try {
                UUID id = UUID.fromString(uuid);
                ShortPosition p = ShortPosition.fromTag(sc2, id);
                if (p.quantity > 0) {
                    // Old saves missing Id -> mint a unique ID at load; when an Id exists, advance the generator so ids never collide globally
                    data.allocShortId(p);
                    data.shorts.computeIfAbsent(id, k -> new HashMap<>()).put(p.code, p);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag shortsCdList = tag.getList("ShortsCooldowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < shortsCdList.size(); i++) {
            CompoundTag cc = shortsCdList.getCompound(i);
            try {
                data.shortsCooldownEnd.put(UUID.fromString(cc.getString("Id")), cc.getLong("End"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        // ---------- old saves lack the ShortPools key -> start from empty tables,
        // and the rebuild loop of StockRegistry.rebuildAndApply fills them at once from the formula (NPE guard) ----------
        ListTag poolList = tag.getList("ShortPools", Tag.TAG_COMPOUND);
        for (int i = 0; i < poolList.size(); i++) {
            CompoundTag pc = poolList.getCompound(i);
            String code = pc.getString("Code");
            if (code.isEmpty()) {
                continue;
            }
            int max = Math.max(0, pc.getInt("Max"));
            int left = Math.min(Math.max(0, pc.getInt("Left")), max);
            data.shortPoolMax.put(code, max);
            data.shortPoolLeft.put(code, left);
        }
        data.loanReserve = tag.getLong("LoanReserve");
        ListTag orderList = tag.getList("PendingOrders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orderList.size(); i++) {
            PendingOrder o = PendingOrder.fromTag(orderList.getCompound(i));
            if (o.quantity > 0) {
                data.pendingOrders.add(o);
            }
        }
        ListTag limitList = tag.getList("LimitOrders", Tag.TAG_COMPOUND);
        for (int i = 0; i < limitList.size(); i++) {
            LimitOrder o = LimitOrder.fromTag(limitList.getCompound(i));
            if (o.quantity > 0) {
                data.limitOrders.add(o);
            }
        }
        ListTag dynList = tag.getList("DynamicStocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < dynList.size(); i++) {
            DynamicStockDef def = DynamicStockDef.fromTag(dynList.getCompound(i));
            if (!def.code.isEmpty()) {
                data.dynamicStocks.put(def.code, def);
            }
        }
        ListTag delistList = tag.getList("DelistCounters", Tag.TAG_COMPOUND);
        for (int i = 0; i < delistList.size(); i++) {
            CompoundTag dc = delistList.getCompound(i);
            data.delistCounters.put(dc.getString("Code"), dc.getInt("Days"));
        }
        // Quote anchor scales (split / dividend re-basing). Missing tag = untouched saves keep anchor 1.0.
        ListTag anchorList = tag.getList("QuoteAnchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < anchorList.size(); i++) {
            CompoundTag ac = anchorList.getCompound(i);
            String code = ac.getString("Code");
            double scale = ac.getDouble("Scale");
            if (!code.isEmpty() && Double.isFinite(scale) && scale > 0.0) {
                data.quoteAnchorScales.put(code, scale);
            }
        }
        ListTag builtinDelist = tag.getList("BuiltinDelistedDays", Tag.TAG_COMPOUND);
        for (int i = 0; i < builtinDelist.size(); i++) {
            CompoundTag bc = builtinDelist.getCompound(i);
            String code = bc.getString("Code");
            // only the 8 built-in codes are accepted: any other key is dirty data and dropped (this table serves built-in delisting records only)
            if (!code.isEmpty() && Stock.BUILTIN_CODES.contains(code)) {
                data.builtinDelistedDays.put(code, Math.max(0L, bc.getLong("Day")));
            }
        }
        ListTag baseList = tag.getList("DailyBaseline", Tag.TAG_COMPOUND);
        for (int i = 0; i < baseList.size(); i++) {
            CompoundTag bc = baseList.getCompound(i);
            try {
                data.dailyBaseline.put(UUID.fromString(bc.getString("Id")), bc.getLong("Assets"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag nameList = tag.getList("PlayerNames", Tag.TAG_COMPOUND);
        for (int i = 0; i < nameList.size(); i++) {
            CompoundTag nc = nameList.getCompound(i);
            try {
                data.playerNames.put(UUID.fromString(nc.getString("Id")), nc.getString("Name"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag starterList = tag.getList("StarterGrantedPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < starterList.size(); i++) {
            CompoundTag starterTag = starterList.getCompound(i);
            try {
                data.starterGrantedPlayers.add(UUID.fromString(starterTag.getString("Id")));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag cancelNoticeList = tag.getList("OrderCancelNotice", Tag.TAG_COMPOUND);
        for (int i = 0; i < cancelNoticeList.size(); i++) {
            CompoundTag noticeTag = cancelNoticeList.getCompound(i);
            try {
                int[] n = new int[]{Math.max(0, noticeTag.getInt("Orders")), Math.max(0, noticeTag.getInt("Limits"))};
                if (n[0] > 0 || n[1] > 0) {
                    data.orderCancelNotice.put(UUID.fromString(noticeTag.getString("Id")), n);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        ListTag offlineNoticeList = tag.getList("OfflineNotices", Tag.TAG_COMPOUND);
        for (int i = 0; i < offlineNoticeList.size(); i++) {
            CompoundTag ownerTag = offlineNoticeList.getCompound(i);
            try {
                UUID id = UUID.fromString(ownerTag.getString("Id"));
                ListTag entries = ownerTag.getList("Entries", Tag.TAG_COMPOUND);
                List<OfflineNotice> queue = new ArrayList<>();
                for (int j = 0; j < entries.size() && queue.size() < MAX_OFFLINE_NOTICES; j++) {
                    CompoundTag entry = entries.getCompound(j);
                    String key = entry.getString("Key");
                    if (key.isEmpty()) {
                        continue;
                    }
                    ListTag args = entry.getList("Args", Tag.TAG_STRING);
                    List<String> values = new ArrayList<>();
                    for (int k = 0; k < args.size(); k++) {
                        values.add(args.getString(k));
                    }
                    queue.add(new OfflineNotice(key, values));
                }
                if (!queue.isEmpty()) {
                    data.offlineNotices.put(id, queue);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        data.orderSequence = tag.getLong("OrderSequence");
        data.lastOrderMatchMarketTick = tag.getLong("LastOrderMatchTick");

        if (migrate) {
            // migration finished: write the new version back at once and flag dirty (the next save persists the new format)
            data.dataVersion = DATA_VERSION;
            data.setDirty();
            LOG.info("[migration] save data version {} -> {}: stock data re-keyed by code (legacy 0..7 -> {})",
                    dv, DATA_VERSION, Stock.BUILTIN_CODES);
        }
        return data;
    }

    /** Whether a stock key found in the save is one of the built-in 8. */
    private static boolean isBuiltinCode(String code) {
        return Stock.BUILTIN_CODES.contains(code);
    }

    /** Restores one stock's market state from a CompoundTag (unknown codes are restored as well so the data is never lost). */
    private static StockState restoreState(String code, CompoundTag sc) {
        Stock stock = StockRegistry.server().getByCode(code);
        if (stock == null) {
            // the registry does not have this stock yet (custom stocks appear only after rebuild): seed a placeholder definition from the saved price,
            // taking the base price from the saved price so the state object is usable
            stock = Stock.builtinResolved(code, "", code, sc.getDouble("Price"), 1, 1);
        }
        StockState st = StockState.empty(stock);
        st.price = sc.getDouble("Price");
        st.trend = sc.getInt("Trend");
        st.trendTicksLeft = sc.getInt("TrendTicks");
        ListTag hist = sc.getList("History", Tag.TAG_DOUBLE);
        double[] arr = new double[Math.min(StockState.HISTORY_LEN, hist.size())];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = hist.getDouble(i);
        }
        st.loadHistory(arr, arr.length);
        // candlesticks: up to 80 completed bars plus the current unfinished one, all restored with the save,
        // so the chart is not blank after a server restart and players see the same history after logging out and back in
        ListTag kl = sc.getList("Klines", Tag.TAG_COMPOUND);
        double[][] bars = new double[Math.min(StockState.KLINE_LEN, kl.size())][StockState.KLINE_FIELDS];
        for (int i = 0; i < bars.length; i++) {
            CompoundTag kc = kl.getCompound(i);
            bars[i][0] = kc.getDouble("Open");
            bars[i][1] = kc.getDouble("High");
            bars[i][2] = kc.getDouble("Low");
            bars[i][3] = kc.getDouble("Close");
            bars[i][4] = kc.getDouble("Volume");
            bars[i][5] = kc.contains("PlayerVolume") ? kc.getDouble("PlayerVolume") : 0.0;
            // Missing Volume / PlayerVolume is the old four-field or pre-player-volume format and intentionally becomes zero.
            // Any negative or non-finite value is sanitized by StockState.loadKlines.
        }
        CompoundTag curK = sc.getCompound("CurKline");
        double[] live = new double[StockState.KLINE_FIELDS];
        live[0] = curK.getDouble("Open");
        live[1] = curK.getDouble("High");
        live[2] = curK.getDouble("Low");
        live[3] = curK.getDouble("Close");
        live[4] = curK.getDouble("Volume");
        live[5] = curK.contains("PlayerVolume") ? curK.getDouble("PlayerVolume") : 0.0;
        // Old CurKline compounds do not have Volume / PlayerVolume; missing values become 0 and remain compatible.
        st.loadKlines(bars, bars.length, live, sc.getInt("LiveKlineFilled"), sc.getLong("KlinePushes"));
        ListTag dailyKl = sc.getList("DailyKlines", Tag.TAG_COMPOUND);
        double[][] dailyBars = new double[Math.min(StockState.KLINE_LEN, dailyKl.size())][StockState.KLINE_FIELDS];
        for (int i = 0; i < dailyBars.length; i++) {
            CompoundTag kc = dailyKl.getCompound(i);
            dailyBars[i][0] = kc.getDouble("Open");
            dailyBars[i][1] = kc.getDouble("High");
            dailyBars[i][2] = kc.getDouble("Low");
            dailyBars[i][3] = kc.getDouble("Close");
            dailyBars[i][4] = kc.getDouble("Volume");
            dailyBars[i][5] = kc.contains("PlayerVolume") ? kc.getDouble("PlayerVolume") : 0.0;
        }
        CompoundTag curDaily = sc.getCompound("CurDailyKline");
        double[] liveDaily = new double[StockState.KLINE_FIELDS];
        liveDaily[0] = curDaily.getDouble("Open");
        liveDaily[1] = curDaily.getDouble("High");
        liveDaily[2] = curDaily.getDouble("Low");
        liveDaily[3] = curDaily.getDouble("Close");
        liveDaily[4] = curDaily.getDouble("Volume");
        liveDaily[5] = curDaily.contains("PlayerVolume") ? curDaily.getDouble("PlayerVolume") : 0.0;
        st.loadDailyKlines(dailyBars, dailyBars.length, liveDaily,
                sc.getInt("LiveDailyKlineFilled"), sc.getLong("DailyKlinePushes"));
        st.loadFirstDayPending(sc.getBoolean("FirstDayPending"));
        return st;
    }

    /** Holding entries: entries in Entries carrying Avg also seed costBasis (only sellable records carry the cost). */
    private static Map<UUID, Map<String, Integer>> readPositionMap(ListTag list, MarketSavedData data,
                                                                   Map<UUID, Map<String, Double>> costSink,
                                                                   boolean migrate) {
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        Set<String> unknownCodes = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag hc = list.getCompound(i);
            String uuid = hc.getString("Id");
            if (uuid.isEmpty()) {
                continue;
            }
            try {
                UUID id = UUID.fromString(uuid);
                Map<String, Integer> map = new HashMap<>();
                ListTag entries = hc.getList("Entries", Tag.TAG_COMPOUND);
                for (int j = 0; j < entries.size(); j++) {
                    CompoundTag en = entries.getCompound(j);
                    String raw = en.getString("Stock");
                    String code = resolveLegacyStockKey(raw);
                    int qty = en.getInt("Qty");
                    if (code == null || qty <= 0) {
                        continue;
                    }
                    if (migrate && !isBuiltinCode(code)) {
                        unknownCodes.add(code);   // unknown codes found in the old format: keep the data, mark them retired
                    }
                    map.merge(code, qty, Integer::sum);
                    if (costSink != null) {
                        double avg = en.getDouble("Avg");
                        if (avg > 0) {
                            costSink.computeIfAbsent(id, k -> new HashMap<>())
                                    .merge(code, avg, Math::max);
                        }
                    }
                }
                if (!map.isEmpty()) {
                    out.merge(id, map, (a, b) -> {
                        b.forEach((code, qty) -> a.merge(code, qty, Integer::sum));
                        return a;
                    });
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        data.retiredCodes.addAll(unknownCodes);
        return out;
    }

    /** Writes a player -> (code -> quantity) table; when withCost=true the average cost is attached as well. */
    private static ListTag writePositionMap(Map<UUID, Map<String, Integer>> src,
                                            Map<UUID, Map<String, Double>> costs, boolean withCost) {
        ListTag hold = new ListTag();
        src.forEach((id, map) -> {
            if (map.isEmpty()) {
                return;
            }
            CompoundTag hc = new CompoundTag();
            hc.putString("Id", id.toString());
            ListTag entries = new ListTag();
            Map<String, Double> myCosts = costs.getOrDefault(id, Map.of());
            map.forEach((code, qty) -> {
                CompoundTag en = new CompoundTag();
                en.putString("Stock", code);
                en.putInt("Qty", qty);
                if (withCost) {
                    Double avg = myCosts.get(code);
                    if (avg != null && avg > 0) {
                        en.put("Avg", DoubleTag.valueOf(avg));
                    }
                }
                entries.add(en);
            });
            hc.put("Entries", entries);
            hold.add(hc);
        });
        return hold;
    }

    private static void readPlayerStockDoubleMap(ListTag list, Map<UUID, Map<String, Double>> target) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag playerTag = list.getCompound(i);
            String uuid = playerTag.getString("Id");
            if (uuid.isEmpty()) {
                continue;
            }
            try {
                UUID id = UUID.fromString(uuid);
                ListTag entries = playerTag.getList("Entries", Tag.TAG_COMPOUND);
                for (int j = 0; j < entries.size(); j++) {
                    CompoundTag entry = entries.getCompound(j);
                    String code = entry.getString("Stock");
                    double amount = entry.getDouble("Amount");
                    if (code != null && !code.isEmpty() && Double.isFinite(amount) && amount != 0.0) {
                        target.computeIfAbsent(id, k -> new HashMap<>()).merge(code, amount, Double::sum);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed persisted P&L accounting history is skipped; it must never block market data loading.
            }
        }
        target.values().removeIf(Map::isEmpty);
    }

    private static void writePlayerStockDoubleMap(CompoundTag tag, String key,
                                                  Map<UUID, Map<String, Double>> source) {
        ListTag list = new ListTag();
        source.forEach((id, map) -> {
            if (map.isEmpty()) {
                return;
            }
            CompoundTag playerTag = new CompoundTag();
            playerTag.putString("Id", id.toString());
            ListTag entries = new ListTag();
            map.forEach((code, amount) -> {
                if (code == null || code.isEmpty() || !Double.isFinite(amount) || amount == 0.0) {
                    return;
                }
                CompoundTag entry = new CompoundTag();
                entry.putString("Stock", code);
                entry.putDouble("Amount", amount);
                entries.add(entry);
            });
            if (!entries.isEmpty()) {
                playerTag.put("Entries", entries);
                list.add(playerTag);
            }
        });
        tag.put(key, list);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        // global data version: every structure saved by code belongs to the version-2 format
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putLong("TickCounter", tickCounter);
        tag.putLong("MarketTicks", marketTicks);
        tag.putInt("KlinePeriodTick", klinePeriodTick);

        CompoundTag stocks = new CompoundTag();
        states.forEach((code, st) -> {
            CompoundTag sc = new CompoundTag();
            sc.putDouble("Price", st.price);
            sc.putInt("Trend", st.trend);
            sc.putInt("TrendTicks", st.trendTicksLeft);
            ListTag hist = new ListTag();
            for (double v : st.recentHistory()) {
                hist.add(DoubleTag.valueOf(v));
            }
            sc.put("History", hist);
            ListTag kl = new ListTag();
            for (double[] bar : st.recentKlines()) {
                CompoundTag kc = new CompoundTag();
                kc.putDouble("Open", bar[0]);
                kc.putDouble("High", bar[1]);
                kc.putDouble("Low", bar[2]);
                kc.putDouble("Close", bar[3]);
                kc.putDouble("Volume", bar.length > 4
                        ? cn.oremarket.common.MarketVolume.of(bar) : 0.0);
                kc.putDouble("PlayerVolume", bar.length > cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT
                        ? cn.oremarket.common.MarketVolume.sanitize(bar[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT]) : 0.0);
                kl.add(kc);
            }
            sc.put("Klines", kl);
            ListTag dailyKl = new ListTag();
            for (double[] bar : st.recentDailyKlines()) {
                CompoundTag kc = new CompoundTag();
                kc.putDouble("Open", bar[0]);
                kc.putDouble("High", bar[1]);
                kc.putDouble("Low", bar[2]);
                kc.putDouble("Close", bar[3]);
                kc.putDouble("Volume", bar.length > 4
                        ? cn.oremarket.common.MarketVolume.of(bar) : 0.0);
                kc.putDouble("PlayerVolume", bar.length > StockState.PLAYER_VOLUME_SLOT
                        ? cn.oremarket.common.MarketVolume.sanitize(bar[StockState.PLAYER_VOLUME_SLOT]) : 0.0);
                dailyKl.add(kc);
            }
            sc.put("DailyKlines", dailyKl);
            double[] liveDaily = st.liveDailyKline();
            CompoundTag curDaily = new CompoundTag();
            curDaily.putDouble("Open", liveDaily[0]);
            curDaily.putDouble("High", liveDaily[1]);
            curDaily.putDouble("Low", liveDaily[2]);
            curDaily.putDouble("Close", liveDaily[3]);
            curDaily.putDouble("Volume", liveDaily.length > 4
                    ? cn.oremarket.common.MarketVolume.of(liveDaily) : 0.0);
            curDaily.putDouble("PlayerVolume", liveDaily.length > StockState.PLAYER_VOLUME_SLOT
                    ? cn.oremarket.common.MarketVolume.sanitize(liveDaily[StockState.PLAYER_VOLUME_SLOT]) : 0.0);
            sc.put("CurDailyKline", curDaily);
            sc.putInt("LiveDailyKlineFilled", st.liveDailyKlineFilled());
            sc.putLong("DailyKlinePushes", st.dailyKlinePushes());
            double[] live = st.liveKline();
            CompoundTag curK = new CompoundTag();
            curK.putDouble("Open", live[0]);
            curK.putDouble("High", live[1]);
            curK.putDouble("Low", live[2]);
            curK.putDouble("Close", live[3]);
            curK.putDouble("Volume", live.length > 4
                    ? cn.oremarket.common.MarketVolume.of(live) : 0.0);
            curK.putDouble("PlayerVolume", live.length > cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT
                    ? cn.oremarket.common.MarketVolume.sanitize(live[cn.oremarket.common.StockState.PLAYER_VOLUME_SLOT]) : 0.0);
            sc.put("CurKline", curK);
            sc.putInt("LiveKlineFilled", st.liveKlineFilled());
            sc.putLong("KlinePushes", st.klinePushes());
            sc.putBoolean("FirstDayPending", st.firstDayPending());
            stocks.put(code, sc);
        });
        tag.put("Stocks", stocks);

        ListTag ev = new ListTag();
        for (ActiveEvent e : events) {
            ev.add(e.save());
        }
        tag.put("Events", ev);
        tag.putLong("LastLegendary", lastLegendaryTick);
        tag.putInt("EventSeq", eventSeq);
        tag.putInt("EventTickInterval", eventTickInterval);
        ListTag susp = new ListTag();
        suspendCooldown.forEach((code, tick) -> {
            CompoundTag sc = new CompoundTag();
            sc.putString("Stock", code);
            sc.putLong("Tick", tick);
            susp.add(sc);
        });
        tag.put("SuspendCooldown", susp);

        ListTag bList = new ListTag();
        balances.forEach((id, amount) -> {
            CompoundTag bc = new CompoundTag();
            bc.putString("Id", id.toString());
            bc.putLong("Amount", amount);
            bList.add(bc);
        });
        tag.put("Balances", bList);

        ListTag pList = new ListTag();
        peakBalances.forEach((id, amount) -> {
            CompoundTag pc = new CompoundTag();
            pc.putString("Id", id.toString());
            pc.putLong("Amount", amount);
            pList.add(pc);
        });
        tag.put("PeakBalances", pList);

        ListTag hList = new ListTag();
        highestLevels.forEach((id, level) -> {
            CompoundTag hc = new CompoundTag();
            hc.putString("Id", id.toString());
            hc.putLong("Level", Math.max(1, level));
            hList.add(hc);
        });
        tag.put("HighestLevel", hList);

        // retirement markers: only the markers themselves are stored; holding / candlestick / cost data all stay intact in the structures above
        ListTag retired = new ListTag();
        for (String code : retiredCodes) {
            retired.add(net.minecraft.nbt.StringTag.valueOf(code));
        }
        tag.put("RetiredCodes", retired);

        tag.putLong("DayProgress", dayProgress);

        tag.put("Holdings", writePositionMap(holdings, costBasis, true));
        tag.put("Frozen", writePositionMap(frozen, Map.of(), false));

        ListTag rList = new ListTag();
        realizedPnl.forEach((id, map) -> {
            if (map.isEmpty()) {
                return;
            }
            CompoundTag rc = new CompoundTag();
            rc.putString("Id", id.toString());
            ListTag entries = new ListTag();
            map.forEach((code, amount) -> {
                CompoundTag en = new CompoundTag();
                en.putString("Stock", code);
                en.putDouble("Amount", amount);
                entries.add(en);
            });
            rc.put("Entries", entries);
            rList.add(rc);
        });
        tag.put("RealizedPnl", rList);
        writePlayerStockDoubleMap(tag, "RealizedShortPnl", realizedShortPnl);

        tag.putLong("TradeDay", tradeDay);
        tag.putLong("LastLegendaryDay", lastLegendaryDay);
        tag.putLong("LastDividendDay", lastDividendDay);
        ListTag comboKeys = new ListTag();
        for (String k : comboFiredKeys) {
            comboKeys.add(StringTag.valueOf(k));
        }
        tag.put("ComboFiredKeys", comboKeys);
        ListTag loanList = new ListTag();
        loans.forEach((id, loan) -> {
            CompoundTag lc = loan.toTag();
            lc.putString("Id", id.toString());
            loanList.add(lc);
        });
        tag.put("Loans", loanList);

        // ---------- v2 - P2P listings
        ListTag p2pOut = new ListTag();
        for (P2PListing pl : p2pListings.values()) {
            p2pOut.add(pl.toTag());
        }
        tag.put("P2PListings", p2pOut);
        tag.putLong("P2PNextId", p2pNextId);
        tag.putLong("P2PEscrow", p2pEscrow);

        // ---------- added: shorts / pending orders / Limit Orders / dynamic stocks / daily baseline, all CompoundTag-serialized
        ListTag shortList = new ListTag();
        shorts.forEach((id, map) -> map.values().forEach(p -> shortList.add(p.toTag())));
        tag.put("Shorts", shortList);
        tag.putLong("NextShortId", nextShortId);

        ListTag shortsCdList = new ListTag();
        shortsCooldownEnd.forEach((id, end) -> {
            CompoundTag cc = new CompoundTag();
            cc.putString("Id", id.toString());
            cc.putLong("End", end);
            shortsCdList.add(cc);
        });
        tag.put("ShortsCooldowns", shortsCdList);

        // ---------- persist maxShortPools / currentShortPools along with the rest
        ListTag poolList = new ListTag();
        for (Map.Entry<String, Integer> e : shortPoolMax.entrySet()) {
            CompoundTag pc = new CompoundTag();
            pc.putString("Code", e.getKey());
            pc.putInt("Max", Math.max(0, e.getValue()));
            pc.putInt("Left", Math.max(0, shortPoolLeft.getOrDefault(e.getKey(), e.getValue())));
            poolList.add(pc);
        }
        tag.put("ShortPools", poolList);
        tag.putLong("LoanReserve", loanReserve);

        ListTag orderList = new ListTag();
        for (PendingOrder o : pendingOrders) {
            orderList.add(o.toTag());
        }
        tag.put("PendingOrders", orderList);

        ListTag limitList = new ListTag();
        for (LimitOrder o : limitOrders) {
            limitList.add(o.toTag());
        }
        tag.put("LimitOrders", limitList);

        ListTag dynList = new ListTag();
        dynamicStocks.forEach((code, def) -> dynList.add(def.toTag()));
        tag.put("DynamicStocks", dynList);

        ListTag delistList = new ListTag();
        delistCounters.forEach((code, days) -> {
            CompoundTag dc = new CompoundTag();
            dc.putString("Code", code);
            dc.putInt("Days", days);
            delistList.add(dc);
        });
        tag.put("DelistCounters", delistList);

        ListTag anchorList = new ListTag();
        quoteAnchorScales.forEach((code, scale) -> {
            if (code != null && !code.isEmpty() && scale != null
                    && Double.isFinite(scale) && scale > 0.0) {
                CompoundTag ac = new CompoundTag();
                ac.putString("Code", code);
                ac.putDouble("Scale", scale);
                anchorList.add(ac);
            }
        });
        tag.put("QuoteAnchors", anchorList);

        ListTag builtinDelist = new ListTag();
        builtinDelistedDays.forEach((code, day) -> {
            CompoundTag bc = new CompoundTag();
            bc.putString("Code", code);
            bc.putLong("Day", day);
            builtinDelist.add(bc);
        });
        tag.put("BuiltinDelistedDays", builtinDelist);

        ListTag baseList = new ListTag();
        dailyBaseline.forEach((id, assets) -> {
            CompoundTag bc = new CompoundTag();
            bc.putString("Id", id.toString());
            bc.putLong("Assets", assets);
            baseList.add(bc);
        });
        tag.put("DailyBaseline", baseList);

        ListTag nameList = new ListTag();
        playerNames.forEach((id, name) -> {
            CompoundTag nc = new CompoundTag();
            nc.putString("Id", id.toString());
            nc.putString("Name", name);
            nameList.add(nc);
        });
        tag.put("PlayerNames", nameList);

        ListTag starterGrantedList = new ListTag();
        starterGrantedPlayers.forEach(id -> {
            CompoundTag sc = new CompoundTag();
            sc.putString("Id", id.toString());
            starterGrantedList.add(sc);
        });
        tag.put("StarterGrantedPlayers", starterGrantedList);

        ListTag cancelNoticeList = new ListTag();
        orderCancelNotice.forEach((id, n) -> {
            if (n == null || (n[0] <= 0 && n[1] <= 0)) {
                return;
            }
            CompoundTag noticeTag = new CompoundTag();
            noticeTag.putString("Id", id.toString());
            noticeTag.putInt("Orders", Math.max(0, n[0]));
            noticeTag.putInt("Limits", Math.max(0, n[1]));
            cancelNoticeList.add(noticeTag);
        });
        tag.put("OrderCancelNotice", cancelNoticeList);

        ListTag offlineNoticeList = new ListTag();
        offlineNotices.forEach((id, queue) -> {
            if (id == null || queue == null || queue.isEmpty()) {
                return;
            }
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putString("Id", id.toString());
            ListTag entries = new ListTag();
            for (OfflineNotice notice : queue) {
                if (notice == null || notice.key.isEmpty()) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putString("Key", notice.key);
                ListTag args = new ListTag();
                for (String arg : notice.args) {
                    args.add(StringTag.valueOf(arg == null ? "" : arg));
                }
                entry.put("Args", args);
                entries.add(entry);
            }
            if (entries.isEmpty()) {
                return;
            }
            ownerTag.put("Entries", entries);
            offlineNoticeList.add(ownerTag);
        });
        tag.put("OfflineNotices", offlineNoticeList);

        tag.putLong("OrderSequence", orderSequence);
        tag.putLong("LastOrderMatchTick", lastOrderMatchMarketTick);

        return tag;
    }
}
