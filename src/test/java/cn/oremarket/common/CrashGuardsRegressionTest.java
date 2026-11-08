package cn.oremarket.common;

import cn.oremarket.market.ActiveEvent;
import cn.oremarket.market.MarketSavedData;
import cn.oremarket.market.event.EventComboTable;
import cn.oremarket.market.event.EventRegistry;
import cn.oremarket.market.event.EventTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash-safety tests for event forecasting and key pruning:
 * - A missing module plan must produce a valid zero-effect legendary forecast;
 * - Combo and chain deduplication keys must remain distinct during pruning.
 */
class CrashGuardsRegressionTest {

    private static EventTemplate previewTemplate() {
        EventTemplate tpl = EventRegistry.byId(EventRegistry.PREVIEW_ID);
        assertNotNull(tpl, "the legendary preview template must stay registered");
        return tpl;
    }

    /** Regression: a null module plan (the old forecast call convention) must not throw NPE and must yield a zero-effect event. */
    @Test
    void spawnFixedToleratesNullModulePlans() {
        ActiveEvent e = assertDoesNotThrow(() ->
                ActiveEvent.spawnFixed(previewTemplate(), Set.of("COAL"), 1,
                        null, 42, 0, "\u9EC4\u91D1\u4F20\u5947"));
        assertNotNull(e);
        assertTrue(e.modules.isEmpty(), "the preview card must carry no effect module");
        assertEquals(42, e.uid);
        assertTrue(e.duration >= 1, "event duration is at least one marketTick");
    }

    /** New convention: an explicit empty list is equally safe. */
    @Test
    void spawnFixedAcceptsEmptyModulePlans() {
        ActiveEvent e = assertDoesNotThrow(() ->
                ActiveEvent.spawnFixed(previewTemplate(), Set.of("COAL"), 1,
                        List.of(), 7, 0, "\u9EC4\u91D1\u4F20\u5947"));
        assertTrue(e.modules.isEmpty());
    }

    /**
     * Regression: pruneFiredKeys must distinguish the two kinds of keys:
     * combo keys childId|uidA|uidB (both segments are parent uids);
     * chain keys chain|childId|parentUid (the middle segment is a template name, not a uid).
     * The old implementation validated the middle segment of chain keys under the combo rule,
     * so every chain key was wiped from the dedup set on each tick.
     */
    @Test
    void pruneFiredKeysKeepsChainKeyWithLiveParentUid() {
        MarketSavedData data = new MarketSavedData();
        // Build a parent event with uid=7 and put it in the event pool
        ActiveEvent parent = ActiveEvent.spawnFixed(previewTemplate(), Set.of("COAL"), 5, List.of(), 7, 0, null);
        data.events.add(parent);

        data.comboFiredKeys.add("chain|gold_haven|7");     // Chain: parent 7 alive in pool -> must be kept
        data.comboFiredKeys.add("chain|gold_haven|99");    // Chain: parent 99 not in pool -> must be pruned
        data.comboFiredKeys.add("market_chaos|7|7");       // Combo: both ends in pool -> kept
        data.comboFiredKeys.add("market_chaos|7|99");      // Combo: one end missing from pool -> pruned
        data.comboFiredKeys.add("garbage_no_pipes");       // Malformed key -> pruned

        EventComboTable.pruneFiredKeys(data);

        assertTrue(data.comboFiredKeys.contains("chain|gold_haven|7"),
                "chain dedup keys must survive while the parent event lives (otherwise the same parent could chain again)");
        assertFalse(data.comboFiredKeys.contains("chain|gold_haven|99"),
                "chain keys of vanished parents must be pruned (anti bloat)");
        assertTrue(data.comboFiredKeys.contains("market_chaos|7|7"));
        assertFalse(data.comboFiredKeys.contains("market_chaos|7|99"));
        assertFalse(data.comboFiredKeys.contains("garbage_no_pipes"));
    }

    @Test
    void eventTimersRescaleWithoutMultiplyingRealTime() {
        ActiveEvent event = ActiveEvent.spawnFixed(previewTemplate(), Set.of("COAL"), 120,
                List.of(), 9, 0, null);
        event.ticksLeft = 60;
        event.rescaleTimeBasis(1, 60);
        assertEquals(2, event.duration);
        assertEquals(1, event.ticksLeft);
        event.rescaleTimeBasis(60, 1);
        assertEquals(120, event.duration);
        assertEquals(60, event.ticksLeft);
    }

    /** Server-shutdown window reset: after EventEngine.clearAll, a new world is not polluted by absolute ticks from the old window. */
    @Test
    void eventEngineWindowStateResetsOnServerStop() {
        // Only verify the entry point exists and can be called safely from the test thread (state is private static; behaviour is constrained by the implementation's comments)
        assertDoesNotThrow(cn.oremarket.market.event.EventEngine::clearAll);
    }
}
