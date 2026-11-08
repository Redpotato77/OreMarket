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

import java.util.ArrayList;
import java.util.List;

/**
 * Repayment schedule (pure JDK, zero Minecraft dependency): server-side settlement and client display share the same code path,
 * so the installments shown in the UI can never disagree with the ones the server actually charges
 *
 * The four repayment modes:
 * - {@link #MODE_BALLOON} lump-sum principal and interest: whatever the chosen period count, the first n-1 planned payments are always 0,
 * and everything (principal plus interest) is charged in one go on the final day (the due day of period n);
 * - {@link #MODE_INTEREST_FIRST} interest first, principal later: the first X-1 periods charge only the current interest,
 * the last period charges all remaining principal plus the final interest; before the last period no payment may touch principal;
 * - {@link #MODE_ANNUITY} equal principal and interest (annuity): X periods, one charge every 5 trading days, identical payment per period;
 * - {@link #MODE_LINEAR} equal principal: principal per period = total principal / period count, current interest = remaining principal x daily rate x 5,
 * the final period zeroes the balance exactly.
 * - Total interest (simple-interest convention) = principal x daily rate x (period count x 5).
 *
 * Boundary conventions (all covered by unit tests; NaN / divide-by-zero / overflow forbidden):
 * - principal <= 0 -> empty table (callers treat it as no outstanding loan; no exception thrown);
 * - term <= 0 counts as 1 trading day; new-borrowal period counts are clamped to [1, {@link #MAX_INSTALLMENTS}];
 * - non-finite or negative daily rate -> treated as 0 (all interest 0; NaN never pollutes NBT);
 * - per-period amounts use floor + final-period true-up: the per-period principal always sums to the total principal,
 * even a principal of 1 gives one period charging 1 and the rest charging 0 (no negatives, no lost coins).
 *
 * <p><b>Term model</b>: each new system-loan installment uses the configured trading-day length
 * ({@code loanTermDays}, default 5), at most {@link #MAX_INSTALLMENTS} (5) installments. The
 * borrow entry point locks that total term into the loan, so hot-reloading the config never moves
 * an existing due date. The one-argument helper keeps the historical 5-day default for pure callers
 * and old saves; the two-argument helper is used by new system loans. Legacy saves whose term/count
 * do not match the current setting keep their stored term and are never rescheduled.</p>
 */
public final class LoanSchedule {
    /** Lump-sum principal and interest (default, matching the legacy-save convention). */
    public static final int MODE_BALLOON = 0;
    /** Interest first, principal later. */
    public static final int MODE_INTEREST_FIRST = 1;
    /** Equal principal and interest (annuity). */
    public static final int MODE_ANNUITY = 2;
    /** Equal principal. */
    public static final int MODE_LINEAR = 3;
    /** Number of modes (for validation and UI cycling). */
    public static final int MODE_COUNT = 4;
    /** Finalised: trading days represented by each installment. */
    public static final int DAYS_PER_INSTALLMENT = 5;
    /** Finalised: new-borrowal installment cap of 5 (total term at most 5 x 5 = 25 trading days). */
    public static final int MAX_INSTALLMENTS = 5;
    /** Legacy-save compatibility cap: reading historical archives still allows up to 24 installments so outstanding loans are not force-rescheduled. */
    public static final int LEGACY_MAX_INSTALLMENTS = 24;
    /** Overflow-protection cap (ore coins never get this far in practice; this only stops extreme configs from turning amounts negative). */
    public static final long SAFE_MAX_LIMIT = 1_000_000_000_000_000L;
    private static final double SAFE_MAX = (double) SAFE_MAX_LIMIT;

    private LoanSchedule() {
    }

    /** One installment of the repayment plan. Principal/interest are planned values (estimates at the locked rate); actual charges follow the booked interest. */
    public static final class Row {
        /** Installment number, starting at 1. */
        public final int index;
        /** Due trading day (absolute day = borrow day + cumulative period length). */
        public final long dueDay;
        /** Planned principal repayment. */
        public final long principal;
        /** Planned interest (locked daily rate x the actual days this period covers). */
        public final long interest;
        /** Trading days covered by this period (>= 1); lets the final period accrue interest honestly when days do not divide evenly. */
        public final int days;

