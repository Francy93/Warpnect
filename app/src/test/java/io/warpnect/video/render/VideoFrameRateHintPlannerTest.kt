package io.warpnect.video.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRateHintPlannerTest {
    @Test
    fun validatesFrameRatePreference() {
        assertEquals(VideoRenderError.None, VideoFrameRateHintPlanner.validate(null))
        assertEquals(VideoRenderError.None, VideoFrameRateHintPlanner.validate(60f))
        assertEquals(VideoRenderError.InvalidPreferredFrameRate, VideoFrameRateHintPlanner.validate(Float.NaN))
        assertEquals(
            VideoRenderError.InvalidPreferredFrameRate,
            VideoFrameRateHintPlanner.validate(Float.POSITIVE_INFINITY),
        )
        assertEquals(VideoRenderError.InvalidPreferredFrameRate, VideoFrameRateHintPlanner.validate(-1f))
        assertEquals(VideoRenderError.InvalidPreferredFrameRate, VideoFrameRateHintPlanner.validate(0f))
    }

    @Test
    fun nullPreferencePlansClearHint() {
        val plan = VideoFrameRateHintPlanner.plan(null, supportsChangeStrategy = false)

        assertTrue(plan.clearsPreference)
        assertEquals(0f, plan.frameRateHz)
        assertNull(plan.changeStrategy)
    }

    @Test
    fun validPreferenceUsesSeamlessChangeStrategyWhenSupported() {
        val plan = VideoFrameRateHintPlanner.plan(60f, supportsChangeStrategy = true)

        assertFalse(plan.clearsPreference)
        assertEquals(60f, plan.frameRateHz)
        assertEquals(VideoFrameRateCompatibility.FixedSource, plan.compatibility)
        assertEquals(VideoFrameRateChangeStrategy.OnlyIfSeamless, plan.changeStrategy)
    }
}
