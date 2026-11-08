package cn.oremarket.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToastTimelineTest {

    @Test
    void entryRampIsContinuousAndMonotonic() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        float previous = timeline.alphaAt(0L);
        assertEquals(0f, previous, 0.0001f);
        for (long now = 16L; now <= 250L; now += 16L) {
            float current = timeline.alphaAt(now);
            assertTrue(current >= previous, "entry opacity must not go backwards");
            assertTrue(current - previous <= 0.25f, "entry opacity must not jump between frames");
            previous = current;
        }
        assertEquals(1f, timeline.alphaAt(250L), 0.0001f);
        assertEquals(1f, timeline.alphaAt(2_000L), 0.0001f);
    }

    @Test
    void departureBecomesFullyHiddenImmediately() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        assertTrue(timeline.alphaAt(125L) > 0f);
        timeline.beginFadeOut(125L, 200L);
        assertTrue(timeline.isFadingOut());
        assertEquals(0f, timeline.alphaAt(125L), 0.0001f);
        assertEquals(0f, timeline.alphaAt(126L), 0.0001f);
        assertTrue(timeline.fadeOutFinished(125L));
    }

    @Test
    void repeatedFadeStartStaysImmediatelyHidden() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        timeline.beginFadeOut(250L, 200L);
        timeline.beginFadeOut(300L, 2_000L);
        assertEquals(0f, timeline.alphaAt(300L), 0.0001f);
        assertTrue(timeline.fadeOutFinished(300L));
    }

    @Test
    void cancelledAtomicDepartureCanReviveWithoutAHiddenIntermediateFrame() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        timeline.beginFadeOut(250L, 200L);
        assertEquals(0f, timeline.alphaAt(250L), 0.0001f);
        assertTrue(timeline.cancelFadeOut(250L, 150L));
        assertFalse(timeline.isFadingOut());
        assertEquals(0f, timeline.alphaAt(250L), 0.0001f);
        assertTrue(timeline.alphaAt(251L) >= 0f);
        assertEquals(1f, timeline.alphaAt(400L), 0.0001f);
    }

    @Test
    void cancellingAStableTimelineIsANoOp() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 1L);
        assertFalse(timeline.cancelFadeOut(100L, 150L));
        assertEquals(1f, timeline.alphaAt(100L), 0.0001f);
    }

    @Test
    void zeroLengthFadeCompletesImmediately() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 1L);
        timeline.beginFadeOut(10L, 0L);
        assertEquals(0f, timeline.alphaAt(10L), 0.0001f);
        assertTrue(timeline.fadeOutFinished(10L));
    }

    @Test
    void samplingRateDoesNotChangeAValueAtTheSameInstant() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        timeline.beginFadeOut(250L, 200L);
        for (long now = 250L; now <= 450L; now += 10L) {
            assertEquals(0f, timeline.alphaAt(now), 0.0001f);
        }
    }

    @Test
    void clockNeverMovesBackwardsWithinARamp() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        assertEquals(0f, timeline.alphaAt(-1_000L), 0.0001f);
        timeline.beginFadeOut(250L, 200L);
        assertEquals(1f, timeline.alphaAt(249L), 0.0001f);
    }

    @Test
    void lagSpikesNeverLeaveTheOpacityBounds() {
        ToastTimeline timeline = new ToastTimeline();
        timeline.beginEnter(0L, 250L);
        for (long now : new long[]{0L, 17L, 250L, 251L, 900L, 2_000L, 10_000L}) {
            float value = timeline.alphaAt(now);
            assertTrue(value >= 0f && value <= 1f);
        }
        timeline.beginFadeOut(2_000L, 200L);
        for (long now : new long[]{2_000L, 2_017L, 2_100L, 2_200L, 20_000L}) {
            float value = timeline.alphaAt(now);
            assertTrue(value >= 0f && value <= 1f);
        }
    }
}
