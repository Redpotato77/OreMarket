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

import java.util.List;

/**
 * The GLOBAL SINGLE ENTRY for all loan arithmetic: the client UI
 * (detail rows, estimated cost, repayment schedule) and the server (borrow logs, booking parameters) must both
 * call the same set of methods here; two parallel formula sets on the two sides are strictly forbidden.
 *
 * <p>Design discipline:</p>
 * <ul>
 * <li>pure JDK static functions, zero Minecraft dependency, so both sides (unit tests included) can call directly;</li>
 * <li>compute in {@code double} throughout; round only at final display / booking (floor: a cent low at worst, never a cent high);</li>
 * <li>any dirty input (NaN / Infinity / negatives / null lists) is treated as 0 or empty: never throws,
 * never lets NaN flow into NBT or the UI;</li>
 * <li>the schedule itself is still built by {@link LoanSchedule}; this class is its ONLY access route:
 * the interest figure in the detail rows and the per-row interest of the schedule come from one and the same
 * {@link #schedule} call, nipping "61 up top, 309 below" (two conflicting algorithms) in the bud.</li>
 * </ul>
 */
public final class LoanCalculator {

    private LoanCalculator() {
    }

    /**
     * Repayment schedule (single entry): returns an empty list instead of crashing on invalid parameters.
     *
     * @param borrowDay    borrowing trading day
     * @param principal    loan principal (<= 0 -> empty list)
     * @param dailyRate    daily rate (decimal; NaN / negative treated as 0)
     * @param termDays     term in trading days (<= 0 clamped to 1)
     * @param installments installment count (1 through {@link LoanSchedule#MAX_INSTALLMENTS}; lump-sum repayment may also
     * be scheduled over multiple installments, with the first n-1 planned payments constantly 0;
     * clamping happens in {@link LoanSchedule#build})
     * @param mode         repayment mode ({@code LoanSchedule.MODE_*}; dirty values clamp back to lump-sum)
     */
    public static List<LoanSchedule.Row> schedule(long borrowDay, long principal, double dailyRate,
                                                  int termDays, int installments, int mode) {
        if (principal <= 0) {
            return List.of();
        }
        double rate = Double.isFinite(dailyRate) && dailyRate > 0 ? dailyRate : 0.0;
        return LoanSchedule.build(borrowDay, principal, rate, Math.max(1, termDays),
                installments, LoanSchedule.clampMode(mode));
    }

    /** Total planned interest of a schedule (same source as the per-row display; a null list counts as 0). */
    public static long plannedInterest(List<LoanSchedule.Row> rows) {
        return rows == null ? 0L : LoanSchedule.totalInterest(rows);
    }

    /**
     * Remaining unsettled planned interest (the single criterion for the "Interest" detail row): sums only rows whose
     * installment number is at least nextInst, identically equal to the interest shown on the schedule's unsettled rows -
     * the two numbers can therefore never disagree.
     */
    public static long remainingPlannedInterest(List<LoanSchedule.Row> rows, int nextInst) {
        if (rows == null || rows.isEmpty()) {
            return 0L;
        }
        long sum = 0;
        int from = Math.max(1, nextInst);
        for (LoanSchedule.Row r : rows) {
            if (r != null && r.index >= from) {
                sum = LoanSchedule.clampL((double) sum + r.interest);
            }
        }
        return sum;
    }

    /** Planned principal-and-interest total = principal + planned interest (saturating addition; dirty values count as 0). */
    public static long plannedPayoff(long principal, List<LoanSchedule.Row> rows) {
        long p = Math.max(0, principal);
        return LoanSchedule.clampL((double) p + plannedInterest(rows));
    }

    /**
     * First part (finalized single source for total debt): unsettled interest = planned-interest total minus paid interest.
     *
     * <p>The "Interest" detail row, total debt, end-of-day automatic repayment deductions and repayment logs all read this
     * one function: under the old criteria the detail row read booked accrued interest (only one day's 144 right after
     * borrowing), the schedule read all-period interest 724, and total debt read the ledger payoff 3313 - three sets of
     * numbers fighting. Now everything is "planned minus paid", so the identity
     * total debt = outstanding principal + unsettled interest + penalty (below the cap) holds on both sides forever.</p>
     */
    public static long remainingInterestOwed(List<LoanSchedule.Row> rows, long paidInterest) {
        long planned = plannedInterest(rows);
        long paid = Math.max(0, paidInterest);
        return paid >= planned ? 0L : planned - paid;
    }

