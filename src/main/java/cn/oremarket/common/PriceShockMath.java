/*
 * oremarket - Minecraft Forge Mod
 * Copyright (C) 2026 Redpotato77
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package cn.oremarket.common;

import java.util.Locale;

/** Shared finite-value math for event price shocks and their player-facing text. */
public final class PriceShockMath {
    private static final double DISPLAY_PERCENT_LIMIT = 999.9;
    private static final double DISPLAY_FRACTION_LIMIT = DISPLAY_PERCENT_LIMIT / 100.0;

    private PriceShockMath() {
    }

    /** Apply a repeated multiplier in log space and clamp the result to the supplied price band. */
    public static double apply(double price, double perTick, int duration,
                               double floor, double ceiling) {
        if (!(price > 0.0) || !Double.isFinite(price)
                || !(perTick > 0.0) || !Double.isFinite(perTick)
                || duration <= 0 || !(floor > 0.0) || !Double.isFinite(floor)
                || !(ceiling >= floor) || !Double.isFinite(ceiling)) {
            return price;
        }
        double logTarget = Math.log(price) + Math.log(perTick) * (double) duration;
        double logFloor = Math.log(floor);
        double logCeiling = Math.log(ceiling);
        if (!Double.isFinite(logTarget)) {
            return logTarget > 0.0 ? ceiling : floor;
        }
        if (logTarget <= logFloor) {
            return floor;
        }
        if (logTarget >= logCeiling) {
            return ceiling;
        }
        double result = Math.exp(logTarget);
        return Double.isFinite(result) ? result : (logTarget > 0.0 ? ceiling : floor);
    }

    /** Whether a realized change is too large to present as a meaningful percentage. */
    public static boolean exceedsDisplayRange(double fraction) {
        return Double.isFinite(fraction) && fraction > DISPLAY_FRACTION_LIMIT;
    }

    /**
     * Format one already-realized fractional change (0.15 = +15%). An empty string is returned when
     * the move is too large for a meaningful percentage: the caller must then switch to the
     * "cumulative gain too large" wording - a fake capped number is never emitted.
     */
    public static String percentText(double fraction) {
        if (!Double.isFinite(fraction)) {
            return "+0.0%";
        }
        if (exceedsDisplayRange(fraction)) {
            return "";
        }
        double percent = Math.max(-100.0, fraction * 100.0);
        return String.format(Locale.ROOT, "%+.1f%%", percent);
    }

    /**
     * Nominal cumulative percentage for an old event without a realized value: the per-tick
     * multiplier compounded over the duration. Same empty-string contract as
     * {@link #percentText(double)} - off-the-chart compounding returns "" instead of a sentinel number.
     */
    public static String cumulativePercentText(double perTick, int duration) {
        if (!(perTick > 0.0) || !Double.isFinite(perTick) || duration <= 0) {
            return "+0.0%";
        }
        double logFactor = Math.log(perTick) * (double) duration;
        double limitLog = Math.log1p(DISPLAY_FRACTION_LIMIT);
        if (!Double.isFinite(logFactor)) {
            return logFactor < 0.0 ? "-100.0%" : "";
        }
        if (logFactor > limitLog) {
            return "";
        }
        double percent = Math.max(-100.0, Math.expm1(logFactor) * 100.0);
        if (!Double.isFinite(percent)) {
            return "";
        }
        return String.format(Locale.ROOT, "%+.1f%%", percent);
    }
}
