package cn.oremarket.market;

import cn.oremarket.common.StockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketLimitReliefTest {

    @Test
    void repeatedLimitHitsResetWhenPressureStops() {
        StockState state = StockState.empty(Stock.COAL);
        assertEquals(1, state.noteLimitHit(1));
        assertEquals(2, state.noteLimitHit(1));
        assertEquals(0, state.noteLimitHit(0));
        assertEquals(1, state.noteLimitHit(1));
        assertEquals(1, state.limitHitStreak());
    }

    @Test
    void directionChangeStartsANewStreak() {
        StockState state = StockState.empty(Stock.COAL);
        assertEquals(1, state.noteLimitHit(-1));
        assertEquals(1, state.noteLimitHit(1));
        assertEquals(1, state.limitHitStreak());
    }

    @Test
    void auctionReliefStaysInsideTheConfiguredBand() {
        double previous = 100.0;
        double limit = 0.10;
        double upper = previous * (1.0 + limit);
        double lower = previous * (1.0 - limit);

        double relievedUp = MarketEngine.auctionRelief(upper, previous, limit, 1);
        double relievedDown = MarketEngine.auctionRelief(lower, previous, limit, -1);

        assertTrue(relievedUp >= previous && relievedUp <= upper);
        assertTrue(relievedDown <= previous && relievedDown >= lower);
        assertEquals(109.75, relievedUp, 1.0e-9);
        assertEquals(90.25, relievedDown, 1.0e-9);
    }

    @Test
    void boundaryReliefCreatesAnInwardWickWithoutCrossingTheBand() {
        double relievedUp = MarketEngine.boundaryAuctionRelief(48.0, 48.0, 3.0, 48.0, 0.10, 1);
        double relievedDown = MarketEngine.boundaryAuctionRelief(3.0, 3.0, 3.0, 48.0, 0.10, -1);

        assertEquals(47.88, relievedUp, 1.0e-9);
        assertEquals(3.0075, relievedDown, 1.0e-9);
        assertTrue(relievedUp >= 3.0 && relievedUp <= 48.0);
        assertTrue(relievedDown >= 3.0 && relievedDown <= 48.0);
    }

    @Test
    void boundaryReliefDoesNotMoveAnUnpinnedQuote() {
        assertEquals(47.0, MarketEngine.boundaryAuctionRelief(47.0, 47.0, 3.0, 48.0, 0.10, 1), 1.0e-9);
        assertEquals(4.0, MarketEngine.boundaryAuctionRelief(4.0, 4.0, 3.0, 48.0, 0.10, -1), 1.0e-9);
    }

    @Test
    void sealedPatternReachesTheLegalEdgeWithoutCrossingIt() {
        assertEquals(110.0, MarketEngine.sealedLimitQuote(110.0, 100.0, 0.10, 1, 1.0, 1000.0), 1.0e-9);
        assertEquals(90.0, MarketEngine.sealedLimitQuote(90.0, 100.0, 0.10, -1, 1.0, 1000.0), 1.0e-9);
    }

    @Test
    void sealedPatternKeepsAdvancingTowardLongRunEdges() {
        double up = 100.0;
        for (int i = 0; i < 20 && up < 400.0; i++) {
            up = MarketEngine.sealedLimitQuote(up * 1.10, up, 0.10, 1, 25.0, 400.0);
        }
        double down = 100.0;
        for (int i = 0; i < 20 && down > 25.0; i++) {
            down = MarketEngine.sealedLimitQuote(down * 0.90, down, 0.10, -1, 25.0, 400.0);
        }
        assertEquals(400.0, up, 1.0e-9);
        assertEquals(25.0, down, 1.0e-9);
    }

    @Test
    void boundaryApproachReachesTheExactLongRunEdge() {
        assertEquals(400.0, MarketEngine.boundaryQuote(400.0, 900.0, 390.0,
                25.0, 400.0, 0.10, 0.0), 1.0e-9);
        assertEquals(25.0, MarketEngine.boundaryQuote(25.0, 0.01, 27.5,
                25.0, 400.0, 0.10, 1.0), 1.0e-9);
    }

    @Test
    void limitPatternDistributionCoversAllFourShapes() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String code : new String[]{"COAL", "COPPER", "IRON", "GOLD", "REDSTONE", "LAPIS", "DIAMOND", "NETHERITE"}) {
            seen.add(MarketEngine.limitPattern(code, 1));
            seen.add(MarketEngine.limitPattern(code, -1));
        }
        assertEquals(java.util.Set.of(0, 1, 2, 3), seen);
    }

    @Test
    void sustainedBoundaryPressureReachesExactLongRunEdges() {
        double upper = MarketEngine.boundaryQuote(600.0, 900.0, 600.0,
                3.0, 600.0, 0.10, 0.0);
        double lower = MarketEngine.boundaryQuote(3.0, 0.01, 3.0,
                3.0, 600.0, 0.10, 1.0);
        assertEquals(600.0, upper, 1.0e-9);
        assertEquals(3.0, lower, 1.0e-9);
    }
}