        Row(int index, long dueDay, long principal, long interest, int days) {
            this.index = index;
            this.dueDay = dueDay;
            this.principal = principal;
            this.interest = interest;
            this.days = days;
        }

        /** Planned total payment for this period. */
        public long total() {
            return saturate(principal + interest);
        }
    }

    /** Clamp a repayment-mode id into the valid range (dirty values from the client must not crash the server). */
    public static int clampMode(int mode) {
        return mode >= 0 && mode < MODE_COUNT ? mode : MODE_BALLOON;
    }

    /** Installment-count clamping (new-borrowal rule): non-positive becomes 1; above the cap becomes {@link #MAX_INSTALLMENTS} (5). */
    public static int clampInstallments(int installmentCount) {
        if (installmentCount < 1) {
            return 1;
        }
        return Math.min(installmentCount, MAX_INSTALLMENTS);
    }

    /** Legacy-save read rule: the historical cap of 24 installments still applies, so an outstanding loan's period count is never rewritten. */
    public static int clampInstallmentsLegacy(int installmentCount) {
        if (installmentCount < 1) {
            return 1;
        }
        return Math.min(installmentCount, LEGACY_MAX_INSTALLMENTS);
    }

    /**
     * Total term for a new system loan using the historical/default five-day period.
     * Existing callers keep this overload for compatibility; configurable system-loan paths
     * use {@link #totalTermDays(int, int)} instead.
     */
    public static int totalTermDays(int installments) {
        return totalTermDays(installments, DAYS_PER_INSTALLMENT);
    }

    /** Config safety bound for the number of trading days in one installment. */
    private static final int MAX_DAYS_PER_INSTALLMENT = 30;

    /**
     * Total term for a new system loan. The configured period is clamped to the safe config range
     * and the installment count is clamped before multiplication, so dirty network/config values
     * cannot create a zero, negative, or overflowing term.
     */
    public static int totalTermDays(int installments, int daysPerInstallment) {
        int boundedDaysPerInstallment = Math.max(1,
                Math.min(daysPerInstallment, MAX_DAYS_PER_INSTALLMENT));
        return clampInstallments(installments) * boundedDaysPerInstallment;
    }

    /**
     * Generate the schedule.
     *
     * @param borrowDay borrow trading day (absolute day, used for per-period due dates)
     * @param principal loan principal (<= 0 returns an empty table)
     * @param dailyRate locked daily rate (fraction; non-finite or negative treated as 0)
     * @param termDays total locked term in trading days (<= 0 treated as 1; new system loans provide installments x configured period)
     * @param installments period count (clamped to [1, {@link #MAX_INSTALLMENTS}])
     * @param mode repayment method (passes through {@link #clampMode} first)
     */
    public static List<Row> build(long borrowDay, long principal, double dailyRate,
                                  int termDays, int installments, int mode) {
        List<Row> schedule = new ArrayList<>();
        if (principal <= 0) {
            return schedule;
        }
        long safePrincipal = Math.min(principal, (long) SAFE_MAX);
        int safeTermDays = Math.max(1, termDays);
        double safeDailyRate = Double.isFinite(dailyRate) && dailyRate > 0 ? dailyRate : 0.0;
        int repaymentMode = clampMode(mode);
        // Finalised: lump-sum repayment ALLOWS multi-period scheduling -- the first periodCount - 1 planned payments are always 0,
        // and the entire principal plus interest lands on the last period (trading day periodCount x 5).
        int periodCount = Math.min(clampInstallmentsLegacy(installments), safeTermDays);

        // Period-length split: term days over periodCount periods, the first (term % periodCount) periods take one extra day.
        int basePeriodDays = safeTermDays / periodCount;
        int extraPeriodDays = safeTermDays % periodCount;
        int[] periodDays = new int[periodCount];
        long[] dueDays = new long[periodCount];
        long dueDayCursor = borrowDay;
        int cumulativeDays = 0;
        for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
            int periodLength = basePeriodDays + (periodIndex < extraPeriodDays ? 1 : 0);
            if (periodLength < 1) {
                periodLength = 1;
            }
            periodDays[periodIndex] = periodLength;
            cumulativeDays += periodLength;
            dueDays[periodIndex] = dueDayCursor + cumulativeDays;
        }

