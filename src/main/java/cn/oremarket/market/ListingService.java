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
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import cn.oremarket.common.StockState;

/** Dynamic listing, delisting and relisting; dynamic definitions and built-in / custom stocks share one stock registry. */
public final class ListingService {
    /** Console logs stay pure English (player-visible wording uses i18n keys); a bad config is warned about once instead of flooding every Trading Day. */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("OreMarket/Listing");
    private static final java.util.Set<String> WARNED_CANDIDATES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private static final String DEFAULT_IRON_RAW_RATE = "9";

    private static void warnCandidateOnce(String key, String message, Object... args) {
        if (WARNED_CANDIDATES.size() >= 256) {
            WARNED_CANDIDATES.clear();
        }
        if (WARNED_CANDIDATES.add(key)) {
            LOGGER.warn(message, args);
        }
    }

    /** Built-in candidate pool; replaced when the listing.candidates config is not empty.
     * Candidate-pool stocks are dynamic general-market stocks: they can receive generic events after listing,
     * but never mineral-only events whose wording assumes a built-in mineral market. */
    private static final String[][] DEFAULT_CANDIDATES = {
            {"EMERALD", "minecraft:emerald", cn.oremarket.common.RawText.t("oremarket.text.0119"), "25", "1"},
            {"QUARTZ", "minecraft:quartz", cn.oremarket.common.RawText.t("oremarket.text.0057"), "5", "1"},
            {"AMETHYST", "minecraft:amethyst_shard", cn.oremarket.common.RawText.t("oremarket.text.0118"), "8", "1"},
            {"ANCIENT_DEBRIS", "minecraft:ancient_debris", cn.oremarket.common.RawText.t("oremarket.text.0194"), "100", "1"},
            {"COPPER_RAW", "minecraft:raw_copper", cn.oremarket.common.RawText.t("oremarket.text.0061"), "5", "1"},
            {"IRON_RAW", "minecraft:raw_iron", cn.oremarket.common.RawText.t("oremarket.text.0060"), DEFAULT_IRON_RAW_RATE, "1"},
            {"GOLD_RAW", "minecraft:raw_gold", cn.oremarket.common.RawText.t("oremarket.text.0059"), "12", "1"},
            {"COBBLED_DEEP", "minecraft:cobbled_deepslate", cn.oremarket.common.RawText.t("oremarket.text.0114"), "2", "1"},
            {"OBSIDIAN", "minecraft:obsidian", cn.oremarket.common.RawText.t("oremarket.text.0125"), "15", "1"},
            {"GLOWSTONE", "minecraft:glowstone_dust", cn.oremarket.common.RawText.t("oremarket.text.0121"), "4", "1"},
    };

    private ListingService() {
    }

    /** Applies the first-day swing once per marketTick to freshly listed stocks (chance and magnitude both come from the config; normalisation lives in MarketSettings). */
    public static void applyFirstDayVolatility(MarketSavedData data, RandomSource rand) {
        MarketSettings ms = MarketSettings.current();
        for (Stock stock : StockRegistry.server().getActiveStocks()) {
            StockState state = data.state(stock);
            if (state == null || !state.firstDayPending()
                    || cn.oremarket.market.event.EventEngine.suspendTicksLeft(data, stock) > 0) {
                continue;
            }
            double roll = rand.nextDouble();
            double change;
            if (roll < ms.firstDayUpChance) {
                change = randRange(rand, ms.firstDayUpMin, ms.firstDayUpMax);
            } else if (roll < ms.firstDayUpChance + ms.firstDayDownChance) {
                change = -randRange(rand, ms.firstDayDownMin, ms.firstDayDownMax);
            } else {
                change = (rand.nextDouble() * 2.0 - 1.0) * ms.firstDayFlatRange;
            }
            // After the first-day swing is applied the price is always clamped to the floor/ceiling:
            // 25%~400% of the default base price (priceFloorPercent / priceCeilingPercent, config-driven, not hardcoded); the clamped price then re-enters the price-engine loop.
            double next = data.clampPrice(stock, state.price * (1.0 + change));
            state.price = next;
            state.loadFirstDayPending(false);
            data.setDirty();
        }
    }