    /**
     * First part: the GLOBALLY UNIQUE amount-due (payoff) formula. Server LoanData.payoff and the
     * loanPayoff value shipped to the client both come from here, so the UI can never read two different totals.
     *
     * <pre>
     * amount due = outstanding principal + min(unsettled interest + penalty,
     * principal x (interest cap multiplier - 1))
     * </pre>
     *
     * @param remainingPrincipal outstanding principal (dirty negatives count as 0)
     * @param basePrincipal      loan principal (the base of the interest cap; falls back to outstanding when <= 0)
     * @param rows               repayment schedule (null / empty means interest-free)
     * @param paidInterest       interest already paid
     * @param penaltyCeil        unpaid penalty interest (already ceil-ed to an integer)
     * @param capMultiplier      principal-and-interest cap multiplier (finite values take effect; dirty values mean no cap)
     */
    public static long payoff(long remainingPrincipal, long basePrincipal,
                              List<LoanSchedule.Row> rows, long paidInterest,
                              long penaltyCeil, double capMultiplier) {
        long principal = Math.max(0, remainingPrincipal);
        long basis = basePrincipal > 0 ? basePrincipal : principal;
        long interest = remainingInterestOwed(rows, paidInterest);
        long penalty = Math.max(0, penaltyCeil);
        long interestPart = LoanRules.saturatingAdd(interest, penalty);
        if (Double.isFinite(capMultiplier) && capMultiplier >= 1.0) {
            long cap = LoanSchedule.clampL(basis * (capMultiplier - 1.0));
            interestPart = Math.min(interestPart, cap);
        }
        return LoanSchedule.clampL((double) principal + interestPart);
    }

    /** Unsettled interest + penalty under the payoff criterion (principal excluded; same clamping as {@link #payoff}). */
    public static long interestAndPenalty(long basePrincipal, List<LoanSchedule.Row> rows,
                                          long paidInterest, long penaltyCeil, double capMultiplier) {
        return Math.max(0, payoff(0L, basePrincipal, rows, paidInterest, penaltyCeil, capMultiplier));
    }

    /** Interest cap (relative to principal): principal x (capMultiplier - 1); zero when the multiplier is dirty or below 1. */
    public static long interestCap(long principal, double capMultiplier) {
        long p = Math.max(0, principal);
        double mul = Double.isFinite(capMultiplier) ? capMultiplier : 0.0;
        if (mul <= 1.0) {
            return 0L;
        }
        return LoanSchedule.clampL(p * (mul - 1.0));
    }

    /**
     * For estimates: the planned-interest total clamped into the interest cap (the client's "estimated cost / expected
     * total repayment" and the planned interest the server writes into the log at borrow time share this method, so the figures agree exactly).
     */
    public static long cappedPlannedInterest(long principal, double dailyRate, int termDays,
                                             int installments, int mode, double capMultiplier) {
        List<LoanSchedule.Row> rows = schedule(0L, principal, dailyRate, termDays, installments, mode);
        return Math.min(plannedInterest(rows), interestCap(principal, capMultiplier));
    }

    /**
     * HOW MUCH ONE ORDINARY REPAYMENT MAY AT MOST DEDUCT - the
     * server deduction and the client's "due this installment" display call the same function, so the shown figure can
     * never disagree with the amount actually taken.
     *
     * <pre>
     * payable bill = penalty + sum over due-but-unsettled installments of (remaining interest + remaining principal)
     * (when no installment is due yet: penalty + the earliest installment's remaining principal + interest)
     * </pre>
     *
     * Each component is then clamped again by the ledger debt (interest at most planned minus paid,
     * principal at most outstanding); under Interest First, Principal Later before the final installment,
     * {@code allowPrincipal=false} zeroes the principal component outright.
     * A row's "remaining" = planned value minus the part of paidTotal allocated to that installment
     * (allocated interest-first then principal, in the same order as splitPayment), so every payment shrinks the next
     * payable bill by exactly that amount - nothing is ever charged twice.
     *
     * @param rows               repayment schedule (the product of {@link #schedule})
     * @param nextInst           current installment number (1-based)
     * @param day                current trading day
     * @param penaltyCeil        unpaid penalty interest (integer criterion)
     * @param remainingInterest  unsettled interest (= {@link #remainingInterestOwed})
     * @param remainingPrincipal outstanding principal
     * @param allowPrincipal     whether principal may be applied (false for Interest First, Principal Later before maturity)
     * @param paidTotal          total already paid (penalty + interest + principal; used to shrink the current bill)
     */
    public static long payableBill(List<LoanSchedule.Row> rows, int nextInst, long day,
                                   long penaltyCeil, long remainingInterest,
                                   long remainingPrincipal, boolean allowPrincipal, long paidTotal) {
        LoanSchedule.Payable p = LoanSchedule.payable(rows, nextInst, day, paidTotal);
        long interest = Math.min(p.interest, Math.max(0L, remainingInterest));
        long principal = allowPrincipal ? Math.min(p.principal, Math.max(0L, remainingPrincipal)) : 0L;
        return Math.max(0L, LoanSchedule.clampL((double) Math.max(0L, penaltyCeil)
                + (double) interest + (double) principal));
    }
}
