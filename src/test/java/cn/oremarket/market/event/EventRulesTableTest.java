package cn.oremarket.market.event;

import cn.oremarket.market.ListingService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Data-integrity unit tests for the six rule tables: combo / chain / conflict / forecast / player-trigger / trigger-type effects.
 * All pure JDK: EventRegistry's static tables touch no game runtime.
 */
class EventRulesTableTest {

    @Test
    void comboTableHasTenRulesWithResolvableIds() {
        assertEquals(10, EventComboTable.RULES.size());
        Set<String> children = new HashSet<>();
        for (EventComboTable.ComboRule r : EventComboTable.RULES) {
            assertNotNull(EventRegistry.byId(r.childId), "combo child " + r.childId);
            assertNotNull(EventRegistry.byId(r.parentA), "combo parentA " + r.parentA);
            assertNotNull(EventRegistry.byId(r.parentB), "combo parentB " + r.parentB);
            assertTrue(children.add(r.childId), "duplicate combo child id: " + r.childId);
        }
    }

    @Test
    void chainTableHasFiveRulesFiringAfterParentTicks() {
        assertEquals(5, EventChainTable.RULES.size());
        for (EventChainTable.ChainRule r : EventChainTable.RULES) {
            assertNotNull(EventRegistry.byId(r.parentTemplateId), "chain parent " + r.parentTemplateId);
            assertNotNull(EventRegistry.byId(r.childTemplateId), "chain child " + r.childTemplateId);
            assertTrue(r.afterTicks >= 1 && r.afterTicks <= 10, "chain delay must be within 1..10 marketTicks");
        }
    }

    @Test
    void specialTemplatesRegistered() {
        assertNotNull(EventRegistry.byId(EventRegistry.CONFLICT_WATCH_ID));
        assertNotNull(EventRegistry.byId(EventRegistry.PREVIEW_ID));
        assertNotNull(EventRegistry.byId("player_frenzy"));
        assertNotNull(EventRegistry.byId("player_panic"));
        assertNotNull(EventRegistry.byId("gold_haven"));
        assertNotNull(EventRegistry.byId("industry_reboot"));
        for (String id : new String[]{"rumor_news_coal", "rumor_news_diamond", "rumor_news_rate",
                "rumor_news_redstone", "rumor_news_netherite"}) {
            assertNotNull(EventRegistry.byId(id));
        }
        assertNotNull(EventRegistry.byId("inst_force_buy"));
        assertNotNull(EventRegistry.byId("inst_force_sell"));
        assertNotNull(EventRegistry.byId("inst_liquidity_dry"));
        assertNotNull(EventRegistry.byId("inst_dividend"));
        assertNotNull(EventRegistry.byId("inst_stock_split"));
    }

    @Test
    void legacyCardsKeepTheirMarketTickDurations() {
        assertEquals(15, EventRegistry.byId("legacy_great_vein").fixedTicks);
        assertEquals(20, EventRegistry.byId("legacy_diamond_fever").fixedTicks);
        assertEquals(10, EventRegistry.byId("legacy_rate_hike").fixedTicks);
        assertEquals(15, EventRegistry.byId("legacy_market_frenzy").fixedTicks);
    }

    @Test
    void rumorTargetsMatchTheirDescriptions() {
        assertEquals(Set.of("COAL"), EventRegistry.byId("rumor_news_coal").fixedTargets);
        assertEquals(Set.of("DIAMOND"), EventRegistry.byId("rumor_news_diamond").fixedTargets);
        assertEquals(Set.of("REDSTONE"), EventRegistry.byId("rumor_news_redstone").fixedTargets);
        assertEquals(Set.of("NETHERITE"), EventRegistry.byId("rumor_news_netherite").fixedTargets);
        assertEquals(EventTemplate.TargetMode.ALL, EventRegistry.byId("rumor_news_rate").targetMode);
    }

    @Test
    void previewAndRumorTemplatesCarryFlags() {
        assertTrue(EventRegistry.byId(EventRegistry.PREVIEW_ID).preview);
        assertTrue(EventRegistry.byId("rumor_news_coal").fakeNews);
        assertTrue(EventRegistry.byId("legacy_market_frenzy").fakeNews == false,
                "a true event must not carry the fake-news flag");
        // Forecast card lasts 1 tick; rumour card lasts 60 seconds
        assertEquals(1, EventRegistry.byId(EventRegistry.PREVIEW_ID).fixedTicks);
        assertEquals(60.0, EventRegistry.byId("rumor_news_coal").fixedSeconds, 1e-9);
    }

