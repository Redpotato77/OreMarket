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

package cn.oremarket.market.event;

import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.MarketSettings;
import cn.oremarket.market.Stock;
import cn.oremarket.market.StockRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate the effect modules of every currently active event, keyed by STOCK CODE, into the set of quote parameters "usable this tick".
 * The server price engine and trade settlement both read this one instance, so players see exactly the same result within a tick.
 *
 * After stocks moved from an enum to a dynamic registry, no fixed 8-slot array indexed by ordinal is used here;
 * the aggregate is a Map of code -> Per, entries built only for active stocks, unknown codes read the default.
 *
 * Accumulation rules and the boundaries from requirement 8 (unchanged):
 * - volatility modifier: multiplied together, clamped to 0.2~3.0;
 * - mean-reversion modifier: the minimum across modules, clamped to 0~0.15;
 * - fee modifier: SET overrides + ADD stacks, finally clamped to 0~10%, never negative;
 * - trade-impact modifier: multiplied together, clamped to 0.5~3.0;
 * - price-limit modifier: signed increments over the default, clamped to 5%~25%;
 * - trend modifier: an existing forced direction is not overridden;
 * - price multiplier: each module individually respects the +-15% single-tick cap;
 * - trading halt: records the remaining ticks.
 */
public final class MarketModifiers {

    /** One stock's aggregated modifiers for this tick. */
    public static final class Per {
        /** Multiplier on the per-tick random volatility. */
        public double volMult = 1.0;
        /** Running product of this tick's price multipliers (already includes the +-15% single-tick cap). */
        public double priceMult = 1.0;
        /** Forced trend direction: 0 = none forced, +-1 = taken over by an event. */
        public int trendForce;
        /** Mean-reversion strength. */
        public double reversion;
        /** Single-tick price-limit width (decimal). */
        public double limitPct;
        /** Player-trade impact multiplier. */
        public double shockMult = 1.0;
        /** Effective commission rate (event modifiers included). */
        public double fee;
        /** Remaining halted ticks (0 = normal trading). */
        public int suspendTicks;
        /** Number of price-multiplier modules joined in the product this tick (>= 2 enables the multi-event clamping range). */
        public int priceMultCount;
        /** Whether fee / price-limit still have an "override value" pending (clamped together when collect finishes). */
        boolean feeOverridden;
        boolean revOverridden;
        double feeDelta;
        double limitDelta;
    }

    private final Map<String, Per> byCode = new HashMap<>();
    private final double baseFee;
    private final double baseReversion;
    private final double baseLimit;
    /** Read-only default for unknown codes (never inserted into byCode, so dirty data cannot touch delisted stocks). */
    private Per fallback;

    private MarketModifiers(double baseFee, double baseReversion, double baseLimit) {
        this.baseFee = baseFee;
        this.baseReversion = baseReversion;
        this.baseLimit = baseLimit;
    }

    private Per defaultsFor() {
        Per p = new Per();
        p.reversion = baseReversion;
        p.limitPct = baseLimit;
        p.fee = baseFee;
        return p;
    }

    /** Create default entries for all currently active stocks. */
    private void seedDefaults() {
        for (Stock s : StockRegistry.getActiveStocks()) {
            byCode.put(s.code(), defaultsFor());
        }
        fallback = defaultsFor();
    }

    /** Summarize the active events. baseFee/baseReversion/baseLimit come from the price mode and config. Fixed event cards from old saves (legacy templates) go through this same aggregation. */
    public static MarketModifiers collect(List<ActiveEvent> events, double baseFee,
                                         double baseReversion, double baseLimit) {
        MarketModifiers m = new MarketModifiers(baseFee, baseReversion, baseLimit);
        m.seedDefaults();
        if (events == null || events.isEmpty()) {
            m.finish();
            return m;
        }
        for (ActiveEvent e : events) {
            for (ActiveEvent.EffectModule mod : e.modules) {
                if (mod.expired()) {
                    continue;
                }
                if (mod.global) {
                    // Global-scope modules apply to every active stock
                    for (String code : m.byCode.keySet()) {
                        m.apply(mod, m.byCode.get(code));
                    }
                } else {
                    for (String code : mod.targets) {
                        Per p = m.byCode.get(code);
                        if (p != null) {
                            m.apply(mod, p);
                        }
                    }
                }
            }
        }
        m.finish();
        return m;
    }

    private void apply(ActiveEvent.EffectModule mod, Per p) {
        switch (mod.type) {
            case PRICE_MULT -> {
                // A price multiplier is a news shock, not a public countdown trade: EventEffectsService
                // realizes its cumulative duration at commit, before the announcement reaches players.
                // Keep the field/counter for old callers and old-save compatibility, but do not apply the
                // same module again on every later marketTick.
                p.priceMultCount++;
            }
            case VOLATILITY -> p.volMult *= mod.param;
            case TREND -> {
                if (p.trendForce == 0) {
                    p.trendForce = mod.direction;
                }
            }
            case REVERSION -> {
                if (!p.revOverridden) {
                    p.reversion = mod.param;
                    p.revOverridden = true;
                } else {
                    p.reversion = Math.min(p.reversion, mod.param);
                }
            }
            case FEE -> {
                if (mod.absoluteSet()) {
                    if (!p.feeOverridden) {
                        p.fee = mod.param;
                        p.feeOverridden = true;
                    }
                } else {
                    p.feeDelta += mod.param;
                }
            }
            case SHOCK -> p.shockMult *= mod.param;
            case PRICE_LIMIT -> {
                if (mod.absoluteSet()) {
                    p.limitDelta += mod.param - baseLimit;
                }
            }
            case SUSPEND -> p.suspendTicks = Math.max(p.suspendTicks, mod.ticksLeft);
        }
    }

    /**
     * Unified clamping (rule-by-rule identical to the old version).
     * Requirement 2, item 14: when one stock is hit by TWO OR MORE price-multiplier modules, the product is clamped into
     * the configured range eventEffectMultiplierCapMin~Max (default 0.5~2.0); single events are exempt and
     * keep the old +-50% clamping, so existing single-event numeric behavior is untouched.
     */
    private void finish() {
        MarketSettings ms = MarketSettings.current();
        for (Per p : byCode.values()) {
            p.volMult = clamp(p.volMult, 0.2, 3.0);
            p.shockMult = clamp(p.shockMult, 0.5, 3.0);
            if (p.priceMultCount >= 2) {
                p.priceMult = clamp(p.priceMult, ms.eventEffectCapMin, ms.eventEffectCapMax);
            } else {
                p.priceMult = clamp(p.priceMult, 0.5, 1.5);
            }
            p.reversion = clamp(p.reversion, 0.0, 0.15);
            p.limitPct = clamp(p.limitPct + p.limitDelta, 0.05, 0.25);
            p.fee = clamp(p.fee + p.feeDelta, 0.0, 0.10);
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------ Queries

    /** Query by code; unregistered (delisted / unknown) returns the default object instead of throwing. */
    public Per get(String code) {
        Per p = byCode.get(code);
        return p != null ? p : fallback;
    }

    public Per get(Stock s) {
        return s == null ? fallback : get(s.code());
    }

    /** Whether this stock is halted this tick. */
    public boolean suspended(String code) {
        return get(code).suspendTicks > 0;
    }

    public boolean suspended(Stock s) {
        return s != null && suspended(s.code());
    }
}
