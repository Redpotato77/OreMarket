package cn.oremarket.common;

import java.util.List;

/**
 * Pure-JDK verification entry (no JUnit / Minecraft): covers shared debt source, interest capping,
 * the four repayment-method math cases, deduction order and the emergency-loan threshold. Any failure exits via System.exit(1).
 */
public final class LoanVerifyMain {
    private static int failed;
    private static int passed;

    public static void main(String[] args) {
        balloonLastRow();
        interestFirst();
        annuityEqual();
        linearDecreasing();
        payoffIdentity();
        remainingInterestPaid();
        rateHardCap();
        scoreAndEmergencyGates();
        splitOrder();
        dirtyInputs();
        eightCombos();
        System.out.println(failed == 0
                ? "[LoanVerifyMain] PASS " + passed + " checks"
                : "[LoanVerifyMain] FAIL " + failed + "/" + (passed + failed));
        if (failed != 0) {
            System.exit(1);
        }
    }

    private static void eq(long a, long b, String what) {
        if (a == b) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what + " expected " + b + " got " + a);
        }
    }

    private static void eqd(double a, double b, double eps, String what) {
        if (Math.abs(a - b) <= eps) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what + " expected " + b + " got " + a);
        }
    }

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    /** Case 5: lump-sum principal and interest -- first two periods 0, third period 1320. */
    private static void balloonLastRow() {
        double rate = 120.0 / (1200.0 * 3.0);
        List<LoanSchedule.Row> rows = LoanSchedule.build(0, 1200, rate, 3, 3, LoanSchedule.MODE_BALLOON);
        eq(rows.size(), 3, "balloon rows");
        eq(rows.get(0).total(), 0, "balloon p1");
        eq(rows.get(1).total(), 0, "balloon p2");
        eq(rows.get(2).total(), 1320, "balloon p3");
    }

    /** Case 6: interest first, principal later -- first two periods 40, third period 1240. */
    private static void interestFirst() {
        double rate = 120.0 / (1200.0 * 3.0);
        List<LoanSchedule.Row> rows = LoanSchedule.build(0, 1200, rate, 3, 3, LoanSchedule.MODE_INTEREST_FIRST);
        eq(rows.get(0).total(), 40, "if p1");
        eq(rows.get(1).total(), 40, "if p2");
        eq(rows.get(0).principal, 0, "if p1 principal");
        eq(rows.get(2).total(), 1240, "if p3");
        eq(rows.get(2).principal, 1200, "if p3 principal");
    }

    /** Case 7: equal principal and interest (annuity) -- 440 per period. */
    private static void annuityEqual() {
        double rate = 120.0 / (1200.0 * 3.0);
        List<LoanSchedule.Row> rows = LoanSchedule.build(0, 1200, rate, 3, 3, LoanSchedule.MODE_ANNUITY);
        eq(rows.get(0).total(), 440, "annuity p1");
        eq(rows.get(1).total(), 440, "annuity p2");
        eq(rows.get(2).total(), 440, "annuity p3");
        eq(LoanSchedule.totalPrincipal(rows), 1200, "annuity principal");
        eq(LoanSchedule.totalInterest(rows), 120, "annuity interest");
    }

    /** Case 8: equal principal -- payments decrease; the last period is principal plus the least interest. */
    private static void linearDecreasing() {
        double rate = 120.0 / (1200.0 * 3.0);
        List<LoanSchedule.Row> rows = LoanSchedule.build(0, 1200, rate, 3, 3, LoanSchedule.MODE_LINEAR);
        eq(rows.get(0).principal, 400, "linear principal");
        eq(rows.get(0).interest, 40, "linear first interest");
        ok(rows.get(0).total() > rows.get(1).total(), "linear decreasing 1");
        ok(rows.get(1).total() > rows.get(2).total(), "linear decreasing 2");
        eq(LoanSchedule.totalPrincipal(rows), 1200, "linear principal sum");
        // Equal principal accrues interest on the decreasing balance: 40+26+13=79, necessarily less than interest-first's 120
        eq(LoanSchedule.totalInterest(rows), 79, "linear interest sum");
        eq(rows.get(0).total(), 440, "linear p1 400+40");
        eq(rows.get(1).total(), 426, "linear p2 400+26");
        eq(rows.get(2).total(), 413, "linear p3 400+13");
    }

    /** Total debt = principal + interest + penalty interest. */
    private static void payoffIdentity() {
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(104L, 3169L, 0.01, 5, 1,
                LoanSchedule.MODE_BALLOON);
        long interest = LoanCalculator.remainingInterestOwed(rows, 0);
        long payoff = LoanCalculator.payoff(3169L, 3169L, rows, 0, 0, 2.0);
        eq(payoff, 3169L + interest, "payoff = principal + interest");
        eq(interest, 158L, "3169 x 1% x 5 = 158.45 floor 158");
        long withPen = LoanCalculator.payoff(3169L, 3169L, rows, 0, 10, 2.0);
        eq(withPen, 3169L + interest + 10, "payoff includes penalty");
    }

    private static void remainingInterestPaid() {
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(0L, 1200L, 0.05, 5, 1,
                LoanSchedule.MODE_BALLOON);
        long all = LoanCalculator.plannedInterest(rows);
        eq(LoanCalculator.remainingInterestOwed(rows, 0), all, "unpaid = planned");
        eq(LoanCalculator.remainingInterestOwed(rows, 10), all - 10, "partial paid");
        eq(LoanCalculator.remainingInterestOwed(rows, all), 0, "fully paid");
        eq(LoanCalculator.remainingInterestOwed(null, 1), 0, "null rows");
    }

    private static void rateHardCap() {
        eqd(LoanRules.clampDailyRate(0.04573), 0.01, 1e-12, "4.573% -> 1%");
        eqd(LoanRules.quoteDailyRate(5.0, 625, 40, 0, 12, true), 0.005, 1e-9, "5% base soft-capped to 0.5%");
        eqd(LoanRules.quoteDailyRate(0.5, 625, 40, 0, 12, false), 0.005, 1e-9, "0.5% stays");
        ok(LoanRules.quoteDailyRate(50, 300, 200, 1, 12, true) <= 0.01 + 1e-9, "dirty high capped");
        eqd(LoanRules.quoteDailyRate(-10, 0, -10, -5, -1, true), 0.0, 1e-12, "neg cap -> 0");
        eqd(LoanRules.lockedDailyRate(0.005, 1.0, 5, 12), 0.006, 1e-9, "5 installments x1.20");
        eq(LoanSchedule.totalTermDays(3), 15, "3 installments = 15 days");
        long[] blocked = LoanRules.splitPayment(80, 0, 10, 1000, false);
        eq(blocked[2], 0, "interest-first pays no principal before the last row");
    }

    private static void scoreAndEmergencyGates() {
        ok(!LoanRules.scoreAllowsBorrow(LoanRules.SCORE_BORROW_MIN - 1, 0), "credit score reject");
        ok(LoanRules.scoreAllowsBorrow(LoanRules.SCORE_BORROW_MIN, 0), "credit score pass");
        ok(LoanRules.scoreAllowsBorrow(300, 2), "emergency ignores score");
        ok(!LoanRules.emergencyAllowed(1001, 100), "assets > 1000 reject emergency");
        ok(LoanRules.emergencyAllowed(1000, 100), "assets = 1000 allowed");
        eq(LoanRules.emergencyCap(500), 100, "emergency cap 100");
        eq(LoanRules.emergencyCap(50), 50, "emergency cap respects smaller");
    }

    private static void splitOrder() {
        long[] r = LoanRules.splitPayment(100, 30, 50, 1000);
        eq(r[0], 30, "penalty first");
        eq(r[1], 50, "interest second");
        eq(r[2], 20, "principal last");
        long[] shortfall = LoanRules.splitPayment(20, 30, 50, 1000);
        eq(shortfall[0] + shortfall[1] + shortfall[2], 20, "shortfall fully allocated");
        eq(shortfall[0], 20, "shortfall all to penalty");
    }

    private static void dirtyInputs() {
        ok(LoanCalculator.schedule(0, 0, Double.NaN, 0, 0, 99).isEmpty(), "zero principal empty");
        eq(LoanCalculator.plannedInterest(null), 0, "null planned");
        eq(LoanCalculator.payoff(-1, -1, null, -5, -9, Double.NaN), 0, "dirty payoff");
    }

    /**
     * Coverage: 4 repayment methods x 1 period = 5 days / 5 periods = 25 days, plus the rate multipliers of the 4 products.
     * No Minecraft dependency; replicates the server-side formulas directly.
     */
    private static void eightCombos() {
        long p = 1000;
        double rate = 0.01; // Already capped at 1%
        int[] ns = {1, 5};
        int[] modes = {
                LoanSchedule.MODE_BALLOON,
                LoanSchedule.MODE_INTEREST_FIRST,
                LoanSchedule.MODE_ANNUITY,
                LoanSchedule.MODE_LINEAR
        };
        for (int n : ns) {
            int term = LoanSchedule.totalTermDays(n);
            eq(term, (long) n * 5, n + " installments = " + (n * 5) + " days");
            for (int mode : modes) {
                List<LoanSchedule.Row> rows = LoanSchedule.build(10, p, rate, term, n, mode);
                eq(rows.size(), n, "mode " + mode + " n=" + n + " row count");
                eq(LoanSchedule.totalPrincipal(rows), p, "mode " + mode + " n=" + n + " principal sum");
                long days = 0;
                for (LoanSchedule.Row r : rows) {
                    days += r.days;
                    ok(r.principal >= 0 && r.interest >= 0, "mode " + mode + " components non-negative");
                }
                eq(days, term, "mode " + mode + " n=" + n + " day sum");
                if (mode == LoanSchedule.MODE_BALLOON && n > 1) {
                    for (int i = 0; i < n - 1; i++) {
                        eq(rows.get(i).total(), 0, "balloon first n-1 rows are 0");
                    }
                    eq(rows.get(n - 1).principal, p, "balloon last row repays all principal");
                }
                if (mode == LoanSchedule.MODE_INTEREST_FIRST && n > 1) {
                    for (int i = 0; i < n - 1; i++) {
                        eq(rows.get(i).principal, 0, "interest-first repays no principal in the first n-1 rows");
                    }
                    eq(rows.get(n - 1).principal, p, "interest-first repays principal in the last row");
                }
                if (mode == LoanSchedule.MODE_LINEAR) {
                    long remain = p;
                    for (LoanSchedule.Row r : rows) {
                        remain -= r.principal;
                    }
                    eq(remain, 0, "equal principal ends exactly at zero");
                }
            }
        }
        // 4 products: quoted rate x product factor x term multiplier, hard-capped at 1%
        double quote = LoanRules.quoteDailyRate(0.5, 625, 0, 0, 12, true);
        eqd(quote, 0.005, 1e-12, "neutral quote = 0.5%");
        eqd(LoanRules.lockedDailyRate(quote, 1.00, 1, 12), 0.005, 1e-12, "credit loan 1 installment");
        eqd(LoanRules.lockedDailyRate(quote, 0.80, 1, 12), 0.004, 1e-12, "collateral loan 0.8x");
        ok(LoanRules.lockedDailyRate(quote, 2.00, 1, 12) <= 0.01 + 1e-12, "emergency high rate still capped at 1%");
        ok(LoanRules.lockedDailyRate(quote, 1.50, 5, 12) <= 0.01 + 1e-12, "margin loan 5 installments still capped at 1%");
        // Anti-negative-payment: given 80 but only 10 interest allowed -> deduct 10, principal 0
        long[] split = LoanRules.splitPayment(80, 0, 10, 1000, false);
        eq(split[0] + split[1] + split[2], 10, "with principal frozen the real charge = interest");
        eq(split[2], 0, "principal frozen");
    }
}
