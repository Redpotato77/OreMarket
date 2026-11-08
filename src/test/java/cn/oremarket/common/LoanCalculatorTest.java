package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoanCalculator is the single interest convention shared by the detail panel, the schedule table and the server.
 * Cases reproduce the ledger straight from the player's screenshot: principal 1200, daily rate 5.153%, 5 trading days, lump-sum principal-and-interest at maturity -- correct interest is about 309, and the two numbers shown on the UI must be identical.
 */
class LoanCalculatorTest {

    @Test
    void userCaseInterestIs309Everywhere() {
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(100L, 1200L, 0.05153, 5, 1,
                LoanSchedule.MODE_BALLOON);
        assertEquals(1, rows.size(), "balloon mode has exactly one installment");
        long planned = LoanCalculator.plannedInterest(rows);
        assertEquals(309L, planned, "1200 * 5.153% * 5 = 309.18 -> rounded down to 309");
        // Detail line (unsettled remainder) and per-row schedule total share one source: when nextInst=1 the two are always equal
        assertEquals(planned, LoanCalculator.remainingPlannedInterest(rows, 1));
        assertEquals(1509L, LoanCalculator.plannedPayoff(1200L, rows), "planned payoff = principal + interest");
    }

    @Test
    void settledRowsDropOutOfRemaining() {
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(0L, 1200L, 0.05, 6, 3,
                LoanSchedule.MODE_INTEREST_FIRST);
        assertEquals(3, rows.size());
        long all = LoanCalculator.plannedInterest(rows);
        assertTrue(LoanCalculator.remainingPlannedInterest(rows, 2) < all, "settling one installment lowers the remaining interest");
        assertEquals(0L, LoanCalculator.remainingPlannedInterest(rows, rows.size() + 1));
        // A dirty nextInst is never allowed as a negative and never goes out of range
        assertEquals(all, LoanCalculator.remainingPlannedInterest(rows, 0));
        assertEquals(all, LoanCalculator.remainingPlannedInterest(rows, -5));
        assertEquals(0L, LoanCalculator.remainingPlannedInterest(rows, 9999), "an out-of-range installment number means no remaining interest");
    }

    @Test
    void dirtyInputsNeverThrowOrNaN() {
        assertTrue(LoanCalculator.schedule(0L, 0L, Double.NaN, 0, 0, 99).isEmpty());
        assertTrue(LoanCalculator.schedule(0L, -5L, 0.05, 5, 1, 0).isEmpty());
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(0L, 1200L, Double.NaN, 5, 1, 0);
        assertEquals(0L, LoanCalculator.plannedInterest(rows), "a dirty rate is treated as 0 and never becomes NaN");
        assertEquals(0L, LoanCalculator.plannedInterest(null));
        assertEquals(0L, LoanCalculator.remainingPlannedInterest(null, 1));
        assertEquals(0L, LoanCalculator.interestCap(1200L, Double.NaN));
        assertEquals(0L, LoanCalculator.interestCap(-1L, 2.0));
    }

    @Test
    void payoffSaturatesDirtyInterestAndPenalty() {
        long payoff = LoanCalculator.payoff(Long.MAX_VALUE, Long.MAX_VALUE,
                List.of(), Long.MAX_VALUE, Long.MAX_VALUE, Double.NaN);
        assertEquals(LoanSchedule.SAFE_MAX_LIMIT, payoff);
    }

    @Test
    void cappedEstimateMatchesCapMultiplier() {
        // Daily rate 50%, 5 days: scheduled interest 3000, but capMultiplier=2 caps interest at the principal 1200
        long capped = LoanCalculator.cappedPlannedInterest(1200L, 0.5, 5, 1,
                LoanSchedule.MODE_BALLOON, 2.0);
        assertEquals(1200L, capped);
        long normal = LoanCalculator.cappedPlannedInterest(1200L, 0.05153, 5, 1,
                LoanSchedule.MODE_BALLOON, 2.0);
        assertEquals(309L, normal, "inside the cap nothing is clamped");
    }

    @Test
    void balloonPutsAllPayoffOnLastRow() {
        // : lump-sum repayment may span a multi-period schedule; the first n-1 periods are 0 and the last period settles everything.
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(0L, 1200L, 0.05, 5, 5,
                LoanSchedule.MODE_BALLOON);
        assertEquals(5, rows.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(0L, rows.get(i).total(), "the first n-1 installments charge 0");
        }
        assertEquals(1200L + 300L, rows.get(4).total(), "the final installment deducts principal plus interest in one go");
    }

    @Test
    void userCasePayoffIsPrincipalPlusInterestPlusPenalty() {
        // Principal 3169, interest 724, penalty interest 0 -> total debt must equal 3893,
        // and the two conflicting totals (detail 3313 vs schedule 3893) must never reappear.
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(104L, 3169L, 0.04573, 5, 1,
                LoanSchedule.MODE_BALLOON);
        long interest = LoanCalculator.remainingInterestOwed(rows, 0);
        long payoff = LoanCalculator.payoff(3169L, 3169L, rows, 0, 0, 2.0);
        assertEquals(interest, LoanCalculator.plannedInterest(rows));
        assertEquals(3169L + interest, payoff, "total debt = principal + interest");
        assertTrue(payoff >= 3169L);
    }

    @Test
    void remainingInterestDropsByPaidAmount() {
        List<LoanSchedule.Row> rows = LoanCalculator.schedule(0L, 1200L, 0.05, 5, 1,
                LoanSchedule.MODE_BALLOON);
        long all = LoanCalculator.plannedInterest(rows);
        assertEquals(all, LoanCalculator.remainingInterestOwed(rows, 0));
        assertEquals(all - 10, LoanCalculator.remainingInterestOwed(rows, 10));
        assertEquals(0L, LoanCalculator.remainingInterestOwed(rows, all));
        assertEquals(0L, LoanCalculator.remainingInterestOwed(rows, all + 99));
        assertEquals(0L, LoanCalculator.remainingInterestOwed(null, 1));
    }
}