    /** Uniform random draw from [min, max]; if min > max the swapped interval is used, so a negative-width range can never be rolled. */
    private static double randRange(RandomSource rand, double min, double max) {
        double lo = Math.min(min, max);
        double hi = Math.max(min, max);
        return lo + rand.nextDouble() * (hi - lo);
    }

    /** Trading Day close: delist, relist, and then add a new listing when the interval is due. */
    public static void onTradeDayClose(MinecraftServer server, MarketSavedData data) {
        MarketSettings settings = MarketSettings.current();
        boolean registryChanged = delistCodes(server, data,
                configuredDelistCandidates(data, settings));
        registryChanged |= delistCodes(server, data, dynamicDelistCandidates(data, settings));
        registryChanged |= relistEligible(server, data, settings);
        if (data.tradeDay % settings.listIntervalDays == 0) {
            registryChanged |= listRandom(server, data, settings);
        }
        if (registryChanged) {
            broadcastRegistry(server);
        }
    }

    private static List<String> configuredDelistCandidates(MarketSavedData data, MarketSettings settings) {
        List<String> out = new ArrayList<>();
        if (!settings.builtinDynamicListing) {
            return out;
        }
        for (Stock stock : StockRegistry.server().getActiveStocks()) {
            if (isDynamic(data, stock.code()) || !Stock.BUILTIN_CODES.contains(stock.code())) {
                continue;
            }
            if (belowDelistThreshold(data, stock.code(), data.state(stock),
                    data.anchorBase(stock), settings)) {
                out.add(stock.code());
            }
        }
        return out;
    }

