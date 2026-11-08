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
import cn.oremarket.market.MarketSettings;

/**
 * Player-facing time display conversion: the code still counts in marketTick internally,
 * but every piece of text is shown as seconds / minutes / hours.
 *
 * Conversion ratios come **entirely from the config** (category five):
 *  1 marketTick = marketTickInterval game ticks = marketTickInterval / 20 seconds;
 *  1 Trading Day = tradingDayTicks / marketTickInterval marketTicks.
 * With the default marketTickInterval = 60, 1 marketTick = 3 seconds and
 * 1 Trading Day = 400 marketTicks.
 * Change the interval and every string here adapts automatically; no code needs touching.
 */
public final class MarketTime {

    private MarketTime() {
    }

    /** How many game ticks one marketTick equals (the config is read live). */
    public static int gameTicksPerMarket() {
        return MarketSettings.current().marketTickInterval;
    }

    /** How many seconds one marketTick equals (the config is read live). */
    public static double secondsPerMarket() {
        return MarketSettings.current().marketTicksToSeconds(1);
    }

    /** How many marketTicks make up one Trading Day (the config is read live). */
    public static int marketPerTradeDay() {
        return MarketSettings.current().marketTicksPerTradeDay();
    }

    /**
     * Renders a marketTick count in plain words: under a minute shows "X seconds",
     * otherwise "X minutes" (rounded, at least 1 minute). Event card countdowns,
     * trading-halt notices and every other player-facing duration go through here.
     */
    public static String human(long marketTicks) {
        long t = Math.max(0, marketTicks);
        long seconds = Math.round(MarketSettings.current().marketTicksToSeconds(t));
        if (seconds < 60) {
            return RawText.f("oremarket.unit.seconds", seconds);
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return RawText.f("oremarket.unit.minutes", minutes);
        }
        return RawText.f("oremarket.unit.hours", Math.round(minutes / 60.0));
    }

    /** Renders a plain seconds count in human words (Event durations in the config are
     * given in seconds and go through here for display as well). */
    public static String seconds(long seconds) {
        long s = Math.max(0, seconds);
        if (s < 60) {
            return RawText.f("oremarket.unit.seconds", s);
        }
        long m = Math.round(s / 60.0);
        if (m < 60) {
            return RawText.f("oremarket.unit.minutes", m);
        }
        return RawText.f("oremarket.unit.hours", Math.round(m / 60.0));
    }

    /** Converts seconds into marketTick (ceil, minimum 1); the only conversion entry point
     * for the Event duration config. */
    public static int toMarketTicks(double seconds) {
        return MarketSettings.current().secondsToMarketTicks(seconds);
    }

    /** Renders a Trading Day count in human words. */
    public static String days(long tradeDays) {
        long t = Math.max(0, tradeDays);
        return RawText.f("oremarket.unit.days", t);
    }
}
