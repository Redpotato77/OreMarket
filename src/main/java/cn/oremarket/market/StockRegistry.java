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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import cn.oremarket.common.StockState;

/**
 * Dynamic stock registry.
 *
 * Two independent registries:
 * - server {@link #server()}: parsed from config by {@link #rebuild()}, mutated only on the server main thread;
 * static initialisation seeds only the 8 built-in defaults, NEVER reading config inside a static block; rebuild() is
 * called after startup and config load to append custom stocks.
 * - client {@link #client()}: built from the SyncConfigPacket stockRegistry field; the client NEVER runs rebuild,
 * and the snapshot is immutable once built.
 *
 * Thread safety: after mutating the list a fresh immutable snapshot is produced and swapped in wholesale via a volatile
 * reference; every reader takes the volatile reference first, so readers always see one complete consistent list and
 * never a half-finished rebuild state.
 */
public final class StockRegistry {
    private static final Logger LOG = LoggerFactory.getLogger("OreMarket/Registry");

    /** Cap on custom stocks. */
    public static final int MAX_CUSTOM = 16;
    /** Stock code: 2-8 characters, uppercase letters and digits only. */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z0-9]{2,8}$");
    /** Max K-line candles and max history points: every stock (built-in or custom) shares one capacity. */
    public static final int KLINE_CAPACITY = StockState.KLINE_LEN;
    public static final int HISTORY_CAPACITY = StockState.HISTORY_LEN;

    // ---------------------------------------------------------------- the two instances

    private static final StockRegistry SERVER = new StockRegistry();
    private static final StockRegistry CLIENT = new StockRegistry();

    static {
        // Static initialisation: seeds only the 8 built-in default templates as the server's first snapshot; the client stays empty until SyncConfigPacket.
        SERVER.setFrom(new ArrayList<>(Stock.BUILTIN_TEMPLATES));
        CLIENT.setFrom(new ArrayList<>());
    }

    /** Immutable snapshot + two derived lists; swapped wholesale via volatile. */
    private static final class Snap {
        final List<Stock> all;                 // Including delisted (in insertion order)
        final List<Stock> active;              // Excluding delisted (for the UI / exchange / quote loop)
        final Map<String, Stock> byCode;       // Including delisted
        final Set<String> activeCodes;

        Snap(List<Stock> all) {
            this.all = List.copyOf(all);
            List<Stock> act = new ArrayList<>();
            Map<String, Stock> map = new LinkedHashMap<>();
            Set<String> codes = new LinkedHashSet<>();
            for (Stock s : all) {
                map.put(s.code(), s);
                if (!s.retired()) {
                    act.add(s);
                    codes.add(s.code());
                }
            }
            this.active = List.copyOf(act);
            this.byCode = Collections.unmodifiableMap(map);
            this.activeCodes = Collections.unmodifiableSet(codes);
        }
    }

    private volatile Snap snap = new Snap(Collections.emptyList());

    private StockRegistry() {
    }

    private void setFrom(List<Stock> list) {
        snap = new Snap(list);
    }

    public static StockRegistry server() {
        return SERVER;
    }

    public static StockRegistry client() {
        return CLIENT;
    }

    // ---------------------------------------------------------------- static convenience (server authority + automatic side pick)

    /**
     * The registry the current caller should use: the server main thread gets the server snapshot, everything else (the client main thread) gets the client snapshot.
     * In single-player the server and client share a process but different threads; each still takes its own.
     */
    public static StockRegistry current() {
        return MarketSettings.isOnServerThread() ? SERVER : CLIENT;
    }

    public static Stock getByCode(String code) {
        return current().snap.byCode.get(code);
    }

    /** Built-in stocks in fixed order (for migrations and legacy index-based access); out of range returns null. */
    public static Stock getBuiltinByIndex(int index) {
        if (index < 0 || index >= Stock.BUILTIN_TEMPLATES.size()) {
            return null;
        }
        return getByCode(Stock.BUILTIN_CODES.get(index));
    }

    /** Index into the current registry by the active list position (out of range returns null). */
    public static Stock getByIndex(int index) {
        List<Stock> act = current().snap.active;
        return index >= 0 && index < act.size() ? act.get(index) : null;
    }

    public static List<Stock> getAll() {
        return current().snap.all;
    }

    public static List<Stock> getActiveStocks() {
        return current().snap.active;
    }

    /** Synonymous with getActiveStocks (both names kept to serve the excluding-delisted semantics). */
    public static List<Stock> getActiveStocksExcludingRetired() {
        return current().snap.active;
    }

    public static int size() {
        return current().snap.all.size();
    }

    public static int activeSize() {
        return current().snap.active.size();
    }

    public static boolean isRetired(String code) {
        Stock s = current().snap.byCode.get(code);
        return s != null && s.retired();
    }

    public static List<Stock> snapshot() {
        return current().snap.all;
    }

    // ---------------------------------------------------------------- server rebuild

    /**
     * Rebuild on the server main thread: parse the 8 built-ins from config (falling back to template defaults) + append/update custom stocks;
     * stocks removed from config are MARKED DELISTED and kept at the tail of the registry (their data is not deleted).
     * Returns the custom stocks newly listed in this pass (no quote record for them in the save yet).
     */
    public static synchronized List<Stock> rebuild(MarketSettings ms) {
        if (!MarketSettings.isOnServerThread()) {
            LOG.warn("rebuild must run on the server thread; ignored (current thread {})",
                    Thread.currentThread().getName());
            return Collections.emptyList();
        }
        List<Stock> next = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        MarketSavedData listingData = MarketSavedData.currentForRegistry();

        // 1) The 8 built-ins: values from config (categories 2/3), levels fixed.
        // When builtinDynamicListing is on and the save carries a delisting record, keep a delisted copy (positions frozen, hidden in the UI),
        // restored by ListingService.relistEligible once the interval elapses; with the switch off the delisting record is cleared and the stock relists automatically.
        boolean builtinDelist = ms != null && ms.builtinDynamicListing;
        for (Stock tpl : Stock.BUILTIN_TEMPLATES) {
            double base = ms != null ? ms.builtinBasePrice(tpl.code()) : tpl.basePrice();
            long rate = ms != null ? ms.builtinExchangeRate(tpl.code()) : tpl.exchangeRate();
            Stock built = Stock.builtinResolved(tpl.code(), tpl.itemId(), tpl.displayName(),
                    base, rate, tpl.tier());
            boolean delistedNow = builtinDelist && listingData != null
                    && listingData.builtinDelistedDays.containsKey(tpl.code());
            next.add(delistedNow ? built.retiredCopy() : built);
            seen.add(tpl.code());
        }

        // 2) Custom stocks: parse + ten-step validation; valid ones are appended in config order
        List<Stock> newlyCustom = new ArrayList<>();
        List<String> rawCustom = ms == null ? Collections.emptyList() : ms.customStocks;
        List<Stock> customs = parseCustomStocks(rawCustom, seen);
        for (Stock c : customs) {
            Stock prev = SERVER.snap.byCode.get(c.code());
            if (prev != null && prev.retired()) {
                // Re-adding the same code (delisted earlier in this JVM): positions/cost/realised/frozen survive from the save,
                // the price resets to the new base price and K-lines clear -- rebuildAndApply resets the quote state and logs it.
                LOG.info("Custom stock {} relisted: price reset to base, candle history cleared", c.code());
            } else if (prev == null) {
                newlyCustom.add(c);
            }
            next.add(c);
            seen.add(c.code());
        }

        // 3) Dynamically listed stocks: take the listed definitions from the current server save; delisted definitions do not enter the active list.
        if (listingData != null) {
            for (DynamicStockDef def : listingData.dynamicStocks.values()) {
                if (seen.contains(def.code) || def.code.isEmpty()) {
                    continue;
                }
                seen.add(def.code);
                Stock stock = def.toStock();
                next.add(def.listed ? stock : stock.retiredCopy());
            }
        }

        // 4) Stocks present in the previous registry but absent from this version's config/dynamic stocks: marked delisted and kept at the tail (including ones already delisted before)
        for (Stock prev : SERVER.snap.all) {
            if (!seen.contains(prev.code()) && !Stock.BUILTIN_CODES.contains(prev.code())) {
                Stock r = prev.retired() ? prev : prev.retiredCopy();
                next.add(r);
                seen.add(prev.code());
                if (!prev.retired()) {
                    LOG.info("Custom stock {} removed from config: marked delisted (holdings kept, trading disabled)", prev.code());
                }
            }
        }

        SERVER.setFrom(next);
        LOG.info("StockRegistry rebuilt: {} active, {} delisted, {} newly listed this pass",
                SERVER.snap.active.size(), SERVER.snap.all.size() - SERVER.snap.active.size(),
                newlyCustom.size());
        return newlyCustom;
    }

    /** The current set of active stock codes (server snapshot). */
    public static Set<String> serverActiveCodes() {
        Set<String> out = new LinkedHashSet<>();
        for (Stock s : SERVER.snap.active) {
            out.add(s.code());
        }
        return out;
    }

    /**
     * Rebuild and apply every side effect for real (server main thread; called after hot reload and after startup):
     * 1. StockRegistry.rebuild() parses the config;
     * 2. New stocks get initialised quotes: price = base price, history / klineHistory empty arrays (never 80 zeros);
     * 3. Removed stocks: immediately end all events affecting them, write the delisted mark into the save, log one system line;
     * 4. Relisted stocks: K-lines and price reset (positions/cost kept), delisted mark cleared, logged;
     * 5. Purge delisted stocks from the ON_DEMAND push memory.
     */
    public static synchronized void rebuildAndApply(net.minecraft.server.MinecraftServer server) {
        MarketSettings ms = MarketSettings.server();
        MarketSavedData data = MarketSavedData.get(server);
        // Pre-rebuild snapshot (to tell a relist from a first record)
        Set<String> beforeActive = serverActiveCodes();
        Set<String> beforeRetired = new LinkedHashSet<>();
        for (Stock s : SERVER.snap.all) {
            if (s.retired()) {
                beforeRetired.add(s.code());
            }
        }
        java.util.List<Stock> newCodes = rebuild(ms);
        java.util.Set<String> afterActive = serverActiveCodes();

        // 2) Every active stock must have a state; brand-new records get an empty state (price = base price, empty K-lines)
        data.ensureStatesPresent();

        // 4) Relisted: active again this pass but previously marked delisted ANYWHERE (this JVM's registry snapshot, or the save's RetiredCodes
        // -- covering the same code re-added to config after a restart) custom code:
        // positions / average cost / realised P&L / frozen all survive as-is (SavedData keeps them by code),
        // K-lines and trend clear, price = the new base price, and the client is told to evict its stale cached entry.
        for (String code : afterActive) {
            boolean builtin = Stock.BUILTIN_CODES.contains(code);
            boolean wasRetired = beforeRetired.contains(code) || data.isCodeRetiredFlagged(code);
            boolean isFresh = !beforeActive.contains(code) && !data.hasStateRecord(code);
            if (wasRetired && !beforeActive.contains(code)) {
                Stock reAdded = StockRegistry.server().getByCode(code);
                StockState st = data.state(code);
                if (st != null) {
                    st.resetFresh(reAdded != null ? reAdded.basePrice() : 0);
                }
                if (reAdded != null) {
                    data.resetCostsForReAddedCode(code, reAdded.basePrice());
                }
                data.clearCodeRetired(code);
                // Built-in relist announcements are written by ListingService under the stock_relisted key;
                // the automatic recovery with the switch off writes no such line (the player did not relist anything).
                // Dynamically listed random stocks are likewise announced by ListingService via
                // the stock_relisted / stock_listed keys; adding a custom-stock-re-added line here too would cross flavours:
                // one listing event would show two unrelated announcements in the player's log page and toast bar.
                if (!builtin && !data.dynamicStocks.containsKey(code)) {
                    cn.oremarket.market.log.LogService.pushAllKey(server, data,
                            cn.oremarket.common.LogType.SYSTEM,
                            "oremarket.log.custom_readded", reAdded == null ? code : reAdded.displayName());
                }
            } else if (isFresh && !builtin && !data.dynamicStocks.containsKey(code)) {
                cn.oremarket.market.log.LogService.pushAllKey(server, data,
                        cn.oremarket.common.LogType.SYSTEM,
                        "oremarket.log.custom_added", code);
            }
        }

        // 3) Removed: codes newly falling into the delisted set -> immediately end events affecting them + save mark + log
        for (Stock s : SERVER.snap.all) {
            if (s.retired() && !beforeRetired.contains(s.code())) {
                // Fixed (delisting cleanup on ListingService's exact convention): an admin deleting a stock from the config
                // is THE SAME EVENT as ListingService retiring it, yet before only a delisting mark was written:
                // limit orders stayed on the book, short positions kept margin locked, and the pool lingered in the save --
                // the player could neither close (no quotes) nor get money back, and that margin became an orphan.
                // Now all three steps run: cancel every order of that stock -> force-close shorts at the last price and refund margin -> retire the pool.
                // forceCloseCode refunds the FULL margin at the open price when quotes are gone;
                // quietly deleting a position with no refund can no longer happen.
                data.cancelStockOrders(server, s.code());
                ShortService.forceCloseCode(server, data, s.code(), "oremarket.short.reason.delisted");
                data.dropShortPool(s.code());
                data.markCodeRetired(s.code());
                int ended = cn.oremarket.market.event.EventEngine
                        .endEventsAffecting(data, s.code());
                // Built-in delisting announcements go through ListingService under the stock_delisted key; this logs only config-removed custom stocks
                // (dynamic stocks' delistings are likewise announced by ListingService, not garbled into custom stock removed)
                if (!Stock.BUILTIN_CODES.contains(s.code()) && !data.dynamicStocks.containsKey(s.code())) {
                    cn.oremarket.market.log.LogService.pushAllKey(server, data,
                            cn.oremarket.common.LogType.SYSTEM,
                            "oremarket.log.custom_removed", s.displayName(), ended);
                }
            }
        }
        // Align the save's delisting marks for unknown codes with the registry: codes the registry made active again are unmarked (handled above);
        // this clears, as a last resort, marks the save still carries for codes the registry has wholly forgotten (neither an active nor a delisted entry)
        for (String code : new java.util.ArrayList<>(data.retiredCodes)) {
            if (afterActive.contains(code)) {
                data.clearCodeRetired(code);
            }
        }
        // builtinDynamicListing off: the built-ins were relisted during rebuild, so clear historical delisting records to avoid state drift
        if (ms != null && !ms.builtinDynamicListing && !data.builtinDelistedDays.isEmpty()) {
            data.builtinDelistedDays.clear();
            LOG.info("builtinDynamicListing disabled: delisted built-in stocks restored to listing (records cleared)");
        }
        // 5) Last resort: codes with quote records in the save but with NEITHER an active NOR a delisted entry in the registry
        // (config deleted then reopened, or unknown codes nobody ever wrote) -> keep the data and mark them delisted.
        java.util.Set<String> known = new java.util.LinkedHashSet<>();
        for (Stock s2 : SERVER.snap.all) {
            known.add(s2.code());
        }
        for (String code : new java.util.ArrayList<>(data.states.keySet())) {
            if (!known.contains(code)) {
                data.markCodeRetired(code);
            }
        }
        // ---------- legacy-save compatibility + unified fallback on listing/rebuild
        // Every active stock must own its short pool; when missing (a legacy save from before the fix / a custom stock just listed this pass)
        // create it immediately on the pure-share formula (defaultBaseShares / the stock's OWN base price, clamped to
        // [absoluteMinShares, absoluteMaxShares]), a hard guard so no read path can ever throw NPE.
        // An existing pool only gets its missing Left keys backfilled; balances are never reset (guaranteed inside ensureShortPool).
        for (Stock s : getActiveStocks()) {
            data.ensureShortPool(s.code(), s.basePrice());
        }
        MarketEngine.onRegistryRebuilt();
        data.setDirty();
    }

    // ---------------------------------------------------------------- client build

    /**
     * The client builds the whole snapshot from the SyncConfigPacket stockRegistry field and swaps it atomically.
     * Called on the client main thread only; immutable once built (the next packet replaces it wholesale). Returns the parsed entries so the caller can build quotes.
     */
    public static synchronized List<Stock> replaceFromClientTag(Tag registryTag) {
        List<Stock> parsed = new ArrayList<>();
        if (registryTag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof CompoundTag e) {
                    Stock s = readEntry(e);
                    if (s != null) {
                        parsed.add(s);
                    }
                }
            }
        }
        CLIENT.setFrom(parsed);
        return parsed;
    }

    /** Serialise the registry into the stockRegistry ListTag (server -> SyncConfigPacket, dynamically sized). */
    public static ListTag toTag() {
        ListTag list = new ListTag();
        for (Stock s : SERVER.snap.all) {
            list.add(writeEntry(s));
        }
        return list;
    }

    private static CompoundTag writeEntry(Stock s) {
        CompoundTag e = new CompoundTag();
        e.putString("code", s.code());
        e.putString("itemId", s.itemId());
        e.putString("displayName", s.displayName());
        e.putDouble("basePrice", s.basePrice());
        e.putInt("exchangeRate", (int) Math.max(0, Math.min(Integer.MAX_VALUE, s.exchangeRate())));
        e.putInt("unlockLevel", s.unlockLevel());
        e.putByte("retired", (byte) (s.retired() ? 1 : 0));
        return e;
    }

    private static Stock readEntry(CompoundTag e) {
        String code = e.getString("code");
        if (code.isEmpty()) {
            return null;
        }
        boolean retired = e.getByte("retired") != 0;
        long rate = Math.max(1, e.getInt("exchangeRate"));
        int level = Math.max(1, Math.min(Stock.MAX_TIER, e.getInt("unlockLevel")));
        double base = Math.max(1.0, e.getDouble("basePrice"));
        if (retired) {
            // Delisted entries in the registry: built as active first, then copied into delisted clones (original fields preserved)
            return Stock.custom(code, e.getString("itemId"), e.getString("displayName"),
                    base, rate, level).retiredCopy();
        }
        return Stock.builtinResolved(code, e.getString("itemId"), e.getString("displayName"),
                base, rate, level);
    }

    // ---------------------------------------------------------------- custom-stock parsing (ten-step validation)

    /**
     * Parse customStocks line by line and return the valid custom stocks (in config order).
     * A failed ten-step validation prints the precise reason and skips that entry; {@code reserved} holds already-used codes
     * (built-ins), and codes claimed during parsing join it to prevent duplicates.
     */
    static List<Stock> parseCustomStocks(List<String> raw, Set<String> reserved) {
        List<Stock> out = new ArrayList<>();
        Set<String> usedCodes = new LinkedHashSet<>(reserved == null ? Set.of() : reserved);
        Set<String> boundItems = builtinItemIds();
        int customCount = 0;
        if (raw == null) {
            return out;
        }
        for (String lineRaw : raw) {
            String line = lineRaw == null ? "" : lineRaw.trim();
            if (line.isEmpty()) {
                continue;                       // Step 1: blank after trim -> skip silently
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length != 6) {            // Step 2: exactly 6 segments required
                warnBad(line, "expected 6 pipe-separated fields, got " + parts.length);
                continue;
            }
            String code = parts[0].trim().toUpperCase(Locale.ROOT);
            if (!CODE_PATTERN.matcher(code).matches()) {   // Step 3: 2-8 uppercase letters/digits
                warnBad(line, "invalid stock code (2-8 uppercase letters or digits required)");
                continue;
            }
            if (usedCodes.contains(code)) {     // Step 4: no clash with built-ins or already-parsed codes
                warnBad(line, "duplicate stock code " + code);
                continue;
            }
            if (customCount >= MAX_CUSTOM) {    // Step 5: at most 16 stocks
                warnBad(line, "custom stock limit reached (" + MAX_CUSTOM + ")");
                continue;
            }
            String itemId = parts[1].trim();
            ResourceLocation rl = ResourceLocation.tryParse(itemId);   // Step 6
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
                warnBad(line, "unknown item ID " + itemId);
                continue;
            }
            if (boundItems.contains(itemId)) {
                warnBad(line, "item already used by another stock: " + itemId);
                continue;
            }
            String name = parts[2].trim();
            if (name.length() > 16) {           // Step 7: display names longer than 16 are truncated
                name = name.substring(0, 16);
            }
            if (name.isEmpty()) {               // Step 8: empty display name -> localisation -> item id
                name = localizedOrDefault(rl);
            }
            double base;
            try {                               // Step 9: base price 1.0 ~ 100000.0
                base = Double.parseDouble(parts[3].trim());
            } catch (NumberFormatException e) {
                warnBad(line, "base price is not a number: " + parts[3]);
                continue;
            }
            if (!(base > 0) || Double.isNaN(base) || Double.isInfinite(base)
                    || base < 1.0 || base > 100000.0) {
                warnBad(line, "base price out of range 1.0..100000.0: " + parts[3]);
                continue;
            }
            long rate;
            try {                               // Step 10: exchange points >= 1, within 1 ~ 10000
                rate = Long.parseLong(parts[4].trim());
            } catch (NumberFormatException e) {
                warnBad(line, "exchange points is not an integer: " + parts[4]);
                continue;
            }
            if (rate < 1 || rate > 10000) {
                warnBad(line, "exchange points out of range 1..10000: " + parts[4]);
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(parts[5].trim());
            } catch (NumberFormatException e) {
                warnBad(line, "unlock level is not an integer: " + parts[5]);
                continue;
            }
            if (level < 1 || level > Stock.MAX_TIER) {     // Out-of-range values are clamped (not discarded)
                LOG.warn("Custom stock {} unlock level {} out of range, clamped to 1~{}", code, level, Stock.MAX_TIER);
                level = Math.max(1, Math.min(Stock.MAX_TIER, level));
            }
            // Warn when the exchange-points / base-price ratio exceeds 50% (price basis and exchange points diverging too far)
            if (rate > base * 0.5) {
                LOG.warn("Custom stock {}: exchange points {} exceed 50% of base price {}; please confirm this is not reversed (warning only, still accepted)",
                        code, rate, base);
            }
            Stock s = Stock.custom(code, rl.toString(), name, base, rate, level);
            out.add(s);
            usedCodes.add(code);
            boundItems.add(rl.toString());
            customCount++;
        }
        return out;
    }

    private static Set<String> builtinItemIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Stock s : Stock.BUILTIN_TEMPLATES) {
            ids.add(s.itemId());
        }
        return ids;
    }

    private static String localizedOrDefault(ResourceLocation rl) {
        Item it = BuiltInRegistries.ITEM.get(rl);
        if (it == net.minecraft.world.item.Items.AIR) {
            it = null;
        }
        if (it != null) {
            String loc = new ItemStack(it).getHoverName().getString();
            if (loc != null && !loc.isEmpty()) {
                return loc;
            }
        }
        return rl.toString();
    }

    private static void warnBad(String line, String why) {
        // User config text may be localized and is logged verbatim; the warning reason is always English.
        LOG.warn("Skipped invalid custom stock config line [{}] - {}", line, why);
    }
}
