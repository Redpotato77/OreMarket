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

import cn.oremarket.market.MarketSettings;
import net.minecraft.network.chat.Component;

/**
 * Single conversion entry for remaining time.
 *
 * Rules (all in marketTick counts; 1 marketTick = marketTickInterval game ticks):
 * - remaining seconds = floor(remaining marketTicks x marketTickInterval / 20);
 * - total minutes     = floor(total marketTicks x marketTickInterval / 1200);
 * - card display      = under 60 s -> "N seconds left"; 60 s or more -> "N minutes left" (N = floor(sec/60), minimum 1).
 * The two displays always agree: both derive from the same remainingMarketTicks field; computing them separately is forbidden.
 */
public final class TimeFormatter {

    /** Fallback interval when the config is not synced yet (same as MarketSettings' default: 60 game ticks = 3 s). */
    public static final int DEFAULT_INTERVAL = 60;

    private TimeFormatter() {
    }

    /** The client config's current marketTick interval (default 60 before a snapshot is ready; never dereference null). */
    public static int interval() {
        MarketSettings ms = MarketSettings.client();
        return ms == null ? DEFAULT_INTERVAL : Math.max(1, ms.marketTickInterval);
    }

    /** Remaining marketTicks -> remaining seconds (floor, non-negative). */
    public static long remainingSeconds(long remainingMarketTicks, int marketTickInterval) {
        long t = Math.max(0, remainingMarketTicks);
        long iv = Math.max(1, marketTickInterval);
        return t * iv / 20L;
    }

    /** Total marketTicks -> total minutes (floor, non-negative; under a minute shows 0). */
    public static long totalMinutes(long totalMarketTicks, int marketTickInterval) {
        long t = Math.max(0, totalMarketTicks);
        long iv = Math.max(1, marketTickInterval);
        return t * iv / 1200L;
    }

    /** Remaining seconds -> minutes (floor, minimum 1; at 0 s the display stays on the second-based wording instead). */
    private static long minutesOf(long seconds) {
        return Math.max(1, seconds / 60L);
    }

    /**
     * Remaining-time text for line 2 of the event card: "N seconds left" below 60 s, otherwise "N minutes left".
     * The tooltip takes its seconds figure from here too (remainingSeconds), guaranteeing both use the same formula.
     */
    public static String formatRemainingTime(long remainingMarketTicks, int marketTickInterval) {
        long secs = remainingSeconds(remainingMarketTicks, marketTickInterval);
        return secs < 60L
                ? formatSeconds(secs)
                : formatMinutes(minutesOf(secs));
    }

    /** "N seconds left". */
    public static String formatSeconds(long seconds) {
        return keyText("oremarket.event.card.remaining_seconds", String.valueOf(Math.max(0, seconds)));
    }

    /** "N minutes left" (N >= 1). */
    public static String formatMinutes(long minutes) {
        return keyText("oremarket.event.card.remaining_minutes",
                String.valueOf(Math.max(1, minutes)));
    }

    /** language key -> current-language text (same rules as MarketScreen.trKey, for reuse by static utilities). */
    private static String keyText(String key, Object... args) {
        if (key == null) {
            return "";
        }
        String out;
        try {
            out = Component.translatable(key, args).getString();
        } catch (RuntimeException e) {
            out = key;
        }
        if (out != null && !out.isEmpty() && !out.equals(key)) {
            return out;
        }
        String fb = L.tr(key);
        return fb == null || fb.isEmpty() ? key : fb;
    }
}
