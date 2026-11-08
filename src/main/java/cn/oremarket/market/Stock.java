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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stock definition.
 *
 * A stock's identity is fully determined by {@code code} (an uppercase-letter + digit string),
 * no longer by an enum ordinal. The 8 built-ins are fixed templates ({@link #BUILTIN_TEMPLATE_COUNT} constants);
 * custom stocks are appended dynamically by the server after parsing the customStocks config, all through one {@link StockRegistry}.
 *
 * The runtime basePrice / exchangeRate / unlockLevel always come from the PARSED instances in the registry;
 * the constant objects are only default templates for built-in stocks and take no part in real trading numbers.
 */
public final class Stock {

    /** Number of built-in stock templates (fixed 8; the registry seeds them first as defaults during static init). */
    public static final int BUILTIN_TEMPLATE_COUNT = 8;

    /** Top tier; level 1 is unlocked from the start (threshold always 0), no peak required. */
    public static final int MAX_TIER = 4;

    // ------- Built-in stock default templates (fallback values when the config is unreadable; real trading numbers are rebuilt by the registry from config) -------
    public static final Stock COAL = template("COAL", "minecraft:coal", cn.oremarket.common.RawText.t("oremarket.text.0052"), 8, 3, 1);
    public static final Stock COPPER = template("COPPER", "minecraft:copper_ingot", cn.oremarket.common.RawText.t("oremarket.text.0073"), 12, 4, 1);
    public static final Stock IRON = template("IRON", "minecraft:iron_ingot", cn.oremarket.common.RawText.t("oremarket.text.0072"), 18, 8, 2);
    public static final Stock GOLD = template("GOLD", "minecraft:gold_ingot", cn.oremarket.common.RawText.t("oremarket.text.0070"), 35, 15, 2);
    public static final Stock REDSTONE = template("REDSTONE", "minecraft:redstone", cn.oremarket.common.RawText.t("oremarket.text.0063"), 6, 2, 1);
    public static final Stock LAPIS = template("LAPIS", "minecraft:lapis_lazuli", cn.oremarket.common.RawText.t("oremarket.text.0124"), 10, 3, 1);
    public static final Stock DIAMOND = template("DIAMOND", "minecraft:diamond", cn.oremarket.common.RawText.t("oremarket.text.0071"), 70, 40, 3);
    public static final Stock NETHERITE = template("NETHERITE", "minecraft:netherite_ingot", cn.oremarket.common.RawText.t("oremarket.text.0233"), 160, 120, 4);

    /** The fixed order of built-in stock templates (migration mapping 0..7 uses exactly this order; registry defaults are arranged the same way). */
    public static final List<Stock> BUILTIN_TEMPLATES = List.of(
            COAL, COPPER, IRON, GOLD, REDSTONE, LAPIS, DIAMOND, NETHERITE);

    /** The fixed order of built-in stock codes (migration and legacy code index into it). */
    public static final List<String> BUILTIN_CODES = builtinCodes();

    private static List<String> builtinCodes() {
        List<String> codes = new ArrayList<>();
        for (Stock s : BUILTIN_TEMPLATES) {
            codes.add(s.code);
        }
        return List.copyOf(codes);
    }

    private final String code;
    private final String itemId;
    private final String displayName;
    private final double basePrice;
    private final long exchangeRate;
    private final int unlockLevel;
    private final boolean retired;

    private Stock(String code, String itemId, String displayName, double basePrice,
                  long exchangeRate, int unlockLevel, boolean retired) {
        this.code = code;
        this.itemId = itemId;
        this.displayName = displayName;
        this.basePrice = basePrice;
        this.exchangeRate = exchangeRate;
        this.unlockLevel = Math.max(1, Math.min(MAX_TIER, unlockLevel));
        this.retired = retired;
    }

    /** Built-in stock default templates (for fallbacks and constant references only). */
    private static Stock template(String code, String itemId, String displayName,
                                  double basePrice, long exchangeRate, int unlockLevel) {
        return new Stock(code, itemId, displayName, basePrice, exchangeRate, unlockLevel, false);
    }

    /** Built-in stocks as parsed from config by the registry (base/rate/level use config values; retired=false). */
    static Stock builtinResolved(String code, String itemId, String displayName,
                                 double basePrice, long exchangeRate, int unlockLevel) {
        return new Stock(code, itemId, displayName, basePrice, exchangeRate, unlockLevel, false);
    }

    /** Custom stocks. */
    static Stock custom(String code, String itemId, String displayName, double basePrice,
                        long exchangeRate, int unlockLevel) {
        return new Stock(code, itemId, displayName, basePrice, exchangeRate, unlockLevel, false);
    }

    /** Delisted copy: keeps every data field but sets retired (holdings and candles stay in the save; the UI hides it and it joins no quotes or events). */
    Stock retiredCopy() {
        return new Stock(code, itemId, displayName, basePrice, exchangeRate, unlockLevel, true);
    }

    public String code() {
        return code;
    }

    public String itemId() {
        return itemId;
    }

    public String displayName() {
        return displayName;
    }

    public double basePrice() {
        return basePrice;
    }

    public long exchangeRate() {
        return exchangeRate;
    }

    /** Unlock level 1~4 (the stock list and the exchange rows share this one). */
    public int tier() {
        return unlockLevel;
    }

    public int unlockLevel() {
        return unlockLevel;
    }

    public boolean retired() {
        return retired;
    }

    /** Ore-coin peak needed to unlock this level: reads the realtime config (level 1 is always 0, i.e. unlocked from the start). */
    public long tierUnlockPeak() {
        return MarketSettings.current().levelThreshold(unlockLevel);
    }

    /** The parsed item; null when the item id is invalid or not in the registry (callers already null-guard). */
    public Item item() {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) {
            return null;
        }
        Item it = BuiltInRegistries.ITEM.get(id);
        return it == null ? null : it;
    }

    /** Display-name fallback: when localization is empty, the item id string itself (already handled at parse time; this is one more safety layer). */
    public String displayNameOrFallback() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        Item it = item();
        if (it != null) {
            String loc = new ItemStack(it).getHoverName().getString();
            if (loc != null && !loc.isEmpty()) {
                return loc;
            }
        }
        return itemId;
    }

    /** Unified unlock test: display and permissions always use the all-time highestLevel; once unlocked, never revoked. */
    public static boolean unlockedByLevel(int highestLevel, Stock stock) {
        return stock != null && highestLevel >= stock.unlockLevel;
    }

    /** Compute the realtime level 1~4 from the peak balance (thresholds from the realtime config; judgement only, never display). */
    public static int tierForPeak(long peak) {
        return MarketSettings.current().levelForPeak(peak);
    }

    /** Lowercase English id (legacy compatibility; not part of identity). */
    public String id() {
        return code.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "Stock(" + code + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Stock s && s.code.equals(code);
    }

    @Override
    public int hashCode() {
        return code.hashCode();
    }
}
