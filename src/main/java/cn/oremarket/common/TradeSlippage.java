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
 * Approximate slippage for trades against the system (phase 1 of the light market-simulation plan; pure
 * calculation, JDK-only, shared verbatim by TradeService, the client estimate and the offline harness).
 *
 * <p>[Business rule, finalized] a fill pays HALF of its own price impact at the moment of execution and the
 * other half still lands afterwards through the untouched {@code applyShock}:
 * {@code slip = 0.5 x tradeImpactFactor x shockMult x sqrt(qty)}. The TOTAL quote move of a trade therefore
 * stays bit-for-bit the same as before - slippage only moves part of that already-existing impact into the
 * fill price, it never amplifies the impact and never re-derives the impact model (keep the existing applyShock path,
 * but avoid double-counting the price impact). Small orders (the default factor 0.004) barely notice; 1000-share orders fill
 * about 6.3% worse than the quote.</p>
 *
 * <p>[Switch] {@code tradeSlippage} defaults to true: with it off every formula below degenerates to the
 * old behavior (slip = 0, exec price = quote), so old saves and old expectations keep working unchanged.</p>
 *
 * <p>[Guards] dirty inputs (NaN / Infinity / non-positive price / negative qty) degrade to the safe value
 * and never throw; the fraction is hard-capped at {@link #MAX_SLIP_FRACTION}; the one-click-buy solver runs a
 * BOUNDED fixed-point + step-back, so no input can loop unbounded.</p>
 */
public final class TradeSlippage {

    /** Hard cap of the fill-side fraction: even a 1M-share order never pays beyond +25% / -25% of the quote. */
    public static final double MAX_SLIP_FRACTION = 0.25;
    /** Upper bound of the step-back loop in the one-click solver (fixed, never unbounded). */
    public static final int MAX_STEP_BACK = 64;
    /** A trade pays half of its own price impact immediately; the other half is applied by the existing shock path. */
    private static final double IMMEDIATE_IMPACT_SHARE = 0.5;
    /** A sell may not push its fill price below five percent of the quote. */
    private static final double MAX_SELL_SLIP_FRACTION = 0.95;
    /** Conservative number of shrink-only fixed-point passes before exact step-back. */
    private static final int FIXED_POINT_PASSES = 4;

    private TradeSlippage() {
    }

    /**
     * [Purpose] the fraction of the quote a system trade pays AGAINST itself at fill time.
     * [Worked example] default factor 0.004, shockMult 1: qty 100 -> 0.5 x 0.004 x 10 = 2.0%;
     * qty 1000 -> 0.5 x 0.004 x 31.6 = 6.3%; qty 1_000_000 -> 2.0 (clamped to 0.25).
     * Any dirty factor / non-positive shockMult / non-positive qty returns 0.0 (= old behavior).
     */
    public static double slipFraction(double impactFactor, double shockMult, int qty) {
        if (qty <= 0) {
            return 0.0;
        }
        if (!Double.isFinite(impactFactor) || impactFactor <= 0.0) {
            return 0.0;
        }
        if (!Double.isFinite(shockMult) || shockMult <= 0.0) {
            return 0.0;
        }
        double fraction = IMMEDIATE_IMPACT_SHARE * impactFactor * shockMult * Math.sqrt((double) qty);
        if (!Double.isFinite(fraction) || fraction <= 0.0) {
            return 0.0;
        }
        return Math.min(MAX_SLIP_FRACTION, fraction);
    }

    /**
     * [Purpose] the execution price of a buy/sell of {@code qty} against {@code quotePrice}.
     * [Worked example] quote 10.00, slip 0.02: buy fills at 10.20, sell at 9.80.
     * slip <= 0 or a dirty quote returns the quote verbatim; the result is re-verified positive + finite,
     * otherwise the quote again (a 0 / NaN exec price could never be settled against).
     */
    public static double execPrice(double quotePrice, double slip, boolean buy) {
        if (!(quotePrice > 0.0) || !Double.isFinite(quotePrice)) {
            return quotePrice;
        }
        if (!Double.isFinite(slip) || slip <= 0.0) {
            return quotePrice;
        }
        double sellSlip = Math.min(slip, MAX_SELL_SLIP_FRACTION);
        double executionPrice = buy
                ? quotePrice * (1.0 + slip)
                : quotePrice * (1.0 - sellSlip);
        if (!(executionPrice > 0.0) || !Double.isFinite(executionPrice)) {
            return quotePrice;
        }
        return executionPrice;
    }

    /**
     * [Purpose] one-click-buy sizing under slippage: the largest n with
     * {@code Fees.buyCost(execPrice(n), n, totalRate) <= balance}, capped by maxTradeQuantity.
     *
     * <p>[Why fixed-point] slip grows with sqrt(n) while the affordable n shrinks with slip - a monotone
     * pair, so four shrink-only passes converge tightly; afterwards a BOUNDED step-back (≤ {@link #MAX_STEP_BACK}
     * iterations) re-checks with the server's verbatim ceil rounding, mirroring the safety loop of
     * ShortPoolCalculator.oneClickShares. The box number can therefore always really be bought.</p>
     *
     * [Worked example] balance 10000, price 10, rate 0.5%, factor 0.004: plain 995 -> slip(995) 6.3% ->
     * 933 -> slip 6.1% -> 935 (never grows back) -> step-back checks cost(935) at its own slip.
     */
    public static int affordableBuyShares(long balance, double price, double totalRate,
                                          double impactFactor, double shockMult, long cap) {
        if (balance <= 0 || cap <= 0) {
            return 0;
        }
        if (!(price > 0.0) || !Double.isFinite(price)) {
            return 0;
        }
        double rate = Double.isFinite(totalRate) && totalRate > 0.0 ? totalRate : 0.0;
        double plainUnit = price * (1.0 + rate);
        if (!(plainUnit > 0.0) || !Double.isFinite(plainUnit)) {
            return 0;
        }
        long shareEstimate = (long) Math.floor((double) balance / plainUnit);
        shareEstimate = Math.min(shareEstimate, Math.min(cap, (long) Integer.MAX_VALUE));
        // shrink-only fixed point: bigger slip can only cut the estimate, never raise it
        for (int passIndex = 0; passIndex < FIXED_POINT_PASSES && shareEstimate > 0; passIndex++) {
            double slip = slipFraction(impactFactor, shockMult, (int) shareEstimate);
            if (slip <= 0.0) {
                break;
            }
            double slippedUnit = price * (1.0 + slip) * (1.0 + rate);
            if (!(slippedUnit > 0.0) || !Double.isFinite(slippedUnit)) {
                break;
            }
            long nextEstimate = (long) Math.floor((double) balance / slippedUnit);
            nextEstimate = Math.min(nextEstimate, Math.min(cap, (long) Integer.MAX_VALUE));
            if (nextEstimate >= shareEstimate) {
                break;                          // converged (or the floor already stable)
            }
            shareEstimate = nextEstimate;
        }
        // final step-back with the exact server deduction (ceiled) - bounded by design
        int stepBackCount = 0;
        while (shareEstimate > 0 && stepBackCount++ < MAX_STEP_BACK
                && Fees.buyCost(execPrice(price, slipFraction(impactFactor, shockMult,
                                (int) shareEstimate), true),
                        (int) shareEstimate, rate) > balance) {
            shareEstimate--;
        }
        return (int) Math.max(0L, shareEstimate);
    }
}
