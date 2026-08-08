package io.warpnect.video.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderInputSlotTrackerTest {
    @Test
    fun retainsInputIndicesWithoutDuplicates() {
        val tracker = AvailableInputSlotTracker()

        assertTrue(tracker.retain(3))
        assertFalse(tracker.retain(3))
        assertTrue(tracker.contains(3))
        assertEquals(1, tracker.size)
    }

    @Test
    fun drainReturnsAndClearsHeldIndices() {
        val tracker = AvailableInputSlotTracker()
        tracker.retain(3)
        tracker.retain(4)

        assertEquals(listOf(3, 4), tracker.drain())
        assertEquals(0, tracker.size)
    }

    @Test
    fun outputReleasePlannerMapsActions() {
        assertEquals(
            DecodedVideoOutputRelease.BooleanRelease(render = true),
            DecodedVideoOutputReleasePlanner.plan(DecodedVideoOutputAction.RenderNow),
        )
        assertEquals(
            DecodedVideoOutputRelease.BooleanRelease(render = false),
            DecodedVideoOutputReleasePlanner.plan(DecodedVideoOutputAction.Drop),
        )
        assertEquals(
            DecodedVideoOutputRelease.ScheduledRelease(timestampNs = 123),
            DecodedVideoOutputReleasePlanner.plan(DecodedVideoOutputAction.RenderAt(timestampNs = 123)),
        )
    }
}
