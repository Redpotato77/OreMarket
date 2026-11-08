package cn.oremarket.common;

import cn.oremarket.market.ShortPosition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortSettlementTest {
    @Test
    void halfCloseReleasesLockedMarginByOriginalQuantity() {
        assertEquals(500L, ShortSettlement.releasedLocked(1000.0, 1000L, 5, 10));
        assertEquals(450L, ShortSettlement.releasedLocked(1000.0, 450L, 5, 10));
    }

    @Test
    void fullCloseReleasesOnlyRemainingRefundableBalance() {
        assertEquals(700L, ShortSettlement.releasedLocked(1000.0, 700L, 5, 5));
    }

    @Test
    void partialCloseCannotEmptyInterestReducedRemainder() {
        assertEquals(350L, ShortSettlement.releasedRemaining(700L, 50, 100));
        assertEquals(175L, ShortSettlement.releasedRemaining(350L, 25, 50));
        assertEquals(0L, ShortSettlement.releasedRemaining(700L, 0, 100));
        assertEquals(700L, ShortSettlement.releasedRemaining(700L, 100, 100));
    }

    @Test
    void invalidAndExtremePnlAreClampedSafely() {
        assertEquals(0L, ShortSettlement.pnl(0.0, 10.0, 1));
        assertEquals(Long.MIN_VALUE, ShortSettlement.pnl(1.0, 1.0e12, 10_000_000));
        assertEquals(Long.MAX_VALUE, ShortSettlement.buybackCost(Double.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void profitableCloseUsesTheQuoteBeforeItsOwnBuyShock() {
        assertEquals(1000L, ShortSettlement.pnl(10.0, 8.0, 500));
    }

    @Test
    void currentRemainingMarginIncludesInterestAndFloatingPnl() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 100,
                10.0, 500L, 0.0, 0.50, 1.15, 0.43, 0.0, 1L);
        position.chargeInterest(30L);
        assertEquals(470.0, position.currentRemainingMargin(10.0), 1.0e-9);
        assertEquals(670.0, position.currentRemainingMargin(8.0), 1.0e-9);
        assertEquals((0.50 - (1.15 - 1.0)) / 1.15 * 10.0 * 100.0,
                position.liquidationThreshold(10.0), 1.0e-9);
    }

    @Test
    void invalidQuotesCannotManufactureCurrentMarginOrThreshold() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 10,
                10.0, 500L, 0.0, 0.50, 1.15, 0.43, 0.0, 1L);
        assertEquals(500.0, position.currentRemainingMargin(0.0), 1.0e-9);
        assertEquals(0.0, position.liquidationThreshold(Double.NaN), 0.0);
        assertEquals(null, position.liquidationReason(Double.NaN));
    }

    @Test
    void priceLineReasonWinsWhenBothLiquidationConditionsAreTrue() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 100,
                10.0, 500L, 0.0, 0.50, 1.15, 0.43, 0.0, 1L);
        assertEquals("price_line", position.liquidationReason(11.5));
    }

    @Test
    void priceLineReasonIsShownEvenWhenMarginThresholdIsSafe() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 100,
                10.0, 500L, 0.0, 0.50, 1.15, 0.20, 0.0, 1L);
        // At 11.5: equity = 350, threshold = 230; only the price line is breached.
        assertEquals("price_line", position.liquidationReason(11.5));
    }

    @Test
    void dynamicMarginThresholdTriggersOnlyAfterInterestConsumesEquity() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 100,
                10.0, 500L, 0.0, 0.50, 1.15, 0.43, 0.0, 1L);
        // The constructor applies the price-line safety cap: at 11.0 the clean position is safe.
        assertEquals(null, position.liquidationReason(11.0));
        // Interest can legitimately make condition two fire before the price line.
        position.chargeInterest(100L);
        assertEquals("margin_below", position.liquidationReason(11.0));
        assertEquals(null, position.liquidationReason(10.0));
    }

    @Test
    void conditionTwoIsCappedByThePriceLineEquity() {
        ShortPosition position = new ShortPosition(UUID.randomUUID(), "COAL", 100,
                10.0, 500L, 0.0, 0.50, 1.15, 0.43, 0.0, 1L);
        double expectedCap = (0.50 - (1.15 - 1.0)) / 1.15;
        assertEquals(expectedCap, position.liquidationThreshold2, 1.0e-9);
        assertEquals("price_line", position.liquidationReason(11.5));
    }
}