    @Test
    void customRegistryReplacesAtomicallyAndCleansUp() {
        int before = EventRegistry.all().size();
        EventTemplate t = EventTemplate.of(EventRegistry.customId("\u5355\u6D4B\u4E8B\u4EF6"), "\u5355\u6D4B\u4E8B\u4EF6",
                cn.oremarket.common.Rarity.COMMON)
                .fixedCodes("COAL").fixedSeconds(30).custom()
                .effect(cn.oremarket.common.EffectSpec.price(1.2, 1.2))
                .build();
        EventRegistry.replaceCustoms(java.util.List.of(t));
        assertEquals(before + 1, EventRegistry.all().size());
        assertNotNull(EventRegistry.byId(EventRegistry.customId("\u5355\u6D4B\u4E8B\u4EF6")));
        // Hot-reload cleanup: the second swap must replace everything wholesale, leaving no old custom template
        EventRegistry.replaceCustoms(java.util.List.of());
        assertEquals(before, EventRegistry.all().size());
        assertNull(EventRegistry.byId(EventRegistry.customId("\u5355\u6D4B\u4E8B\u4EF6")));
    }

    @Test
    void splitConservationInvariantHoldsOnlyForTrueDoubling() {
        assertTrue(EventEffectsService.conservationHolds(10.0, 100, 5.0, 200));
        assertTrue(EventEffectsService.conservationHolds(0.01, 7, 0.005, 14));
        // Doubling only the amount without touching the price breaks conservation, so it must return false
        assertTrue(!EventEffectsService.conservationHolds(10.0, 100, 10.0, 200));
    }

    @Test
    void stockSplitPreservesShortContractAccounting() {
        java.util.UUID owner = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        cn.oremarket.market.ShortPosition position =
                new cn.oremarket.market.ShortPosition(
                        owner, "COAL", 100, 10.0, 1500L, 30.0, 1.5, 1.3, 1.0, 0.03, 4L);
        position.id = 77L;
        position.releaseLocked(100.0);
        position.chargeInterest(20L);

        position.applyStockSplit(2);

        assertEquals(200, position.quantity);
        assertEquals(200, position.originalQuantity);
        assertEquals(5.0, position.openPrice, 0.0);
        assertEquals(6.5, position.liquidationPrice(), 0.0);
        assertEquals(1510L, position.margin);
        assertEquals(1530.0, position.lockedMargin, 0.0);
        assertEquals(100.0, position.lockedReleased, 0.0);
        assertEquals(20.0, position.interestPaid, 0.0);
        assertEquals(77L, position.id);
        assertEquals(1000.0, position.openMarketValue(), 0.0);
    }

    @Test
    void mineralSpecificTemplatesNeverAcceptCustomStockCodes() {
        EventTemplate mineral = EventRegistry.byId("legacy_great_vein");
        EventTemplate marketWide = EventRegistry.byId("legacy_market_frenzy");
        EventTemplate generic = EventRegistry.byId("local_quote");

        assertEquals(EventTemplate.TargetScope.BUILTIN_MINERALS, mineral.targetScope);
        assertTrue(mineral.allowsCode("IRON"));
        assertTrue(!mineral.allowsCode("SWORD"));
        assertEquals(EventTemplate.TargetScope.ACTIVE, marketWide.targetScope);
        assertTrue(marketWide.allowsCode("SWORD"));
        assertEquals(EventTemplate.TargetScope.ACTIVE, generic.targetScope);
        assertTrue(generic.allowsCode("SWORD"));
        assertEquals(EventTemplate.TargetScope.BUILTIN_MINERALS,
                EventRegistry.byId("mine_collapse").targetScope);
        assertEquals(EventTemplate.TargetScope.BUILTIN_MINERALS,
                EventRegistry.byId("vein_depletion").targetScope);
    }

    @Test
    void defaultCandidatePoolUsesGenericEventsOnly() {
        assertTrue(ListingService.DEFAULT_CANDIDATE_CODES.contains("EMERALD"));
        assertTrue(ListingService.DEFAULT_CANDIDATE_CODES.contains("QUARTZ"));
        assertTrue(ListingService.DEFAULT_CANDIDATE_CODES.stream()
                .noneMatch(StockCodesForTest::isBuiltinCode));

        EventTemplate generic = EventRegistry.byId("local_quote");
        EventTemplate mineralOnly = EventRegistry.byId("quiet_mine");
        assertEquals(EventTemplate.TargetScope.ACTIVE, generic.targetScope);
        assertTrue(generic.allowsCode("EMERALD"));
        assertTrue(generic.allowsCode("SWORD"));
        assertEquals(EventTemplate.TargetScope.BUILTIN_MINERALS, mineralOnly.targetScope);
        assertTrue(!mineralOnly.allowsCode("EMERALD"));
        assertTrue(!mineralOnly.allowsCode("SWORD"));
    }

    private static final class StockCodesForTest {
        private static boolean isBuiltinCode(String code) {
            return cn.oremarket.market.Stock.BUILTIN_CODES.contains(code);
        }
    }

    @Test
    void builtinNamesCoversLegacyCardsAndTemplates() {
        Set<String> names = EventRegistry.builtinNames();
        assertTrue(names.contains("\u77FF\u8109\u5927\u53D1\u73B0"), "built-in card name must stay in the reserved set");
        assertTrue(names.contains("\u9EC4\u91D1\u907F\u9669"), "new-pool template name must stay in the reserved set");
        assertTrue(names.stream().noneMatch(n -> n == null || n.isBlank()));
    }
}
