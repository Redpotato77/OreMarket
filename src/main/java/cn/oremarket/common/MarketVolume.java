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

/**
 * Candlestick volume slot arithmetic (phase 2 of the light market-simulation plan; pure calculation, JDK-only).
 *
 * <p>[What counts as volume] ONLY fills that moved the system quote are booked into the candle: normal buys/sells
 * against the system, price-trigger limit fills, event FORCE_BUY / FORCE_SELL, short open/close while
 * shortAffectPrice is on, and player order-book fills while orderAffectPrice is on. Order-book fills that do not
 * rewrite the quote (the default) stay out of the bar - exactly the meaning finalized in the plan:
 * only real volume that moves the system price gets recorded into the candle.</p>
 *
 * <p>[Units] pure SHARE counts (never coin turnover). [Guards] every entry point sanitizes dirty input first:
 * negative / NaN / Infinity share counts are dropped, the accumulator saturates at {@link #MAX_VOLUME} so it can
 * never overflow into a negative double or an infinite value - the extreme-test requirement of the plan.</p>
 *
 */
public final class MarketVolume {

    /** Index of the volume slot inside a candle array {open, high, low, close, volume}. */
    public static final int VOLUME_SLOT = 4;
    /** Hard cap of one candle's accumulated share count: saturating, never wrapping negative. */
    public static final double MAX_VOLUME = 1.0e15;

    private MarketVolume() {
    }

    /** Dirty-value hygiene: negative / NaN / Infinity fall back to 0; anything above the cap clamps down. */
    public static double sanitize(double v) {
        if (!Double.isFinite(v) || v < 0.0) {
            return 0.0;
        }
        return v > MAX_VOLUME ? MAX_VOLUME : v;
    }

    /**
     * Accumulate one fill into the running volume.
     * [Worked example] add(120, 30) = 150; add(-5, 30) = 30 (dirty current repaired on the spot);
     * add(MAX_VOLUME, 1) = MAX_VOLUME (saturation, never above, never NaN).
     */
    public static double add(double current, long shares) {
        double c = sanitize(current);
        if (shares <= 0) {
            return c;             // non-positive / dirty fills never move the accumulator
        }
        double next = c + (double) shares;
        if (!Double.isFinite(next) || next > MAX_VOLUME) {
            return MAX_VOLUME;    // saturating: an extreme test flood cannot blow the value up
        }
        return next;
    }

    /** Whole-share display value (floored, sanitized): the UI and the packets all show through this one gate. */
    public static long roundShares(double v) {
        double s = sanitize(v);
        if (s >= (double) Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.floor(s);
    }

    /** Volume slot read with legacy support: candles shorter than five fields (old saves / old packets) have no volume - 0. */
    public static double of(double[] bar) {
        if (bar == null || bar.length <= VOLUME_SLOT) {
            return 0.0;
        }
        return sanitize(bar[VOLUME_SLOT]);
    }
}
