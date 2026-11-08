package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary tests for loan rules.
 * All through the pure-JDK {@link LoanRules} / {@link LoanSchedule}, with no Minecraft dependency.
 */
public final class LoanRulesTest {

    @Test
    void saturatingDebtAddNeverWraps() {
        assertEquals(3L, LoanRules.saturatingAdd(1L, 2L));
        assertEquals(Long.MAX_VALUE, LoanRules.saturatingAdd(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, LoanRules.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(5L, LoanRules.saturatingAdd(-10L, 5L));
    }

    @Test
    void scoreClampsAndIgnoresNegatives() {
        int mid = LoanRules.creditScore(0, 0, 0, 0, 0, 12, 35, 150, 2000, 15);
        assertEquals(LoanRules.SCORE_BASE, mid);
        int tooHigh = LoanRules.creditScore(999, 0, 0, 1_000_000_000L, 99, 12, 35, 150, 2000, 15);
        assertEquals(LoanRules.SCORE_MAX, tooHigh);
        int tooLow = LoanRules.creditScore(0, 999, 99, 0, 0, 12, 35, 150, 2000, 15);
        assertEquals(LoanRules.SCORE_MIN, tooLow);
        int fromNeg = LoanRules.creditScore(-4, -2, -1, -100, -3, 12, 35, 150, 2000, 15);
        assertEquals(LoanRules.SCORE_BASE, fromNeg);
    }

    @Test
    void scoreAssetStepZeroDoesNotDivide() {
        int s = LoanRules.creditScore(0, 0, 0, 10_000, 2, 12, 35, 150, 0, 15);
        assertTrue(s >= LoanRules.SCORE_MIN && s <= LoanRules.SCORE_MAX);
    }

    @Test
    void dynamicLimitZeroBaseStaysZero() {
        assertEquals(0, LoanRules.dynamicLimit(0, 800, 10_000, 5_000, 0, true, 20, 50, true));
        assertEquals(0, LoanRules.dynamicLimit(-1, 800, 10_000, 5_000, 0, true, 20, 50, true));
    }

    @Test
    void dynamicLimitOverdueCutAndNaN() {
        long base = 1000;
        long none = LoanRules.dynamicLimit(base, 650, 0, 0, 0, false, 0, 0, true);
        long one = LoanRules.dynamicLimit(base, 650, 0, 0, 1, false, 0, 0, true);
        long three = LoanRules.dynamicLimit(base, 650, 0, 0, 3, false, 0, 0, true);
        assertTrue(one < none);
        assertTrue(three < one);
        // A non-finite percentage disables only the matching bonus term,
        // while the level + credit-tier base quota is still computed (score 650 = Good tier, 1.25x).
        // Collapsing the entire quota to 0 would turn one config typo into a borrowing ban for every player.
        assertEquals(1250, LoanRules.dynamicLimit(base, 650, 0, 0, 0, false,
                Double.NaN, Double.POSITIVE_INFINITY, true));
        // Return 0 only when the collateral bonus truly overflows (infinite ratio with collateral present): better to grant no quota than to admit astronomical numbers
        assertEquals(0, LoanRules.dynamicLimit(base, 650, 0, 1000, 0, true,
                20, Double.POSITIVE_INFINITY, true));
        // Overdue discount has a 40% floor: no matter how overdue, the quota is never cut to 0 (otherwise it could never be repaid)
        assertEquals((long) Math.floor(1250 * 0.4),
                LoanRules.dynamicLimit(base, 650, 0, 0, 99, false, 20, 50, true));
    }

    @Test
    void dailyRateNeverNaNAndCapped() {
        double r = LoanRules.quoteDailyRate(5.0, 300, 40, 1.0, 12.0, true);
        assertTrue(Double.isFinite(r) && r >= 0 && r <= 0.01 + 1e-12);
        double nan = LoanRules.quoteDailyRate(Double.NaN, 0, Double.POSITIVE_INFINITY, Double.NaN, 12, true);
        assertTrue(Double.isFinite(nan) && nan >= 0.0 && nan <= 0.01 + 1e-12, "actual=" + nan);
        assertEquals(0.0, LoanRules.quoteDailyRate(Double.NaN, 650, Double.NaN, Double.NaN, 12, true), 0.0);
        double over = LoanRules.quoteDailyRate(50, 300, 200, 1, 12, true);
        assertTrue(over <= 0.01 + 1e-9, "even the 12% config cap is pressed to <=1% by the hard cap, actual=" + over);
        double off = LoanRules.quoteDailyRate(0.5, 300, 40, 0, 12, false);
        assertEquals(0.005, off, 1e-9, "scoring disabled = base rate");
        // Old convention: a 5% base rate, run through the 0.5% soft cap and credit/event multipliers, is still forced to at most 1% by the hard cap
        assertTrue(LoanRules.quoteDailyRate(5.0, 650, 40, 0, 12, true) <= 0.01 + 1e-12);
        assertEquals(0.01, LoanRules.clampDailyRate(0.04573), 1e-12);
    }

    @Test
    void prepayNeverBelowPrincipal() {
        long due = LoanRules.prepayAmount(100, 20, 10, 1);
        assertTrue(due >= 100);
        assertEquals(0, LoanRules.prepayAmount(-10, -5, 200, -1));
        assertTrue(LoanRules.prepayAmount(1, 0, Double.NaN, Double.POSITIVE_INFINITY) >= 1);
    }

    @Test
    void splitPaymentNeverOverpays() {
        long[] a = LoanRules.splitPayment(50, 10, 20, 100);
        assertEquals(10, a[0]);
        assertEquals(20, a[1]);
        assertEquals(20, a[2]);
        long[] b = LoanRules.splitPayment(-1, 10, 20, 100);
        assertArrayEquals(new long[]{0, 0, 0}, b);
        long[] c = LoanRules.splitPayment(5, 10, 20, 100);
        assertEquals(5, c[0] + c[1] + c[2]);
    }

    @Test
    void scheduleEmptyOnZeroPrincipal() {
        assertTrue(LoanSchedule.build(1, 0, 0.05, 5, 1, LoanSchedule.MODE_BALLOON).isEmpty());
        assertTrue(LoanSchedule.build(1, -10, 0.05, 5, 1, LoanSchedule.MODE_BALLOON).isEmpty());
    }

    @Test
    void schedulePrincipalSumsToTotal() {
        for (int mode = 0; mode < LoanSchedule.MODE_COUNT; mode++) {
            for (int n : new int[]{1, 2, 3, 5, 12}) {
                List<LoanSchedule.Row> rows = LoanSchedule.build(10, 100, 0.05, 5, n, mode);
                assertEquals(100, LoanSchedule.totalPrincipal(rows), "mode=" + mode + " n=" + n);
                assertFalse(rows.isEmpty());
                for (LoanSchedule.Row r : rows) {
                    assertTrue(r.principal >= 0 && r.interest >= 0 && r.days >= 1);
                    assertTrue(r.dueDay > 10);
                }
            }
        }
    }

    @Test
    void scheduleHandlesZeroRateAndOverflow() {
        List<LoanSchedule.Row> z = LoanSchedule.build(1, 100, 0, 5, 5, LoanSchedule.MODE_ANNUITY);
        assertEquals(100, LoanSchedule.totalPrincipal(z));
        assertEquals(0, LoanSchedule.totalInterest(z));
        List<LoanSchedule.Row> nan = LoanSchedule.build(1, 100, Double.NaN, 5, 3, LoanSchedule.MODE_LINEAR);
        assertEquals(100, LoanSchedule.totalPrincipal(nan));
        List<LoanSchedule.Row> huge = LoanSchedule.build(1, Long.MAX_VALUE, 0.05, 5, 1, LoanSchedule.MODE_BALLOON);
        assertTrue(LoanSchedule.totalPayoff(huge) > 0);
        assertTrue(LoanSchedule.totalPayoff(huge) <= LoanSchedule.SAFE_MAX_LIMIT * 2);
    }

    @Test
    void clampModeAndInstallments() {
        assertEquals(LoanSchedule.MODE_BALLOON, LoanSchedule.clampMode(-1));
        assertEquals(LoanSchedule.MODE_BALLOON, LoanSchedule.clampMode(99));
        assertEquals(1, LoanSchedule.clampInstallments(0));
        assertEquals(LoanSchedule.MAX_INSTALLMENTS, LoanSchedule.clampInstallments(999));
    }

    // ================================================================ boundary additions for v2

    @Test
    void eachModePrincipalSumsToBorrowedAmount() {
        for (int mode : new int[]{LoanSchedule.MODE_INTEREST_FIRST,
                LoanSchedule.MODE_ANNUITY, LoanSchedule.MODE_LINEAR}) {
            List<LoanSchedule.Row> rows = LoanSchedule.build(3, 12_000, 0.0025, 12, 5, mode);
            assertEquals(12_000, LoanSchedule.totalPrincipal(rows), "mode " + mode);
            assertEquals(5, rows.size(), "mode " + mode);
            long days = 0;
            for (LoanSchedule.Row r : rows) {
                assertTrue(r.days >= 1, "mode " + mode + " installments have at least one day each");
                assertTrue(r.interest >= 0 && r.principal >= 0, "mode " + mode + " components are non-negative");
                days += r.days;
            }
            assertEquals(12, days, "mode " + mode + " day sums equal the term");
            assertEquals(3 + 12, rows.get(rows.size() - 1).dueDay, "mode " + mode + " final due day");
        }
    }

    @Test
    void balloonAndInterestFirstDifferOnlyInPrincipalSplit() {
        List<LoanSchedule.Row> balloon = LoanSchedule.build(1, 1000, 0.002, 10, 1, LoanSchedule.MODE_BALLOON);
        List<LoanSchedule.Row> upfront = LoanSchedule.build(1, 1000, 0.002, 10, 5,
                LoanSchedule.MODE_INTEREST_FIRST);
        assertEquals(LoanSchedule.totalInterest(balloon), LoanSchedule.totalInterest(upfront),
                "interest-first and balloon share the same total interest (principal is repaid last in both)");
        assertEquals(1000, LoanSchedule.totalPrincipal(upfront));
        assertEquals(4, upfront.get(0).interest, "first installment interest = 1000 * 0.002 * 2 days");
        assertEquals(0, upfront.get(0).principal, "interest-first repays no principal in the first installment");
    }

    @Test
    void balloonLastRowCarriesAllPayoff() {
        // : lump-sum repayment charges 0 on the first n-1 periods and settles everything in the last period.
        List<LoanSchedule.Row> rows = LoanSchedule.build(10, 1000, 0.001, 12, 3,
                LoanSchedule.MODE_BALLOON);
        assertEquals(3, rows.size());
        assertEquals(0, rows.get(0).total());
        assertEquals(0, rows.get(1).total());
        assertEquals(1000 + 12, rows.get(2).total());
        assertEquals(22, rows.get(2).dueDay, "final due day = borrow day + whole term");
        assertEquals(1000, LoanSchedule.totalPrincipal(rows));
    }

    @Test
    void daySplitPutsRemainderFirst() {
        List<LoanSchedule.Row> rows = LoanSchedule.build(1, 1000, 0.001, 7, 3, LoanSchedule.MODE_LINEAR);
        assertEquals(3, rows.get(0).days);
        assertEquals(2, rows.get(1).days);
        assertEquals(2, rows.get(2).days);
    }

    @Test
    void installmentsNeverExceedTerm() {
        List<LoanSchedule.Row> rows = LoanSchedule.build(1, 1000, 0.001, 2, 24, LoanSchedule.MODE_ANNUITY);
        assertEquals(2, rows.size(), "a 2-day term yields at most 2 installments (the last one cannot be 0 days)");
        assertEquals(1000, LoanSchedule.totalPrincipal(rows));
    }

    @Test
    void dailyRateRespectsCapAndStaysFraction() {
        double mid = LoanRules.quoteDailyRate(0.35, 625, 0, 0, 12, true);
        assertEquals(0.0035, mid, 1e-12, "neutral score 625 + no event = base rate");
        double great = LoanRules.quoteDailyRate(0.35, 950, 0, 0, 12, true);
        assertEquals(0.0035 * 0.60, great, 1e-12, "best tier gets a 0.6x discount");
        double bad = LoanRules.quoteDailyRate(0.35, 300, 0, 0, 12, true);
        assertEquals(0.0035 * 1.40, bad, 1e-12, "worst tier pays 1.4x");
        assertTrue(great < mid && mid < bad);
        assertEquals(0.0, LoanRules.quoteDailyRate(Double.NaN, 650, Double.NaN, Double.NaN, Double.NaN, true),
                1e-12, "fully dirty input = 0");
        assertEquals(0.0, LoanRules.quoteDailyRate(1.0, 650, 0, 0, -5, true), 1e-12,
                "a negative cap behaves as 0 (never a negative rate)");
        double locked5 = LoanRules.lockedDailyRate(0.0035, 1.0, 5, 12);
        assertEquals(0.0035 * 1.20, locked5, 1e-12, "5-installment term multiplier 1.20");
        assertEquals(0.01, LoanRules.lockedDailyRate(0.01, 2.0, 5, 12), 1e-12, "still capped at 1% after the multiplier");
    }

    @Test
    void termIsFiveDaysPerInstallment() {
        assertEquals(5, LoanSchedule.totalTermDays(1));
        assertEquals(25, LoanSchedule.totalTermDays(5));
        assertEquals(25, LoanSchedule.totalTermDays(99));
        List<LoanSchedule.Row> rows = LoanSchedule.build(10, 1000, 0.01, 25, 5, LoanSchedule.MODE_LINEAR);
        assertEquals(5, rows.size());
        for (LoanSchedule.Row r : rows) {
            assertEquals(5, r.days, "each installment is a fixed 5 trading days");
        }
        assertEquals(35, rows.get(4).dueDay);
    }

    @Test
    void configurableTermDaysDriveEachNewLoanInstallment() {
        assertEquals(7, LoanSchedule.totalTermDays(1, 7));
        assertEquals(21, LoanSchedule.totalTermDays(3, 7));
        assertEquals(150, LoanSchedule.totalTermDays(99, 30),
                "dirty installment counts and terms are clamped first and never overflow");
        List<LoanSchedule.Row> rows = LoanSchedule.build(10, 1000, 0.01,
                LoanSchedule.totalTermDays(3, 7), 3, LoanSchedule.MODE_LINEAR);
        assertEquals(3, rows.size());
        for (LoanSchedule.Row r : rows) {
            assertEquals(7, r.days, "the configured days-per-installment must reach the actual schedule");
        }
        assertEquals(31, rows.get(2).dueDay);
    }

    @Test
    void invalidConfiguredTermDaysNeverCreateZeroOrNegativeTerm() {
        assertEquals(1, LoanSchedule.totalTermDays(1, 0));
        assertEquals(1, LoanSchedule.totalTermDays(1, -99));
        assertEquals(150, LoanSchedule.totalTermDays(5, Integer.MAX_VALUE));
    }

    @Test
    void interestFirstForbidsPrincipalBeforeLast() {
        long[] mid = LoanRules.splitPayment(100, 0, 40, 1000, false);
        assertEquals(0, mid[2], "principal must never be paid before the last installment");
        assertEquals(40, mid[1]);
        long[] last = LoanRules.splitPayment(100, 0, 40, 1000, true);
        assertEquals(60, last[2]);
    }

    @Test
    void linearLastRowZerosPrincipal() {
        List<LoanSchedule.Row> rows = LoanSchedule.build(1, 1000, 0.01, 15, 3, LoanSchedule.MODE_LINEAR);
        assertEquals(1000, LoanSchedule.totalPrincipal(rows));
        long remaining = 1000;
        for (int i = 0; i < rows.size(); i++) {
            remaining -= rows.get(i).principal;
        }
        assertEquals(0, remaining, "equal-principal mode zeroes the remainder exactly at the last row");
        assertEquals(333 + 333 + 334, rows.get(0).principal + rows.get(1).principal + rows.get(2).principal);
    }

    @Test
    void prepayNeverBelowPrincipalAndClampsDirtyPercents() {
        assertEquals(1000 + 90 + 10, LoanRules.prepayAmount(1000, 100, 10, 1));
        assertEquals(1100, LoanRules.prepayAmount(1000, 100, 0, 0));
        assertEquals(1000, LoanRules.prepayAmount(1000, 100, 100, 0), "even fully waived interest keeps the amount at least the principal");
        assertEquals(0, LoanRules.prepayAmount(-1, -1, -1, -1));
        assertEquals(LoanSchedule.SAFE_MAX_LIMIT, LoanRules.prepayAmount(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0),
                "overflow is clamped to the safe limit");
    }

    @Test
    void partialPaymentAppliesInOrderAndNeverVanishes() {
        long[] p = LoanRules.splitPayment(100, 30, 40, 500);
        assertArrayEquals(new long[]{30, 40, 30}, p);
        assertArrayEquals(new long[]{0, 0, 0}, LoanRules.splitPayment(-1, 30, 40, 500));
        assertArrayEquals(new long[]{0, 0, 0}, LoanRules.splitPayment(1000, 0, 0, 0));
        // Key invariant: when the actual payment is at most the billed amount, the three parts must sum to what was paid (otherwise money vanishes)
        for (long pay : new long[]{1, 17, 30, 70, 100, 530, 569, 570}) {
            long owed = 30 + 40 + 500;
            long[] r = LoanRules.splitPayment(Math.min(pay, owed), 30, 40, 500);
            assertEquals(Math.min(pay, owed), r[0] + r[1] + r[2], "pay=" + pay);
        }
    }

    @Test
    void fullRepaymentIntentWithInsufficientBalanceOnlyUsesAvailableCoins() {
        long requestedFullPayment = 570;
        long walletBalance = 125;
        long[] split = LoanRules.splitPayment(Math.min(requestedFullPayment, walletBalance),
                30, 40, 500);

        assertEquals(walletBalance, split[0] + split[1] + split[2],
                "a full-repayment request must be clamped to the available balance");
        assertArrayEquals(new long[]{30, 40, 55}, split,
                "an insufficient balance pays penalty, then interest, then only the affordable principal");
        assertEquals(445, 500 - split[2],
                "the unpaid principal remains outstanding instead of being treated as bankruptcy");
    }

    @Test
    void dynamicLimitMonotonicAndFloored() {
        long base = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 0, false, 20, 50, true);
        assertEquals(5000, base, "good tier 1.0x + 20% of current assets");
        assertEquals(21_200, LoanRules.dynamicLimit(1200, 600, 100_000, 0, 0, false, 20, 50, true),
                "current assets continue increasing the limit beyond the old gameplay cap");
        assertTrue(LoanRules.dynamicLimit(1000, 400, 20_000, 0, 3, false, 20, 50, true) < base);
        long one = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 1, false, 20, 50, true);
        long two = LoanRules.dynamicLimit(1000, 600, 20_000, 0, 2, false, 20, 50, true);
        assertTrue(one > two, "same score, more overdue means a lower limit");
        assertEquals((long) Math.floor(5000 * 0.4),
                LoanRules.dynamicLimit(1000, 600, 20_000, 0, 99, false, 20, 50, true),
                "discount has a 40% floor (never zeroed no matter how overdue)");
        assertEquals(0, LoanRules.dynamicLimit(0, 950, 1_000_000, 1_000_000, 0, true, 20, 50, true),
                "a 0 level limit (not eligible yet) makes the dynamic limit 0 too");
        assertEquals(1000, LoanRules.dynamicLimit(1000, 300, 0, 0, 0, false, 20, 50, false),
                "with credit scoring off only the level base limit counts");
    }

