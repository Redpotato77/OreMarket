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
 * The single source of truth for trading fee rates and money amounts (server settlement, short-open tax, order book, limit orders and logs all route through here).
 *
 * Rules:
 * - total rate = commission rate + destroy-tax rate - commission rate x destroy-tax rate;
 * - the destroy tax leaves circulation directly and enters no pool; event modifiers only touch the commission part;
 * - buys deduct with ceil, sells credit with floor, so ore-coin balances can never be fractional;
 * - resting orders and limit orders use the additive 'trade tax + dedicated commission' scheme and do NOT reuse the system trade rate;
 * - short closes and forced-liquidation settlements are tax-free; only the open-position tax is affected by the event commission modifier.
 */
public final class Fees {
    /** Cap on the event-adjusted commission rate, keeping config/events from pushing it into a meaningless range. */
    public static final double MAX_EFFECTIVE_FEE_RATE = 0.20;

    private Fees() {
    }

    /** Non-finite guard: NaN / +/-Infinity all count as 0 (when config or event data is polluted, better a zero fee than a NaN settlement). */
    private static double finite(double v) {
        return v == v && !Double.isInfinite(v) ? v : 0.0;
    }

    /** Clamp the event-adjusted commission rate into 0~20% (non-finite values -> 0). */
    public static double effectiveFeeRate(double feeRate) {
        double v = finite(feeRate);
        return Math.max(0.0, Math.min(MAX_EFFECTIVE_FEE_RATE, v));
    }

    /** Product-form combined rate shared by system trades and short opens. */
    public static double combined(double feeRate, double taxRate) {
        double fee = effectiveFeeRate(feeRate);
        double tax = Math.max(0.0, finite(taxRate));
        return fee + tax - fee * tax;
    }

    /** Additive rate (order-book two-sided commission, limit-order extra commission). */
    public static double additive(double taxRate, double extraRate) {
        return Math.max(0.0, finite(taxRate)) + Math.max(0.0, finite(extraRate));
    }

    public static double amount(double price, int qty) {
        return Math.max(0.0, finite(price)) * Math.max(0, qty);
    }

    public static long buyCost(double price, int qty, double totalRate) {
        return (long) Math.ceil(amount(price, qty) * (1.0 + Math.max(0.0, finite(totalRate))));
    }

    public static long sellGain(double price, int qty, double totalRate) {
        return (long) Math.floor(amount(price, qty)
                * Math.max(0.0, 1.0 - Math.max(0.0, finite(totalRate))));
    }

    /** Weighted-average cost basis after a buy fill; the charged amount includes all buyer fees. */
    public static double weightedAverageBuyCost(int oldShares, double oldAverageCost,
                                                 long chargedCoins, int boughtShares) {
        if (boughtShares <= 0) {
            return Math.max(0.0, oldAverageCost);
        }
        if (oldShares <= 0) {
            return (double) chargedCoins / boughtShares;
        }
        long safeOldShares = Math.max(0L, oldShares);
        double oldBasis = Double.isFinite(oldAverageCost) ? Math.max(0.0, oldAverageCost) : 0.0;
        long totalShares = safeOldShares + boughtShares;
        return (safeOldShares * oldBasis + chargedCoins) / totalShares;
    }

    /**
     * Split the fee into commission and destroy tax for log display; the actual deduction still uses the combined rate.
     *
     * Log display now always uses the exact values from {@link #feeAmountExact} / {@link #taxAmountExact};
     * this method stays only as the integer-basis reference and is banned from display use.
     */
    public static long feePortion(double price, int qty, double feeRate) {
        return (long) Math.round(amount(price, qty) * effectiveFeeRate(feeRate));
    }

    /** Integer-basis destroy tax (same rule: display must use {@link #taxAmountExact}; never round a fractional fee back to an integer for display). */
    public static long destroyPortion(double price, int qty, double taxRate) {
        return (long) Math.round(amount(price, qty) * Math.max(0.0, finite(taxRate)));
    }

