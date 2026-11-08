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
 * The eight event effect types. The min/max here are only SAFETY RAILS against runaway values (blocking NaN /
 * infinity / extremes); the real value ranges come from config category nine (eventPriceMultiplierMin/Max,
 * eventVolatilityMin/Max, eventMeanReversionMin/Max, eventFeeMin/Max, eventTradeImpactMin/Max, eventPriceLimitMin/Max):
 * event spawn draws the parameter first, applies the safety rails, then clamps into the config range (ActiveEvent#spawn).
 * abbr is the prefix of the UI effect shorthand ('price x0.9 vol x1.5'); the text is assembled server-side and shipped
 * down - the client never participates in effect math.
 */
public enum EffectType {
    /** Per-marketTick multiplier applied to the target stock's price. */
    PRICE_MULT(0.01, 10.0),
    /** Multiplier on the per-tick random volatility amplitude. */
    VOLATILITY(0.0, 10.0),
    /** Force-overrides the trend direction (dir is -1/1 and skips the min/max clamping). */
    TREND(-1, 1),
    /** Temporarily rewrites the mean-reversion strength (absolute value; 0 = completely free drift). */
    REVERSION(0.0, 1.0),
    /** Fee modifier: ADD raises or lowers the default rate, SET overrides it outright. */
    FEE(-1.0, 1.0),
    /** Multiplier on player-trade price impact. */
    SHOCK(0.0, 10.0),
    /** Per-tick price-limit width (absolute value; the range is set by config eventPriceLimitMin/Max). */
    PRICE_LIMIT(0.001, 1.0),
    /** Trading halt: duration (the cap comes from config eventHaltMaxSeconds converted to marketTicks). */
    SUSPEND(1, 1_000_000),
    // ---- Trigger-once effects: run exactly once at the moment of event trigger, never aggregated into the price engine.
    // Always append at the END of the enum: save NBT stores the ordinal, so the order of the first 8 entries can never change.
    /** Forced Buy: the system market-buys param shares of the target stock to push the price up (runs once at trigger). */
    FORCE_BUY(1, 100000),
    /** Forced Sell: the system market-sells param shares of the target stock to drag the price down (runs once at trigger). */
    FORCE_SELL(1, 100000),
    /** Liquidity Drain: the target stock's order matching pauses for ticksLeft marketTicks (checked in matching, not aggregated into price). */
    LIQUIDITY_DRY(1, 1_000_000),
    /** Dividend: pays param ore coins per held share to position holders (system mints them), and the stock price is force ex-dividended by param (runs once at trigger). */
    DIVIDEND(0.0001, 1000.0),
    /** Stock Split: price / 2, holdings x 2 (floating-point value conserved), short positions adjust in lockstep; runs once at trigger; banned on halted stocks. */
    SPLIT(2, 2);

    public static final EffectType[] ORDER = values();

    public final double min;
    public final double max;

    EffectType(double min, double max) {
        this.min = min;
        this.max = max;
    }

    /** Clamp a parameter into this type's allowed range. */
    public double clampParam(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return (min + max) / 2.0;
        }
        return Math.max(min, Math.min(max, v));
    }

    public static EffectType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < ORDER.length ? ORDER[ordinal] : null;
    }
}
