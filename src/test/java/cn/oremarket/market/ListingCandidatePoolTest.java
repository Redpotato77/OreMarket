package cn.oremarket.market;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingCandidatePoolTest {
    private static final int CODE_INDEX = 0;
    private static final int UNLOCK_INDEX = 4;
    private static final int MAX_POOL_ROWS = 64;
    private static final int ROW_COUNT = 70;
    private static final int LAST_POOL_INDEX = MAX_POOL_ROWS - 1;
    private static final double BASE_PRICE = 10.0;

    @Test
    void configuredPoolRejectsDuplicateCodesAndItems() {
        List<String> rows = List.of(
                "ALPHA|minecraft:emerald|Alpha|25|2",
                "ALPHA|minecraft:quartz|Duplicate code|30|1",
                "BETA|minecraft:emerald|Duplicate item|40|1",
                "GAMMA|minecraft:amethyst_shard|Gamma|50");

        List<String[]> pool = ListingService.candidates(rows, 1, itemId -> true, Set.of());

        assertEquals(2, pool.size());
        assertEquals("ALPHA", pool.get(0)[CODE_INDEX]);
        assertEquals("2", pool.get(0)[UNLOCK_INDEX]);
        assertEquals("GAMMA", pool.get(1)[CODE_INDEX]);
        assertEquals("1", pool.get(1)[UNLOCK_INDEX]);
    }

    @Test
    void configuredPoolRejectsItemsAlreadyUsedByListedStocks() {
        List<String> rows = List.of(
                "EMERALD2|minecraft:emerald|Emerald|25|1",
                "QUARTZ2|minecraft:quartz|Quartz|5|1");

        List<String[]> pool = ListingService.candidates(rows, 1, id -> true,
                Set.of("minecraft:emerald"));

        assertEquals(1, pool.size());
        assertEquals("QUARTZ2", pool.get(0)[CODE_INDEX]);
    }

    @Test
    void malformedOrUnknownRowsFallBackToFilteredDefaultPool() {
        List<String> rows = List.of(
                "bad code|minecraft:emerald|Bad|25|1",
                "UNKNOWN|minecraft:missing_item|Unknown|25|1");

        List<String[]> pool = ListingService.candidates(rows, 1, itemId -> false,
                Set.of("minecraft:emerald"));

        assertFalse(pool.isEmpty());
        assertTrue(pool.stream().noneMatch(row -> "EMERALD".equals(row[CODE_INDEX])));
        assertTrue(pool.stream().allMatch(row -> Stock.BUILTIN_CODES.stream()
                .noneMatch(code -> code.equals(row[CODE_INDEX]))));
    }

    @Test
    void candidatePoolIsCappedAtSixtyFourRows() {
        List<String> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < ROW_COUNT; rowIndex++) {
            rows.add("C" + rowIndex + "|minecraft:item" + rowIndex + "|Item|"
                    + (rowIndex + 1) + "|1");
        }

        List<String[]> pool = ListingService.candidates(rows, 1, itemId -> true, Set.of());

        assertEquals(MAX_POOL_ROWS, pool.size());
        assertEquals("C0", pool.get(0)[CODE_INDEX]);
        assertEquals("C63", pool.get(LAST_POOL_INDEX)[CODE_INDEX]);
    }

    @Test
    void relistingRejectsAnItemAlreadyUsedByAnotherActiveStock() {
        Stock active = Stock.custom("ACTIVE", "minecraft:emerald", "Active", BASE_PRICE, 2L, 1);
        Stock own = Stock.custom("RELIST", "minecraft:emerald", "Relist", BASE_PRICE, 2L, 1);

        assertTrue(ListingService.itemBoundByOtherActiveStock(own.itemId(), own.code(), List.of(active)));
        assertFalse(ListingService.itemBoundByOtherActiveStock(own.itemId(), own.code(), List.of(own)));
        assertFalse(ListingService.itemBoundByOtherActiveStock(null, own.code(), List.of(active)));
    }

    @Test
    void customConfigClaimsItsLeadingCode() {
        Set<String> claimed = ListingService.customClaimedCodes(List.of(
                "EMERALD|minecraft:emerald|Emerald|25|10|1",
                "  beta|minecraft:quartz|Beta|20|5|1 ",
                "TOO_LONG_CODE|minecraft:diorite|Invalid|20|5|1"));

        assertEquals(Set.of("EMERALD", "BETA"), claimed);
    }

    @Test
    void defaultPoolCodesRemainOutsideTheBuiltInMarket() {
        assertTrue(ListingService.DEFAULT_CANDIDATE_CODES.stream()
                .noneMatch(Stock.BUILTIN_CODES::contains));
    }
}