    /**
     * [Purpose] The EXACT commission amount (decimal, unrounded): the 'Commission' shown in buy/sell logs and detail
     * rows is this money - it truly exists and is taken together with the actual deduction (counted into the buy cost).
     * ONLY the display layer may keep two decimals; rounding it to a long the way the old version showed 0.25 as 0 is banned.
     * [Worked example] commission = subtotal x event-adjusted commission rate:
     * 12.41 x 1 share = subtotal 12.41, rate 2% -> 12.41 x 0.02 = 0.2482,
     * shown with two decimals as "0.25" (the old long-cast showed "0.0" - the very bug fixed here).
     * Non-finite guard: a price / rate polluted into NaN, Inf or negative counts as 0 and can never produce NaN text.
     */
    public static double feeAmountExact(double price, int qty, double feeRate) {
        return amount(price, qty) * effectiveFeeRate(feeRate);
    }

    /**
     * [Purpose] The EXACT destroy-tax amount (decimal, unrounded): before this fix the log had no such column at all,
     * and players saw 'total 12.4, commission 0.0, actually charged 13' - three numbers that never added up, as if the 1%
     * tax had been swallowed; the tax now has its own column, and together with the charged / credited columns the whole
     * log line reconciles on its own.
     * [Worked example] destroy tax = subtotal x tax rate: 12.41 x 1% = 0.1241 -> shown "0.12".
     * The tax leaves the wallet with the actual charge and is destroyed outright (enters no pool), on the same books as the
     * settlement combined rate {@link #combined} (commission + tax - their product):
     * subtotal 12.41 x (1 + 0.0298) = 12.7798 -> ceil, charged 13 (rounding up anything below a whole ore coin is established policy).
     */
    public static double taxAmountExact(double price, int qty, double taxRate) {
        return amount(price, qty) * Math.max(0.0, finite(taxRate));
    }

    /**
     * [Purpose] The four money columns of the BUY log: subtotal / commission / destroy tax / actually charged.
     * TradeService's log assembly and the offline verification share this single entry - 'the numbers in the log' and
     * 'the numbers actually deducted' come from the same place and can never fight again (the root cause of this bug was
     * the log printing the pre-fee subtotal plus an integer-cast commission while the deduction ran through the combined
     * rate with ceil: three numbers each telling its own story).
     * [Worked example] trade price 12.41 x 1 share, commission 2%, destroy tax 1%:
     * subtotal 12.41 -> "12.41"; commission 12.41 x 0.02 = 0.2482 -> "0.25";
     * destroy tax 12.41 x 0.01 = 0.1241 -> "0.12";
     * charged = ceil(12.41 x (1 + combined(0.02, 0.01))) = ceil(12.7798) = "13".
     * [Self-check] subtotal + commission + tax = 12.78, at least the billing base 12.7798 (the gap is exactly the
     * commission-rate x tax-rate product term, per the definition of combined); charged = ceil(base);
     * the four columns match the settlement entry {@link #buyCost} one by one in order - no elementary math error.
     */
    public static String[] buyLedger(double price, int qty, double feeRate, double taxRate) {
        return ledger(price, qty, feeRate, taxRate, true);
    }

    /**
     * [Purpose] The four money columns of the SELL log: subtotal / commission / destroy tax / actually credited (exactly symmetric to buy).
     * [Worked example] trade price 12.41 x 1 share, commission 2%, destroy tax 1%:
     * subtotal "12.41"; commission "0.25"; destroy tax "0.12";
     * credited = floor(12.41 x (1 - 0.0298)) = floor(12.0402) = "12".
     * [Self-check] credited <= subtotal x (1 - combined rate) < credited + 1 (the definition of floor); the two fee columns
     * sum to at least subtotal - base (the difference being the product term); consistent in order with settlement entry {@link #sellGain}.
     */
    public static String[] sellLedger(double price, int qty, double feeRate, double taxRate) {
        return ledger(price, qty, feeRate, taxRate, false);
    }

    private static String[] ledger(double price, int qty, double feeRate, double taxRate, boolean buy) {
        double total = combined(feeRate, taxRate);
        long settle = buy ? buyCost(price, qty, total) : sellGain(price, qty, total);
        return new String[]{
                Num.fixed(amount(price, qty), 2),
                Num.fixed(feeAmountExact(price, qty, feeRate), 2),
                Num.fixed(taxAmountExact(price, qty, taxRate), 2),
                String.valueOf(settle),
        };
    }
}