        long[] principalParts = new long[periodCount];
        long[] interestParts = new long[periodCount];
        switch (repaymentMode) {
            case MODE_INTEREST_FIRST -> {
                // Interest first, principal later: each period's interest accrues on the full principal; the last period also repays principal.
                for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
                    interestParts[periodIndex] = interestFor(safePrincipal, safeDailyRate, periodDays[periodIndex]);
                }
                principalParts[periodCount - 1] = safePrincipal;
            }
            case MODE_ANNUITY -> {
                // Annuity: the total payment per period is fixed; the final period absorbs the indivisible remainder.
                // Interest in this mod is simple full-term interest, so each period's interest is the full interest for that period's days.
                long totalInterest = interestFor(safePrincipal, safeDailyRate, safeTermDays);
                long fixedPeriodPayment = (safePrincipal + totalInterest) / periodCount;
                long remainingPrincipal = safePrincipal;
                long principalUsed = 0;
                long interestUsed = 0;
                for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
                    if (periodIndex == periodCount - 1) {
                        // The final period absorbs the leftover principal and interest remainder.
                        principalParts[periodIndex] = safePrincipal - principalUsed;
                        interestParts[periodIndex] = totalInterest - interestUsed;
                        break;
                    }
                    long periodInterest = Math.min(
                            interestFor(safePrincipal, safeDailyRate, periodDays[periodIndex]),
                            totalInterest - interestUsed);
                    long periodPrincipal = Math.min(
                            Math.max(0, fixedPeriodPayment - periodInterest), remainingPrincipal);
                    principalParts[periodIndex] = periodPrincipal;
                    interestParts[periodIndex] = periodInterest;
                    principalUsed += periodPrincipal;
                    interestUsed += periodInterest;
                    remainingPrincipal -= periodPrincipal;
                }
            }
            case MODE_LINEAR -> {
                // Equal principal: principal splits evenly, with the remainder in the final period; interest decays with the balance.
                long fixedPeriodPrincipal = safePrincipal / periodCount;
                long finalRemainder = safePrincipal - fixedPeriodPrincipal * periodCount;
                long remainingPrincipal = safePrincipal;
                for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
                    long periodPrincipal = fixedPeriodPrincipal
                            + (periodIndex == periodCount - 1 ? finalRemainder : 0);
                    principalParts[periodIndex] = periodPrincipal;
                    interestParts[periodIndex] = interestFor(remainingPrincipal, safeDailyRate,
                            periodDays[periodIndex]);
                    remainingPrincipal -= periodPrincipal;
                    if (remainingPrincipal < 0) {
                        remainingPrincipal = 0;
                    }
                }
            }
            default -> {
                // Lump-sum principal and interest land on the last period; earlier periods are empty.
                interestParts[periodCount - 1] = interestFor(safePrincipal, safeDailyRate, safeTermDays);
                principalParts[periodCount - 1] = safePrincipal;
            }
        }
        for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
            schedule.add(new Row(periodIndex + 1, dueDays[periodIndex], principalParts[periodIndex],
                    interestParts[periodIndex], periodDays[periodIndex]));
        }
        return schedule;
    }

    /** One period's interest: principal x daily rate x days, floored and overflow-clamped. */
    public static long interestFor(long principal, double dailyRate, int days) {
        if (principal <= 0 || days <= 0 || !Double.isFinite(dailyRate) || dailyRate <= 0) {
            return 0;
        }
        return clampL(principal * dailyRate * days);
    }

    /** Total planned principal over the schedule. */
    public static long totalPrincipal(List<Row> rows) {
        long sum = 0;
        for (Row r : rows) {
            sum = saturate(sum + r.principal);
        }
        return sum;
    }

    /** Total planned interest over the schedule. */
    public static long totalInterest(List<Row> rows) {
        long sum = 0;
        for (Row r : rows) {
            sum = saturate(sum + r.interest);
        }
        return sum;
    }

    /** Total planned payments over the schedule (principal plus interest). */
    public static long totalPayoff(List<Row> rows) {
        return saturate(totalPrincipal(rows) + totalInterest(rows));
    }

    /** Periods due on or before today that nothing later is scheduled ahead of: which installment is due today. */
    public static int dueThroughDay(List<Row> rows, long day) {
        int dueCount = 0;
        for (Row row : rows) {
            if (row.dueDay <= day) {
                dueCount++;
            } else {
                break;
            }
        }
        return dueCount;
    }

    /**
     * The due day of the first period that is unsettled AND actually owes money.
     *
     * <p>Lump-sum repayment may be scheduled over 1~5 periods; the first n-1 periods always have planned principal/interest of 0.
     * Those empty periods carry no debt and must never be the overdue baseline: otherwise a 25-trading-day loan would be judged
     * overdue on day 6 (penalty interest, credit downgrade, same-day forced liquidation of a collateral loan). When every row is
     * empty (principal already repaid / dirty save), fall back to the caller's {@code fallbackDueDay} (the server passes the
     * contract due day).
     *
     * <p>Both the server's overdue check and the offline verification read this one method, killing the two-places-two-criteria
     * class of bugs.
     *
     * @param rows the repayment schedule
     * @param nextInst current unsettled period number (1-based)
     * @param fallbackDueDay due day to fall back to when no real payable period is found
     */
    public static long firstOpenDueDay(List<Row> rows, int nextInst, long fallbackDueDay) {
        for (Row r : rows) {
            if (r.index >= nextInst && r.total() > 0) {
                return r.dueDay;
            }
        }
        return fallbackDueDay;
    }

    /**
     * The billing-window start of a single period: the trading day on which this period's interest begins accruing.
     *
     * <p>The schedule is generated as due day = borrow day + cumulative days, so the window start is simply
     * {@code dueDay - days}: period 1 starts on the borrow day itself; period k starts on period k-1's due day
     * (the last day of the previous period, i.e. this period's first accrual day). The per-period windows
     * {@code [windowStartDay, dueDay]} are contiguous and cover the whole contract term exactly.
     */
    public static long windowStartDay(Row row) {
        return row.dueDay - Math.max(1, row.days);
    }

    /**
     * The window-opening day of the first period that is unsettled AND actually owes money
     * (the single source for the installment %s only opens on trading day %s message).
     * When no such period exists (all settled / dirty save), fall back to the caller's {@code fallbackDueDay}.
     */
    public static long firstOpenDay(List<Row> rows, int nextInst, long fallbackDueDay) {
        if (rows == null) {
            return fallbackDueDay;
        }
        for (Row r : rows) {
            if (r != null && r.index >= Math.max(1, nextInst) && r.total() > 0) {
                return windowStartDay(r);
            }
        }
        return fallbackDueDay;
    }

    /** The first period number in the schedule with an amount greater than 0 (returns count + 1 when every row is empty): used to initialise the unsettled index when posting a loan. */
    public static int firstChargedIndex(List<Row> rows) {
        for (Row r : rows) {
            if (r.total() > 0) {
                return r.index;
            }
        }
        return rows.size() + 1;
    }

    /**
     * Advance the unsettled period index by the total repaid: accumulate planned payments period by period; the index moves to the next period as soon as the repaid total covers it.
     * Empty periods with 0 payable (the first n-1 of a lump-sum loan) contribute 0 and are naturally treated as settled.
     *
     * @param rows the repayment schedule
     * @param paidTotal penalty interest + interest + principal repaid in total
     * @return unsettled period number (1-based; count + 1 when everything is settled)
     */
    public static int openIndex(List<Row> rows, long paidTotal) {
        long acc = 0;
        int idx = 1;
        for (Row r : rows) {
            acc = saturate(acc + r.total());
            if (paidTotal >= acc) {
                idx = r.index + 1;
            } else {
                break;
            }
        }
        return Math.max(1, idx);
    }

    /** Remaining principal beyond the first {@code settledRows} periods. */
    public static long remainingPrincipal(List<Row> rows, int settledRows) {
        long sum = 0;
        int firstUnsettledIndex = Math.max(0, Math.min(settledRows, rows.size()));
        for (int rowIndex = firstUnsettledIndex; rowIndex < rows.size(); rowIndex++) {
            sum = saturate(sum + rows.get(rowIndex).principal);
        }
        return sum;
    }

    /**
     * Red-line 1 criterion: the first-n-minus-1 zero-bill window of a lump-sum loan with MULTIPLE installments.
     *
     * <p>This mode places all principal and interest on the last period (due at {@code dueDay}), so while
     * {@code mode=BALLOON && installments>1 && day<dueDay} the payable for the current period is always 0:
     * every regular repayment button (pay 10 / pay 100 / pay off) must warn and return immediately -- no deduction,
     * no failed-deduction flag, no overdue flag (the overdue baseline {@link #firstOpenDueDay} skips empty periods too).
     * The only allowed early-settlement path is the prepay button (the prepay route does not pass this gate).
     *
     * <p>The criterion lives in the pure calculation class: the server (LoanService) and the offline verification harness
     * run the same code, eliminating false greens from duplicated logic in tests.
     */
    public static boolean balloonZeroBillPeriod(int mode, int installments, long day, long dueDay) {
        return clampMode(mode) == MODE_BALLOON && installments > 1 && day < dueDay;
    }

    /**
     * The single source for at most how much this regular repayment may deduct (the root fix for the cross-period prepayment bug).
     *
     * <p>Four components:
     * <ul>
     * <li>{@code interest} / {@code principal}: sum only the unsettled periods that are ALREADY due ({@code dueDay <= day});
     * when NOT A SINGLE period is due, the current period is admitted only if it has already entered its own billing
     * window ({@code day >= } {@link #windowStartDay}, the day its interest starts accruing), falling back to the
     * earliest period's bill and setting {@code early} -- a period whose window has not opened contributes nothing to
     * the bill, which is exactly the rule that period 1 may be paid while periods 2-4 must stay unsettled;</li>
     * <li>{@code dueDay}: the due day of the last payable period in this batch (for the message text);</li>
     * <li>{@code rows}: how many periods are payable ({@code 0} = no payable bill at all).</li>
     * </ul>
     *
     * <p>Periods are generated with strictly increasing due days, so scanning can break at the first row that is not yet due:
     * everything behind it is certainly not due either. Empty periods (the first n-1 of a lump-sum loan, {@code total()==0})
     * are skipped and never form a payable bill.
     */
    public static final class Payable {
        /** The REMAINING interest of payable periods (planned interest minus what this batch already paid; raw value before book clamping). */
        public long interest;
        /** The REMAINING principal of payable periods (planned principal minus what this batch already paid; raw value before book clamping). */
        public long principal;
        /** Due day of the last payable period in this batch; {@link Long#MIN_VALUE} when nothing is payable. */
        public long dueDay = Long.MIN_VALUE;
        /** Due day of the earliest payable period in this batch (the baseline for detecting an early payment). */
        public long firstDueDay = Long.MIN_VALUE;
        /** Number of payable periods in this batch. */
        public int rows;
        /**
         * True when no period is due yet, but this payment prepaids the current period inside its billing window.
         * At most one period may be rolled forward per trading day.
         */
        public boolean early;

        /** Upper bound of this payable bill (before book clamping) = penalty interest + interest + principal. */
        public long bill(long penaltyCeil) {
            return saturate(saturate(Math.max(0L, penaltyCeil) + interest) + principal);
        }
    }

    /**
     * See {@link Payable}: the composition of this regular repayment's payable bill.
     *
     * <p><b>Fixed (the current bill must subtract what was already paid into the current period)</b>: the old rule
     * reported only the planned principal/interest of payable periods and never credited money the player had already paid into
     * the current period -- every click of pay 100 capped the interest component at the full period's planned interest, so
     * {@code splitPayment}, blind to the earlier interest, kept pouring money into interest until the interest pools of ALL
     * periods were filled before any principal was credited. The player log showing eleven pay-100 clicks with the first 800
     * recorded as interest (the current period's planned interest is only 292) came from exactly this hole: interest of
     * periods 2-5 was paid ahead while period 1's principal still hung open, and the cross-period prepayment red line leaked
     * through the interest seam.
     *
     * <p>Now the repaid total (penalty + interest + principal, the same number {@link #openIndex} uses to advance the index) is
     * consumed period by period first, within each period interest before principal (the same order as {@code splitPayment}),
     * and the bill reports only the leftovers -- index advance and bill shrinkage stay forever in sync: whatever was paid
     * reduces the current bill by exactly that much.
     *
     * @param paidTotal total repaid (penalty interest + interest + principal; dirty negatives treated as 0)
     */
    public static Payable payable(List<Row> rows, int nextInst, long day, long paidTotal) {
        Payable result = new Payable();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        int from = Math.max(1, nextInst);
        // Only money paid after the first unsettled period should reduce the current payable bill.
        long settledBefore = 0L;
        for (Row row : rows) {
            if (row != null && row.index < from) {
                settledBefore = saturate(settledBefore + row.total());
            }
        }
        long applied = Math.max(0L, Math.max(0L, paidTotal) - settledBefore);
        for (Row row : rows) {
            if (row == null || row.index < from || row.total() <= 0) {
                continue;
            }
            long interest = row.interest;
            long principal = row.principal;
            long appliedToThisPeriod = Math.min(applied, row.total());
            if (appliedToThisPeriod > 0) {
                long appliedInterest = Math.min(appliedToThisPeriod, interest);
                interest -= appliedInterest;
                principal -= Math.min(appliedToThisPeriod - appliedInterest, principal);
                applied -= appliedToThisPeriod;
            }
            if (row.dueDay > day) {
                // A future period may be prepaid only once its own billing window opens.
                if (result.rows == 0 && day >= windowStartDay(row)) {
                    result.interest = interest;
                    result.principal = principal;
                    result.dueDay = row.dueDay;
                    result.firstDueDay = row.dueDay;
                    result.rows = 1;
                }
                break;
            }
            if (result.rows == 0) {
                result.firstDueDay = row.dueDay;
            }
            result.interest = saturate(result.interest + interest);
            result.principal = saturate(result.principal + principal);
            result.dueDay = row.dueDay;
            result.rows++;
        }
        result.early = result.rows > 0 && result.dueDay > day;
        return result;
    }

    /**
     * Red-line 2 criterion: for interest-first loans before the last period, the principal-offset path must be fully sealed.
     *
     * <p>Any regular payment (pay 10 / pay 100 / pay off) before the last period may offset only penalty interest and then
     * interest; any leftover stays in the wallet and must never hit principal. The rule matches the server's
     * {@code LoanService.principalAllowed} exactly: the last period counts as reached when its due day has arrived or the
     * unsettled index has advanced to it ({@code nextInst >= installments}, i.e. the first n-1 interest payments are done).
     * The prepay button is exempt from this gate and may settle everything early.
     */
    public static boolean interestFinalNotReached(int mode, long day, long dueDay,
                                                  int nextInst, int installments) {
        if (clampMode(mode) != MODE_INTEREST_FIRST) {
            return false;
        }
        return day < dueDay && nextInst < Math.max(1, installments);
    }

    /** Clamp an amount into [0, SAFE_MAX]; non-finite values become 0 (NaN/Infinity must never reach NBT). */
    public static long clampL(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return 0;
        }
        return value > SAFE_MAX ? (long) SAFE_MAX : (long) Math.floor(value);
    }

    private static double clampD(double value) {
        if (!Double.isFinite(value) || value < 0) {
            return 0;
        }
        return Math.min(value, SAFE_MAX);
    }

    private static long saturate(long value) {
        return value < 0 ? (long) SAFE_MAX : value;
    }
}
