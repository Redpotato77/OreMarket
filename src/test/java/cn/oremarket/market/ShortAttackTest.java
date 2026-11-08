package cn.oremarket.market;

import cn.oremarket.common.ShortSettlement;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial short-position checks: forged close identities, invalid quantities and extreme settlement values.
 * must never release more collateral or create an accepted code-only close path.
 */
class ShortAttackTest {
    @Test
    void zeroAndNegativePositionIdsCannotAuthorizeAClose() {
        assertFalse(ShortPosition.validCloseId(0L));
        assertFalse(ShortPosition.validCloseId(-1L));
        assertTrue(ShortPosition.validCloseId(1L));
        assertTrue(ShortPosition.validCloseId(Long.MAX_VALUE));
    }

    @Test
    void forgedQuantitiesCannotReleaseCollateral() {
        assertEquals(0L, ShortSettlement.releasedLocked(1_000.0, 1_000L, -1, 100));
        assertEquals(0L, ShortSettlement.releasedLocked(1_000.0, 1_000L, 0, 100));
        assertEquals(1_000L, ShortSettlement.releasedLocked(1_000.0, 1_000L, Integer.MAX_VALUE, 100));
        assertEquals(7L, ShortSettlement.releasedLocked(1_000.0, 7L, 50, 100));
    }

    @Test
    void extremeLossDoesNotTurnIntoAnOverflowProfit() {
        assertEquals(Long.MIN_VALUE,
                ShortSettlement.payoutFullClose(0L, 1.0, 1.0e12, 10_000_000));
        assertEquals(0L,
                ShortSettlement.payoutWithFloor(0L, 1.0, 1.0e12, 10_000_000));
    }

    @Test
    void positionIdsRemainPositiveWhenConstructedFromNormalData() {
        ShortPosition position = new ShortPosition(
                UUID.randomUUID(), "COAL", 10, 10.0, 100L,
                1.0, 1.15, 0.43, 0.0, 1L);
        assertEquals(0L, position.id);
        assertFalse(ShortPosition.validCloseId(position.id));
    }

    @Test
    void duplicateCodeCannotOverwriteAnActivePosition() {
        MarketSavedData data = new MarketSavedData();
        UUID owner = UUID.randomUUID();
        ShortPosition first = new ShortPosition(owner, "COAL", 10, 10.0, 100L,
                1.0, 1.15, 0.43, 0.0, 1L);
        ShortPosition second = new ShortPosition(owner, "COAL", 20, 20.0, 200L,
                1.0, 1.15, 0.43, 0.0, 1L);
        assertTrue(data.openShort(first));
        assertFalse(data.openShort(second));
        assertEquals(first, data.shortPosition(owner, Stock.BUILTIN_TEMPLATES.get(0)));
        assertEquals(10, data.shortPosition(owner, Stock.BUILTIN_TEMPLATES.get(0)).quantity);
    }

    @Test
    void corruptIdCounterCannotMintZeroOrDuplicateIds() {
        MarketSavedData data = new MarketSavedData();
        data.nextShortId = Long.MAX_VALUE;
        UUID owner = UUID.randomUUID();
        ShortPosition first = new ShortPosition(owner, "COAL", 1, 10.0, 10L,
                1.0, 1.15, 0.43, 0.0, 1L);
        ShortPosition second = new ShortPosition(UUID.randomUUID(), "IRON", 1, 10.0, 10L,
                1.0, 1.15, 0.43, 0.0, 1L);
        data.openShort(first);
        data.openShort(second);
        assertTrue(ShortPosition.validCloseId(first.id));
        assertTrue(ShortPosition.validCloseId(second.id));
        assertTrue(first.id != second.id, "two active positions must never share a close ID");
    }
}
