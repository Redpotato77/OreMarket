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
 * "Template declaration" for effect modules: declares only the type, the parameter's random range,
 * the per-module durations and the scope. The concrete values and the target stocks are drawn by the
 * server's random source when the event instance is spawned (see ActiveEvent#spawn).
 * Writing -1 as the duration means the module follows the event's overall lifetime; giving a separate
 * range means each effect module expires on its own.
 */
public final class EffectSpec {
    public final EffectType type;
    /** Random range for the parameter (applied after type.clampParam). */
    public final double min;
    public final double max;
    /** FEE only: true = overwrite the fee rate outright, false = add a delta on top of the default rate. */
    public final boolean absolute;
    /** TREND only: 0 = random direction, 1 = force up, -1 = force down. */
    public final int direction;
    /** This module's own duration range in ticks; -1/-1 = follow the event's overall lifetime. */
    public final int durMin;
    public final int durMax;
    /** true = applies to all stocks regardless of the template's target selection (e.g. a global fee). */
    public final boolean global;

    public EffectSpec(EffectType type, double min, double max, boolean absolute,
                      int direction, int durMin, int durMax, boolean global) {
        this.type = type;
        this.min = min;
        this.max = max;
        this.absolute = absolute;
        this.direction = direction;
        this.durMin = durMin;
        this.durMax = durMax;
        this.global = global;
    }

    /** Switch this module to its own independent timing. */
    public EffectSpec dur(int lo, int hi) {
        return new EffectSpec(type, min, max, absolute, direction, lo, hi, global);
    }

    /** Switch this module to apply to all stocks. */
    public EffectSpec glob() {
        return new EffectSpec(type, min, max, absolute, direction, durMin, durMax, true);
    }

    // ------------------------------------------------------------ Common factories

    public static EffectSpec price(double lo, double hi) {
        return new EffectSpec(EffectType.PRICE_MULT, lo, hi, false, 0, -1, -1, false);
    }

    public static EffectSpec volatility(double lo, double hi) {
        return new EffectSpec(EffectType.VOLATILITY, lo, hi, false, 0, -1, -1, false);
    }

    /** Trend modifier: pass dir = 0 for a random uptrend or downtrend. */
    public static EffectSpec trend(int dir) {
        return new EffectSpec(EffectType.TREND, 0, 0, false, dir, -1, -1, false);
    }

    public static EffectSpec reversion(double lo, double hi) {
        return new EffectSpec(EffectType.REVERSION, lo, hi, true, 0, -1, -1, false);
    }

    /** Additive fee modifier (stacked on the default 2%; negative values reduce it). */
    public static EffectSpec feeAdd(double lo, double hi) {
        return new EffectSpec(EffectType.FEE, lo, hi, false, 0, -1, -1, true);
    }

    /** Overriding fee modifier (sets the fee rate to the given value outright). */
    public static EffectSpec feeSet(double v) {
        return new EffectSpec(EffectType.FEE, v, v, true, 0, -1, -1, true);
    }

    public static EffectSpec shock(double lo, double hi) {
        return new EffectSpec(EffectType.SHOCK, lo, hi, false, 0, -1, -1, false);
    }

    /** Overrides the up/down price-limit band (0.05 to 0.25). */
    public static EffectSpec limit(double lo, double hi) {
        return new EffectSpec(EffectType.PRICE_LIMIT, lo, hi, true, 0, -1, -1, true);
    }

    /** Trading-halt module: only the duration matters (the 60-tick cap is guaranteed by EffectType.SUSPEND's range). */
    public static EffectSpec suspend(int lo, int hi) {
        return new EffectSpec(EffectType.SUSPEND, lo, hi, false, 0, lo, hi, false);
    }

    /** Instant-trigger module (forced buy / forced sell / Order Matching pause / dividend / stock split):
     *  the parameter is drawn randomly between lo and hi and applied once at the moment of the trigger. */
    public static EffectSpec instant(EffectType type, double lo, double hi) {
        return new EffectSpec(type, lo, hi, false, 0, -1, -1, false);
    }
}
