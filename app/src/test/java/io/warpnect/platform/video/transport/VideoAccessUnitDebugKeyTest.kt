package io.warpnect.platform.video.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAccessUnitDebugKeyTest {
    @Test
    fun repeatedNativeReadinessForSameFrameDoesNotConsumeObservationBudget() {
        val first = VideoAccessUnitDebugKey(configGeneration = 1, frameId = 42)
        assertTrue(isDistinctVideoAccessUnitDebugKey(null, first))
        assertFalse(isDistinctVideoAccessUnitDebugKey(first, first))
        assertTrue(isDistinctVideoAccessUnitDebugKey(first, first.copy(frameId = 43)))
        assertTrue(isDistinctVideoAccessUnitDebugKey(first, first.copy(configGeneration = 2)))
    }
}
