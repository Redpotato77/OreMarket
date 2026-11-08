package cn.oremarket.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Attack cases for numeric units and fee rates: boundary values must not overflow, and non-finite fee rates must not create free trades. */
final class NumberAndFeesAttackTest {
    private static final double FIFTEEN_PERCENT = 0.15;
    private static final double UNIT_PRICE = 10.0;
    private static final double FOUR_COINS = 4.0;
    private static final int ONE_SHARE = 1;
    private static final int TEN_SHARES = 10;
    private static final long ELEVEN_COINS = 11L;
    private static final long FOURTEEN_COINS = 14L;
    private static final long TWELVE_COINS = 12L;
    private static final long RESERVED_COINS = 41L;
    private static final double EXPECTED_SINGLE_BUY_COST = 11.0;
    private static final double EXPECTED_TWO_LOT_AVERAGE = 12.0;
    private static final double EXPECTED_OLD_BASIS = 10.0;
    private static final double EXPECTED_ZERO_CHARGE_AVERAGE = 20.0 / 3.0;

    @Test
    void unitMappingKeepsChineseAndEnglishScalesAligned() {
        assertEquals("1.0\u4EBF", Num.unit(1.0e8, true, 1));
        assertEquals("100.0M", Num.unit(1.0e8, false, 1));
        assertEquals("1.0\u4E07", Num.unit(1.0e4, true, 1));
        assertEquals("10.0K", Num.unit(1.0e4, false, 1));
        assertEquals("1.0B", Num.unit(1.0e9, false, 1));
        assertEquals("1.0T", Num.unit(1.0e12, false, 1));
    }

    @Test
    void numericExtremesStayRenderable() {
        assertEquals("0.0", Num.unit(Double.NaN, false, 1));
        assertEquals("-0.0", Num.signed(-0.0, 1));
        assertFalse(Num.unit(Double.MAX_VALUE, false, 1).isEmpty());
        assertFalse(Num.fixed(Double.POSITIVE_INFINITY, 2).isEmpty());
    }

    @Test
    void cumulativePriceShockTextCannotOverflowOrGrowWithoutBound() {
        assertEquals("+15.0%", PriceShockMath.cumulativePercentText(1.15, 1));
        assertEquals("", PriceShockMath.cumulativePercentText(1.15, 300));
        assertEquals("+15.0%", PriceShockMath.percentText(FIFTEEN_PERCENT));
        assertEquals("+0.0%", PriceShockMath.percentText(0.0));
        assertEquals("", PriceShockMath.percentText(10.0));
        assertTrue(PriceShockMath.exceedsDisplayRange(10.0));
        assertFalse(PriceShockMath.exceedsDisplayRange(FIFTEEN_PERCENT));
        assertEquals("-100.0%", PriceShockMath.cumulativePercentText(0.85, Integer.MAX_VALUE));
        assertEquals("+0.0%", PriceShockMath.cumulativePercentText(Double.POSITIVE_INFINITY, 300));
        assertEquals(1.0, cn.oremarket.market.MarketSavedData.clampEventMultiplier(Double.NaN));
        assertTrue(PriceShockMath.apply(10.0, 1.15, 300, 2.5, 40.0) <= 40.0);
        assertTrue(PriceShockMath.apply(10.0, 0.85, 300, 2.5, 40.0) >= 2.5);
    }

    @Test
    void weightedCostUsesActualChargedCoinsAndKeepsOldBasis() {
        assertEquals(EXPECTED_SINGLE_BUY_COST,
                Fees.weightedAverageBuyCost(0, 0.0, ELEVEN_COINS, ONE_SHARE), 0.0);
        assertEquals(EXPECTED_TWO_LOT_AVERAGE,
                Fees.weightedAverageBuyCost(1, UNIT_PRICE, FOURTEEN_COINS, ONE_SHARE), 0.0);
        assertEquals(EXPECTED_ZERO_CHARGE_AVERAGE,
                Fees.weightedAverageBuyCost(2, UNIT_PRICE, 0L, ONE_SHARE), 0.0);
        assertEquals(EXPECTED_OLD_BASIS,
                Fees.weightedAverageBuyCost(2, UNIT_PRICE, TWELVE_COINS, 0), 0.0);
    }

    @Test
    void feeInclusiveBuyReservationRoundsUpWithoutOverflow() {
        assertEquals(RESERVED_COINS, Fees.buyCost(FOUR_COINS, TEN_SHARES, 0.01));
        assertEquals(Long.MAX_VALUE, Fees.buyCost(Double.MAX_VALUE, Integer.MAX_VALUE, 0.01));
    }

    @Test
    void nonFiniteRatesAreClampedBeforeSettlement() {
        assertEquals(0.0, Fees.effectiveFeeRate(Double.NaN));
        assertEquals(0.0, Fees.effectiveFeeRate(Double.POSITIVE_INFINITY));
        assertEquals(50L, Fees.buyCost(10.0, 5, Double.NaN));
        assertEquals(1.0, Fees.additive(Double.POSITIVE_INFINITY, 1.0));
        assertEquals(35L, Fees.sellGain(10.0, 5, 0.3));
    }
}