    // ================================================================ 8 product / repayment-method checks

    @Test
    void creditScoreBelowFloorRejectsBorrow() {
        assertFalse(LoanRules.scoreAllowsBorrow(LoanRules.SCORE_BORROW_MIN - 1, 0),
                "credit loan: insufficient score must be rejected");
        assertTrue(LoanRules.scoreAllowsBorrow(LoanRules.SCORE_BORROW_MIN, 0));
        assertTrue(LoanRules.scoreAllowsBorrow(300, 2), "the emergency loan is a comeback path and ignores the score");
    }

    @Test
    void emergencyLoanAssetAndCapGates() {
        assertFalse(LoanRules.emergencyAllowed(1001, 100), "total assets above 1000 forbid the emergency loan");
        assertTrue(LoanRules.emergencyAllowed(1000, 100));
        assertTrue(LoanRules.emergencyAllowed(0, 100));
        assertFalse(LoanRules.emergencyAllowed(0, 0), "forbidden when disabled by config");
        assertEquals(100, LoanRules.emergencyCap(500), "hard per-draw cap of 100");
        assertEquals(50, LoanRules.emergencyCap(50));
        assertEquals(0, LoanRules.emergencyCap(-1));
    }

    @Test
    void repaymentModesMatchUserMathSpec() {
        // Assume borrowing 1200 over 3 periods with total interest 120: daily rate x term = 0.10, amortized over 0.10/3 days.
        // To make total interest exactly 120: principal 1200 x daily rate x 3 days = 120 -> daily rate = 120/3600 = 1/30.
        double rate = 120.0 / (1200.0 * 3.0);
        long p = 1200;
        int term = 3;
        int n = 3;

        // 5. Lump-sum principal and interest: first two periods 0, third period 1320
        List<LoanSchedule.Row> balloon = LoanSchedule.build(0, p, rate, term, n, LoanSchedule.MODE_BALLOON);
        assertEquals(0, balloon.get(0).total());
        assertEquals(0, balloon.get(1).total());
        assertEquals(1320, balloon.get(2).total());

        // 6. Interest first, principal later: first two periods pay only interest 40, third period 1240
        List<LoanSchedule.Row> first = LoanSchedule.build(0, p, rate, term, n, LoanSchedule.MODE_INTEREST_FIRST);
        assertEquals(40, first.get(0).total());
        assertEquals(40, first.get(1).total());
        assertEquals(0, first.get(0).principal);
        assertEquals(0, first.get(1).principal);
        assertEquals(1240, first.get(2).total());
        assertEquals(1200, first.get(2).principal);

        // 7. Equal principal and interest (annuity): each of the three periods charges a fixed 440
        List<LoanSchedule.Row> annuity = LoanSchedule.build(0, p, rate, term, n, LoanSchedule.MODE_ANNUITY);
        assertEquals(440, annuity.get(0).total());
        assertEquals(440, annuity.get(1).total());
        assertEquals(440, annuity.get(2).total());
        assertEquals(1200, LoanSchedule.totalPrincipal(annuity));
        assertEquals(120, LoanSchedule.totalInterest(annuity));

        // 8. Equal principal: principal per period is constant (400), interest accrues on the decreasing balance, so the three total payments decline
        List<LoanSchedule.Row> linear = LoanSchedule.build(0, p, rate, term, n, LoanSchedule.MODE_LINEAR);
        assertEquals(400, linear.get(0).principal);
        assertEquals(400, linear.get(1).principal);
        assertEquals(400, linear.get(2).principal);
        assertEquals(40, linear.get(0).interest, "first interest = 1200 * daily rate * 1 day");
        assertTrue(linear.get(0).total() > linear.get(1).total(), "equal-principal payments decrease installment by installment");
        assertTrue(linear.get(1).total() > linear.get(2).total());
        assertTrue(linear.get(2).interest < linear.get(0).interest, "the last installment carries the least interest");
        assertEquals(1200, LoanSchedule.totalPrincipal(linear));
        assertEquals(79, LoanSchedule.totalInterest(linear), "equal principal accrues interest on the decreasing balance, so the total is below interest-first");
    }

