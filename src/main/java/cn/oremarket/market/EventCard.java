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

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The 12 event cards. Events only move stock prices, never the exchange rate.
 * percent is the cumulative price change over the whole event cycle (positive = bullish,
 * negative = bearish); the engine spreads it into a per-marketTick price multiplier.
 *
 * Change: the affected scope moved from EnumSet&lt;Stock&gt; to a set of stock
 * code strings; whole-market cards (rate hike / market frenzy) carry the allMarket flag, which
 * covers every active stock when matched.
 */
public enum EventCard {
    GREAT_VEIN(cn.oremarket.common.RawText.t("oremarket.text.0625"), cn.oremarket.common.RawText.t("oremarket.text.0626"), -25, 15, false, codes("IRON")),
    DIAMOND_FEVER(cn.oremarket.common.RawText.t("oremarket.text.0627"), cn.oremarket.common.RawText.t("oremarket.text.0628"), 40, 20, false, codes("DIAMOND")),
    REDSTONE_REVOLUTION(cn.oremarket.common.RawText.t("oremarket.text.0629"), cn.oremarket.common.RawText.t("oremarket.text.0630"), 30, 12, false, codes("REDSTONE")),
    COAL_EMBARGO(cn.oremarket.common.RawText.t("oremarket.text.0631"), cn.oremarket.common.RawText.t("oremarket.text.0632"), 35, 18, false, codes("COAL")),
    RATE_HIKE(cn.oremarket.common.RawText.t("oremarket.text.0633"), cn.oremarket.common.RawText.t("oremarket.text.0634"), -12, 10, true, codes()),
    MARKET_FRENZY(cn.oremarket.common.RawText.t("oremarket.text.0635"), cn.oremarket.common.RawText.t("oremarket.text.0636"), 15, 15, true, codes()),
    MINE_ACCIDENT(cn.oremarket.common.RawText.t("oremarket.text.0637"), cn.oremarket.common.RawText.t("oremarket.text.0638"), 22, 14, false, codes("COAL", "IRON", "GOLD")),
    LAPIS_SURPLUS(cn.oremarket.common.RawText.t("oremarket.text.0639"), cn.oremarket.common.RawText.t("oremarket.text.0640"), -30, 16, false, codes("LAPIS")),
    NETHERITE_ARMS(cn.oremarket.common.RawText.t("oremarket.text.0641"), cn.oremarket.common.RawText.t("oremarket.text.0642"), 30, 20, false, codes("NETHERITE")),
    COPPER_CRASH(cn.oremarket.common.RawText.t("oremarket.text.0643"), cn.oremarket.common.RawText.t("oremarket.text.0644"), -35, 15, false, codes("COPPER")),
    GOLD_RUSH(cn.oremarket.common.RawText.t("oremarket.text.0645"), cn.oremarket.common.RawText.t("oremarket.text.0646"), 28, 16, false, codes("GOLD")),
    SYNTHETIC_DIAMOND(cn.oremarket.common.RawText.t("oremarket.text.0647"), cn.oremarket.common.RawText.t("oremarket.text.0648"), -30, 18, false, codes("DIAMOND"));

    public static final EventCard[] ORDER = values();

    private final String name;
    private final String description;
    private final int percent;
    private final int durationTicks;
    private final boolean allMarket;
    private final Set<String> affectedCodes;

    EventCard(String name, String description, int percent, int durationTicks,
              boolean allMarket, Set<String> affectedCodes) {
        this.name = name;
        this.description = description;
        this.percent = percent;
        this.durationTicks = durationTicks;
        this.allMarket = allMarket;
        this.affectedCodes = affectedCodes;
    }

    private static Set<String> codes(String... cs) {
        Set<String> set = new LinkedHashSet<>();
        for (String c : cs) {
            set.add(c);
        }
        return set;
    }

    public String cardName() {
        return name;
    }

    public String description() {
        return description;
    }

    public int percent() {
        return percent;
    }

    public int durationTicks() {
        return durationTicks;
    }

    /** Whether this card affects the stock with the given code (whole-market cards cover all active stocks). */
    public boolean affects(String code) {
        return allMarket || (code != null && affectedCodes.contains(code));
    }

    /** The fixed set of target codes this card affects (empty for whole-market cards, which are decided by allMarket). */
    public Set<String> affectedCodes() {
        return affectedCodes;
    }

    public boolean isAllMarket() {
        return allMarket;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EventCard byId(String id) {
        for (EventCard c : ORDER) {
            if (c.id().equalsIgnoreCase(id)) {
                return c;
            }
        }
        return null;
    }

    /** Price multiplier for a single marketTick (intensityScale is decided by the event mode). */
    public double perTickMultiplier(int duration, double intensityScale) {
        return 1.0 + (percent * intensityScale / 100.0) / Math.max(1, duration);
    }
}
