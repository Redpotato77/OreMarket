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

/** Pure arithmetic for the optional shared market fluctuation component. */
public final class MarketIndexNoise {
    /** Safety cap: the shared component cannot exceed half of the configured per-stock random fluctuation. */
    public static final double MAX_WEIGHT = 0.5;

    private MarketIndexNoise() {
    }

    /** Clamp dirty weights before they reach the price engine. */
    public static double clampWeight(double weight) {
        if (!Double.isFinite(weight) || weight <= 0.0) {
            return 0.0;
        }
        return Math.min(MAX_WEIGHT, weight);
    }

    /**
     * Calculate one shared noise value from a uniform random sample. Weight 0 returns exactly +0.0 and does
     * not consume a random sample in the caller, preserving the legacy price sequence when disabled.
     */
    public static double noise(double uniform, double fluctuation, double weight) {
        double w = clampWeight(weight);
        if (w == 0.0) {
            return 0.0;
        }
        double u = Double.isFinite(uniform) ? Math.max(0.0, Math.min(1.0, uniform)) : 0.5;
        double f = Double.isFinite(fluctuation) && fluctuation > 0.0 ? fluctuation : 0.0;
        double value = (u * 2.0 - 1.0) * f * w;
        return Double.isFinite(value) ? value : 0.0;
    }
}