    @Test
    void splitPaymentAlwaysPenaltyThenInterestThenPrincipal() {
        long[] r = LoanRules.splitPayment(100, 30, 50, 1000);
        assertEquals(30, r[0], "penalty is deducted first");
        assertEquals(50, r[1], "then interest");
        assertEquals(20, r[2], "principal last");
        long[] shortfall = LoanRules.splitPayment(20, 30, 50, 1000);
        assertEquals(20, shortfall[0]);
        assertEquals(0, shortfall[1]);
        assertEquals(0, shortfall[2]);
        assertEquals(20, shortfall[0] + shortfall[1] + shortfall[2], "even a shortfall is fully allocated; nothing is quietly forgiven");
    }

    @Test
    void clampDailyRateNeverExceedsHardCap() {
        assertEquals(0.01, LoanRules.clampDailyRate(0.04573), 1e-12, "4.573% must be pressed down to 1%");
        assertEquals(0.01, LoanRules.clampDailyRate(0.05, 0.12), 1e-12);
        assertEquals(0.0, LoanRules.clampDailyRate(-1));
        assertEquals(0.0, LoanRules.clampDailyRate(Double.NaN));
        assertEquals(0.005, LoanRules.clampDailyRate(0.005, 0.12), 1e-12, "below the cap the rate is kept as-is");
    }
}
