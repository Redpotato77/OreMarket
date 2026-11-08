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
 * The pure calculation rules of the short pool.
 *
 * <p>[Purpose] Shorting is no longer limited by a personal 'short quota'; a WHOLE-SERVER PER-STOCK POOL is used:
 * the pool is measured in PURE SHARES, its cap derived by inverse scaling from the stock's OWN base price
 * (stocks are randomly listed with different base prices - never a single shared reference price, and never
 * restate the pool as a coin amount); each cross-day settlement refills 10% of the maximum shares;
 * the player's wallet balance decides how many shares can be borrowed.</p>
 *
 * <p>This class is shared, SAME BYTECODE, by the server (listing, daily refill, open-position validation),
 * the offline harness and the {@code /oremarket testshort} sandbox command, so formulas can never diverge.
 * Every method guards dirty inputs: NaN / Infinity / negatives fall back to safe values and never throw.
 */
public final class ShortPoolCalculator {

    /** Daily refill ratio (10%, requirement-finalized; appears only in the cross-day settlement call). */
    public static final double DAILY_REFILL_RATE = 0.10;
    /** Remaining pool ratio below which a newly opened short pays the scarcity rate. */
    public static final double SCARCITY_LEFT_RATIO = 0.20;
    /** Hard safety ceiling shared with the shortDailyInterest config range. */
    public static final double MAX_DAILY_RATE = 0.20;

    private ShortPoolCalculator() {
    }

    /**
     * [Purpose] compute a stock's dedicated short-pool cap (pure shares) at LISTING time from its own base price.
     * The base price is the only factor; the realtime price is never mixed in.
     * [Worked example] defaultBaseShares = 50000:
     * base price 5 -> floor(50000 / 5) = 10000 shares;
     * base price 0 / dirty -> treated as 1.0 against division by zero -> 50000 shares;
     * base price 1000 -> floor(50000 / 1000) = 50 -> below the floor, clamped up to absoluteMinShares = 100;
     * base price 0.1 -> 500000 -> above the hard cap, clamped down to absoluteMaxShares = 100000.
     */
    public static int maxSharesFor(double basePrice, int defaultBaseShares,
                                   int absoluteMaxShares, int absoluteMinShares) {
        // 1. Guard division by 0 / NaN / negative price: a dirty base price is treated as 1.0 and never throws
        double bp = Double.isFinite(basePrice) && basePrice > 0.0 ? basePrice : 1.0;
        int def = Math.max(1, defaultBaseShares);
        // (original defect: the cap was punched through by defaultBaseShares). AbsoluteMaxShares is the
        // admin's safety valve and MUST apply STRICTLY - if operations deliberately set the hard cap below the default
        // base (e.g. default 50000 but hard cap 100), the old code raised the cap back to 50000 and the pool could never clamp. The hard cap is now taken from the config as written.
        int max = Math.max(1, absoluteMaxShares);
        int min = Math.max(1, absoluteMinShares);
        if (min > max) {
            // Absurd config (floor above the hard cap): the hard cap wins; the floor is clamped under it and the range collapses to a single point.
            min = max;
        }
        // 2. Core formula: inverse scaling from this stock's own base price, in pure shares
        double raw = (double) def / bp;
        if (!Double.isFinite(raw) || raw < 0.0) {
            raw = 0.0;
        }
        // 3. Floor to integer + forced lower/upper clamping (clamp into the long domain first to stop (int) overflow wraparound)
        long calc = raw >= (double) Integer.MAX_VALUE ? Integer.MAX_VALUE : (long) Math.floor(raw);
        if (calc > max) {
            calc = max;
        }
        if (calc < min) {
            calc = min;
        }
        return (int) calc;
    }

    /**
     * [Purpose] the per-stock daily refill amount (whole shares) in the cross-day settlement.
     * [Worked example] max = 105 -> floor(105 x 10%) = 10 shares/day;
     * left = 100, max = 105 -> min(100 + 10, 105) = 105; a full pool clamps at the top and never overflows.
     */
    public static int refill(int left, int max) {
        int m = Math.max(0, max);
        if (m == 0) {
            return 0;
        }
        int l = Math.min(Math.max(0, left), m);          // Dirty leftovers (negative / above cap) are pulled back into the legal domain on the spot
        long add = (long) Math.floor((double) m * DAILY_REFILL_RATE);
        long v = l + add;
        return v > (long) m ? m : (int) v;
    }