    private static List<String> dynamicDelistCandidates(MarketSavedData data, MarketSettings settings) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> customClaimed = customClaimedCodes(settings);
        for (DynamicStockDef def : new ArrayList<>(data.dynamicStocks.values())) {
            if (!def.listed) {
                continue;
            }
            if (Stock.BUILTIN_CODES.contains(def.code) || customClaimed.contains(def.code)) {
                data.delistCounters.remove(def.code);
                continue;
            }
            if (belowDelistThreshold(data, def.code, data.state(def.code),
                    data.anchorBase(def.code, def.basePrice), settings)) {
                out.add(def.code);
            }
        }
        return out;
    }

    private static boolean belowDelistThreshold(MarketSavedData data, String code, StockState state,
                                                 double anchor, MarketSettings settings) {
        if (state == null) {
            return false;
        }
        double threshold = anchor * settings.delistThresholdRatio;
        int days = state.price < threshold
                ? data.delistCounters.merge(code, 1, Integer::sum)
                : resetDelistCounter(data, code);
        return days >= settings.delistConsecutiveDays;
    }

    private static int resetDelistCounter(MarketSavedData data, String code) {
        data.delistCounters.put(code, 0);
        return 0;
    }

    private static boolean delistCodes(MinecraftServer server, MarketSavedData data, List<String> codes) {
        boolean changed = false;
        for (String code : codes) {
            delist(server, data, code);
            changed = true;
        }
        return changed;
    }

    /** @return whether a dynamic stock was actually added (the caller uses this to decide whether to push the registry). */
    private static boolean listRandom(MinecraftServer server, MarketSavedData data, MarketSettings settings) {
        if (!settings.dynamicListingEnabled
                || StockRegistry.server().getActiveStocks().size() >= settings.listingMaxCount) {
            return false;
        }
        List<String[]> pool = new ArrayList<>();
        for (String[] candidate : candidates(settings)) {
            if (StockRegistry.server().getByCode(candidate[0]) == null && !data.dynamicStocks.containsKey(candidate[0])) {
                pool.add(candidate);
            }
        }
        if (pool.isEmpty()) {
            return false;
        }
        RandomSource rand = server.overworld().getRandom();
        String[] chosen = pool.get(rand.nextInt(pool.size()));
        long exchangeRate = parseLong(chosen[3], 1L);
        int unlockLevel = chosen.length >= 5 ? parseInt(chosen[4], settings.dynamicUnlockLevel)
                : settings.dynamicUnlockLevel;
        DynamicStockDef def = new DynamicStockDef(chosen[0], chosen[1], chosen[2],
                exchangeRate, exchangeRate * settings.dynamicBasePriceMultiplier, unlockLevel, true, 0);
        // A code reused after an earlier stock was removed must start a fresh quote round.
        data.resetAnchor(def.code);
        data.delistCounters.remove(def.code);
        data.dynamicStocks.put(def.code, def);
        data.setDirty();
        StockRegistry.rebuildAndApply(server);
        Stock listed = StockRegistry.server().getByCode(def.code);
        if (listed == null || listed.retired()) {
            // Defensive rollback: a hidden dynamic definition must never become a silent zombie record.
            data.dynamicStocks.remove(def.code);
            data.setDirty();
            warnCandidateOnce("zombie:" + def.code,
                    "dynamic listing of [{}] did not reach the active board; definition rolled back", def.code);
            return false;
        }
        StockState state = data.state(listed);
        if (state == null) {
            state = StockState.empty(listed);
            data.states.put(listed.code(), state);
        }
        state.setFirstDayPending(true);
        state.price = listed.basePrice();
        // The listing announcement is one whole-sentence key + arguments, no more splicing of language fragments
        LogService.pushAllKey(server, data, LogType.SYSTEM, "oremarket.log.stock_listed",
                listed.displayName(), String.format(Locale.ROOT, "%.2f", listed.basePrice()));
        MarketEngine.onRegistryRebuilt();
        return true;
    }

    private static void delist(MinecraftServer server, MarketSavedData data, String code) {
        // Capture the same display name used by the stock picker before rebuilding the registry.
        // Passing the raw code here made delisting logs show names such as "EMERALD", while relisting used the display name.
        Stock listedStock = StockRegistry.server().getByCode(code);
        String displayName = listedStock == null ? code : listedStock.displayName();
        data.dynamicStocks.computeIfPresent(code, (k, def) -> {
            def.listed = false;
            def.delistedDay = data.tradeDay;
            return def;
        });
        // built-in stock delisted: record the delisting day separately (the config-driven registry ignores dynamicStocks); relistEligible handles the relisting
        if (Stock.BUILTIN_CODES.contains(code)) {
            data.builtinDelistedDays.put(code, data.tradeDay);
        }
        // First cancel every open order on this stock, then force-close existing Short Positions at the last price (no fee, no tax, no volume);
        // ordinary holdings are not settled - they stay frozen in the save under the delisted marker.
        data.cancelStockOrders(server, code);
        ShortService.forceCloseCode(server, data, code, "oremarket.short.reason.delisted");
        // Delisting drops the pool; on relisting, ensureShortPool inside rebuildAndApply rebuilds it at the new base price.
        data.dropShortPool(code);
        data.delistCounters.remove(code);
        // Players still holding this stock: the shares stay in the save but leave the tradable
        // board. Tell every holder (toast online, login catch-up offline) so the position
        // "disappearing" from the market list is never silent.
        noticeHoldersDelisted(server, data, code, displayName);
        cn.oremarket.market.event.EventEngine
                .endEventsAffecting(data, code);
        data.markCodeRetired(code);
        data.setDirty();
        StockRegistry.rebuildAndApply(server);
        LogService.pushAllKey(server, data, LogType.SYSTEM, "oremarket.log.stock_delisted", displayName);
        MarketEngine.onRegistryRebuilt();
    }

    /** One login-safe notice per holder (sellable + frozen shares) of a stock that just left the board. */
    private static void noticeHoldersDelisted(MinecraftServer server, MarketSavedData data,
                                              String code, String displayName) {
        java.util.Map<java.util.UUID, Integer> qty = new java.util.LinkedHashMap<>();
        data.holdings.forEach((id, map) -> {
            int n = map.getOrDefault(code, 0);
            if (n > 0) {
                qty.merge(id, n, Integer::sum);
            }
        });
        data.frozen.forEach((id, map) -> {
            int n = map.getOrDefault(code, 0);
            if (n > 0) {
                qty.merge(id, n, Integer::sum);
            }
        });
        for (java.util.Map.Entry<java.util.UUID, Integer> e : qty.entrySet()) {
            OrderNoticeService.notice(server, data, e.getKey(),
                    "oremarket.toast.delist.held", false, displayName, e.getValue());
        }
    }

    /** @return whether at least one stock was actually relisted. */
    private static boolean relistEligible(MinecraftServer server, MarketSavedData data, MarketSettings settings) {
        return relistDynamicStocks(server, data, settings)
                | relistBuiltinStocks(server, data, settings);
    }

    private static boolean relistDynamicStocks(MinecraftServer server, MarketSavedData data,
                                                MarketSettings settings) {
        List<String> eligible = new ArrayList<>();
        for (DynamicStockDef def : data.dynamicStocks.values()) {
            if (!def.listed && data.tradeDay - def.delistedDay >= settings.relistIntervalDays) {
                eligible.add(def.code);
            }
        }
        boolean relisted = false;
        for (String code : eligible) {
            if (StockRegistry.server().getActiveStocks().size() >= settings.listingMaxCount) {
                break;
            }
            if (relistDynamic(server, data, settings, code)) {
                relisted = true;
            }
        }
        return relisted;
    }

    private static boolean relistDynamic(MinecraftServer server, MarketSavedData data,
                                         MarketSettings settings, String code) {
        DynamicStockDef def = data.dynamicStocks.get(code);
        if (def == null) {
            return false;
        }
        if (itemBoundByOtherStock(def.itemId, code)) {
            warnCandidateOnce("relist-bound:" + code + ":" + def.itemId,
                    "dynamic relist of [{}] skipped: item [{}] already backs another listed stock",
                    code, def.itemId);
            return false;
        }
        def.listed = true;
        def.basePrice = def.exchangeRate * settings.dynamicBasePriceMultiplier;
        def.delistedDay = 0;
        data.resetAnchor(code);
        data.clearCodeRetired(code);
        data.delistCounters.put(code, 0);
        StockRegistry.rebuildAndApply(server);
        Stock stock = StockRegistry.server().getByCode(code);
        if (stock == null || stock.retired()) {
            def.listed = false;
            def.delistedDay = data.tradeDay;
            data.setDirty();
            StockRegistry.rebuildAndApply(server);
            warnCandidateOnce("relist-zombie:" + code,
                    "dynamic relist of [{}] did not reach the active board; definition rolled back", code);
            return false;
        }
        resetRelistedState(data, code, stock);
        LogService.pushAllKey(server, data, LogType.SYSTEM, "oremarket.log.stock_relisted", def.displayName);
        MarketEngine.onRegistryRebuilt();
        return true;
    }

    private static boolean relistBuiltinStocks(MinecraftServer server, MarketSavedData data,
                                                MarketSettings settings) {
        if (!settings.builtinDynamicListing) {
            return false;
        }
        boolean relisted = false;
        for (String code : new ArrayList<>(data.builtinDelistedDays.keySet())) {
            if (StockRegistry.server().getActiveStocks().size() >= settings.listingMaxCount) {
                break;
            }
            long delistedDay = data.builtinDelistedDays.getOrDefault(code, 0L);
            if (data.tradeDay - delistedDay < settings.relistIntervalDays
                    || itemBoundByOtherStock(builtinItemId(code), code)) {
                continue;
            }
            data.builtinDelistedDays.remove(code);
            data.resetAnchor(code);
            data.clearCodeRetired(code);
            data.delistCounters.put(code, 0);
            StockRegistry.rebuildAndApply(server);
            Stock stock = StockRegistry.server().getByCode(code);
            if (stock != null) {
                resetRelistedState(data, code, stock);
                LogService.pushAllKey(server, data, LogType.SYSTEM, "oremarket.log.stock_relisted",
                        stock.displayName());
                MarketEngine.onRegistryRebuilt();
                relisted = true;
            }
        }
        return relisted;
    }

    private static void resetRelistedState(MarketSavedData data, String code, Stock stock) {
        StockState state = data.states.get(code);
        if (state == null) {
            data.states.put(code, StockState.empty(stock));
        } else {
            state.resetFresh(stock.basePrice());
            state.setFirstDayPending(true);
        }
        data.resetCostsForReAddedCode(code, stock.basePrice());
    }

    /** After a listing / delisting / relisting, push the latest config Snapshot plus the stock registry to every client (the UI adds or drops stocks at once).
     * Also sync the full market-data Snapshot to players with the screen open, so a new stock never shows in the list without a price. */
    private static void broadcastRegistry(MinecraftServer server) {
        cn.oremarket.net.OreMarketNetwork.syncConfigToAll(
                server, MarketSettings.server());
        MarketEngine.pushSnapshotToWatchers(server);
    }

    /**
     * How a dynamic stock is decided: the dynamicStocks records are authoritative (a
     * dynamic stock in the middle of a delisting still counts as dynamic and is handled
     * by the dedicated dynamicStocks delist/relist loop); the legacy DYN_ prefix stays
     * supported.
     */
    private static boolean isDynamic(MarketSavedData data, String code) {
        return code != null && (data.dynamicStocks.containsKey(code) || code.startsWith("DYN_"));
    }

    // ------------------------------------------------------------ candidate pool (config-driven)

    /** Built-in default candidate pool (used while the listing.candidates config is empty). */
    private static final List<String[]> DEFAULT_CANDIDATE_POOL =
            java.util.Arrays.asList(DEFAULT_CANDIDATES);

    /** Code set of the default pool (kept for the old reference: deciding that a code "could be a dynamic one"). */
    public static final java.util.Set<String> DEFAULT_CANDIDATE_CODES = defaultCandidateCodes();

    private static java.util.Set<String> defaultCandidateCodes() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String[] c : DEFAULT_CANDIDATES) {
            out.add(c[0]);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /** Current candidate pool: invalid rows are skipped, and one item can back only one stock. */
    static List<String[]> candidates(MarketSettings settings) {
        List<String> raw = settings == null ? null : settings.dynamicCandidates;
        int defaultLevel = settings == null ? 1 : settings.dynamicUnlockLevel;
        return candidates(raw, defaultLevel, ListingService::itemRegistered, activeStockItemIds());
    }

    /** Builds a candidate pool from raw rows; the arguments keep validation independently testable. */
    static List<String[]> candidates(java.util.Collection<String> raw, int defaultLevel,
                                     java.util.function.Predicate<String> itemAvailable,
                                     java.util.Set<String> boundItemIds) {
        List<String[]> parsed = new ArrayList<>();
        java.util.Set<String> seenCodes = new java.util.LinkedHashSet<>();
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw) {
                String[] candidate = parseCandidate(line, defaultLevel, itemAvailable);
                if (candidate == null || Stock.BUILTIN_CODES.contains(candidate[0])
                        || !seenCodes.add(candidate[0])) {
                    continue;
                }
                parsed.add(candidate);
                if (parsed.size() >= 64) {
                    break;
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed = new ArrayList<>(DEFAULT_CANDIDATE_POOL);
        }
        java.util.Set<String> usedItems = new java.util.LinkedHashSet<>();
        if (boundItemIds != null) {
            usedItems.addAll(boundItemIds);
        }
        List<String[]> out = new ArrayList<>();
        for (String[] candidate : parsed) {
            if (!usedItems.add(candidate[1])) {
                warnCandidateOnce("bound-item:" + candidate[0] + ":" + candidate[1],
                        "dynamic listing candidate skipped: item [{}] already backs another stock (code [{}])",
                        candidate[1], candidate[0]);
                continue;
            }
            out.add(candidate);
        }
        return out;
    }

    /** Checks candidate items against the live item registry. */
    private static boolean itemRegistered(String itemId) {
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
        return id != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id);
    }

    /** Returns item ids currently used by stocks on the active board. */
    private static java.util.Set<String> activeStockItemIds() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Stock stock : StockRegistry.server().getActiveStocks()) {
            if (stock.itemId() != null && !stock.itemId().isEmpty()) {
                out.add(stock.itemId());
            }
        }
        return out;
    }

    /** Checks whether another active stock already uses an item id. */
    static boolean itemBoundByOtherActiveStock(String itemId, String ownCode,
                                               java.util.Collection<Stock> activeStocks) {
        if (itemId == null || itemId.isEmpty() || activeStocks == null) {
            return false;
        }
        for (Stock stock : activeStocks) {
            if (stock != null && !stock.code().equals(ownCode)
                    && itemId.equals(stock.itemId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean itemBoundByOtherStock(String itemId, String ownCode) {
        return itemBoundByOtherActiveStock(itemId, ownCode, StockRegistry.server().getActiveStocks());
    }

    private static String builtinItemId(String code) {
        for (Stock stock : Stock.BUILTIN_TEMPLATES) {
            if (stock.code().equals(code)) {
                return stock.itemId();
            }
        }
        return null;
    }

    /** Parses one candidate entry: CODE|item id|display name|exchange points, with an optional fifth unlock-level field. */
    private static String[] parseCandidate(String raw, int defaultLevel,
                                           java.util.function.Predicate<String> itemAvailable) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] p = raw.split("\\|", -1);
        if (p.length != 4 && p.length != 5) {
            return null;
        }
        String code = p[0].trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9]{2,8}")) {
            return null;
        }
        String itemId = p[1].trim();
        net.minecraft.resources.ResourceLocation parsedItem =
                net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (itemId.isEmpty() || parsedItem == null) {
            return null;
        }
        itemId = parsedItem.toString();
        if (itemAvailable == null || !itemAvailable.test(itemId)) {
            warnCandidateOnce("item:" + code + ":" + itemId,
                    "dynamic listing candidate skipped: unknown item id [{}] in code [{}]", itemId, code);
            return null;
        }
        String name = p[2].trim();
        if (name.isEmpty()) {
            net.minecraft.world.item.Item item =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(parsedItem);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            }
            if (name == null || name.isBlank()) {
                name = itemId;
            }
        }
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        long rate = parseLong(p[3].trim(), -1L);
        if (rate <= 0L) {
            return null;
        }
        rate = Math.max(1L, Math.min(10_000L, rate));
        int level = defaultLevel;
        if (p.length >= 5 && !p[4].isBlank()) {
            level = parseInt(p[4].trim(), defaultLevel);
        }
        level = Math.max(1, Math.min(Stock.MAX_TIER, level));
        return new String[]{code, itemId, name, Long.toString(rate), Integer.toString(level)};
    }

    /**
     * Codes the custom-stock config currently claims (first pipe segment of each raw row, upper-cased).
     * Customs win board slots over same-code dynamic definitions, so those definitions must stay inert
     * while the claim exists: no threshold delist, no side effects on the custom stock's orders/shorts.
     */
    static java.util.Set<String> customClaimedCodes(MarketSettings settings) {
        return customClaimedCodes(settings == null ? null : settings.customStocks);
    }

    /** Raw-row variant, kept side-effect free for tests. */
    static java.util.Set<String> customClaimedCodes(java.util.Collection<String> rawCustomStocks) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (rawCustomStocks == null) {
            return out;
        }
        for (String line : rawCustomStocks) {
            if (line == null) {
                continue;
            }
            int bar = line.indexOf('|');
            String code = (bar < 0 ? line : line.substring(0, bar)).trim().toUpperCase(Locale.ROOT);
            if (code.matches("[A-Z0-9]{2,8}")) {
                out.add(code);
            }
        }
        return out;
    }

    private static long parseLong(String v, long fallback) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