    /**
     * [Purpose] one-click short sizing: OPEN AS MANY SHARES AS POSSIBLE, double-capped by 'available wallet balance' and
     * 'this stock's remaining pool', floored.
     *
     * ({@code balance x 0.5}), so when 'the coal pool still has 6250 shares and the wallet clearly covers it'
     * one-click filled only 3000-something shares - yet the player was asking precisely for 'if the money covers it,
     * take the whole pool'. Now the base = the FULL available balance (what callers pass as {@code balance} is
     * {@code freeCoins()}; order locks and used margin are already deducted outside it), and only three caps remain:
     * (1) the wallet can afford 'margin + open tax/fee'; (2) never above the stock's remaining pool; (3) never above
     * the per-order quantity cap. Server {@code ShortService.open} re-validates with the same formula inside the same
     * critical section, so the number filled into the input box can always actually be opened.
     *
     * <p>[Worked example] balance 100000, price 10, margin ratio 0.5, combined fee+tax 0.5%:
     * per-share cost = 10 x 0.5 + 10 x 0.005 = 5.05; fundsMax = floor(100000 / 5.05) = 19801;
     * pool left 6250 -> final 6250 shares (the whole pool); pool left 50000 -> final 19801 shares (money exhausted).
     *
     * @param balance      available balance (= wallet balance - order/margin locks; callers pass freeCoins())
     * @param price        current price
     * @param marginRatio  margin ratio (config shortMarginRatio)
     * @param combinedFeeTaxRate open fee+tax total (commission + destroy tax, {@code Fees.combined})
     * @param poolLeft     remaining shares in this stock's short pool
     * @param maxPerOrder  per-order quantity cap (config maxTradeQuantity)
     * @return shares safe to fill with one click; any dirty input / insufficient funds / empty pool returns 0 (the caller intercepts locally and never sends an empty packet).
     */
    public static int oneClickShares(double balance, double price, double marginRatio,
                                     double combinedFeeTaxRate, int poolLeft, int maxPerOrder) {
        if (poolLeft <= 0) {
            return 0;                       // Pool already empty
        }
        if (!(balance > 0.0) || !Double.isFinite(balance)
                || !(price > 0.0) || !Double.isFinite(price)) {
            return 0;                       // Dirty balance / dirty quote
        }
        double ratio = marginRatio > 0.0 && Double.isFinite(marginRatio) ? marginRatio : 0.0;
        double fee = combinedFeeTaxRate > 0.0 && Double.isFinite(combinedFeeTaxRate)
                ? combinedFeeTaxRate : 0.0;
        double costPerShare = price * ratio + price * fee;
        if (!(costPerShare > 0.0) || !Double.isFinite(costPerShare)) {
            return 0;
        }
        long fundsMax = (long) Math.floor(balance / costPerShare);
        // The last millimeter: the server's real deduction is ceil(marketValue x ratio) + ceil(marketValue x feeTax), two ceil passes,
        // so under extreme price/ratio combinations the ceiled total may exceed costPerShare x shares by up to 2 coins. Here the
        // candidate share count is re-checked with the server's VERBATIM rounding formula and stepped back share by share when
        // over - one-click sizing can never drift into 'the box says 32 shares but opening complains about balance'.
        while (fundsMax > 0 && exactOpenCost(price, fundsMax, ratio, fee) > balance + 1e-6) {
            fundsMax--;
        }
        if (fundsMax <= 0) {
            return 0;                       // Insufficient funds
        }
        long v = Math.min(fundsMax, (long) poolLeft);
        if (maxPerOrder > 0) {
            v = Math.min(v, maxPerOrder);   // Clamped from the same source as the single-order cap; the server will clamp once more
        }
        return (int) Math.min(v, (long) Integer.MAX_VALUE);
    }

    /** Whether the remaining borrowable share pool is scarce. Exactly 20% remaining is not scarce. */
    public static boolean scarce(int poolLeft, int poolMax) {
        return poolMax > 0 && poolLeft >= 0
                && (double) poolLeft / (double) poolMax < SCARCITY_LEFT_RATIO;
    }

    /**
     * Daily rate for a newly opened position. Existing ShortPosition values remain frozen at their entry rate.
     * Dirty or missing pool data never raises the rate; the result is capped at the configured safety ceiling.
     */
    public static double scarceDailyRate(double baseRate, int poolLeft, int poolMax, double multiplier) {
        double base = Double.isFinite(baseRate) && baseRate > 0.0 ? baseRate : 0.0;
        if (!scarce(poolLeft, poolMax) || base <= 0.0) {
            return base;
        }
        double factor = Double.isFinite(multiplier) && multiplier > 1.0 ? multiplier : 1.0;
        double result = base * factor;
        return !Double.isFinite(result) ? MAX_DAILY_RATE : Math.min(MAX_DAILY_RATE, result);
    }

    /**
     * [Purpose] the open-position total cost, term-for-term identical to server ShortService.open (margin + tax/fee, each ceil-ed separately).
     * [Worked example] price 10 x 32 shares x 1.5 = 480; 320 x 0.03 = 9.6 -> ceil 10; total 490.
     */
    public static long exactOpenCost(double price, long shares, double marginRatio, double feeTax) {
        double raw = price * (double) shares;
        long m = (long) Math.ceil(raw * marginRatio);
        long t = (long) Math.ceil(raw * feeTax);
        if (m < 0 || !Double.isFinite(raw * marginRatio)) {
            m = 0;
        }
        if (t < 0 || !Double.isFinite(raw * feeTax)) {
            t = 0;
        }
        return m + t;
    }
}
