package io.warpnect.video.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VideoEncoderFormatPlannerTest {
    private val request = VideoEncoderRequest(
        width = 1920,
        height = 1080,
        frameRate = 60,
        bitrateBps = 12_000_000,
        iFrameIntervalSeconds = 1f,
    )

    @Test
    fun requiredAvcSurfaceFormatValuesArePlanned() {
        val plan = VideoEncoderFormatPlanner.build(
            request,
            VideoEncoderFormatSupport(
                supportsPriority = true,
                supportsLatency = true,
                supportsMaxBFrames = true,
                supportsMaxFpsToEncoder = true,
            ),
        )

        assertEquals("video/avc", plan.mimeType)
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
        assertEquals(VideoEncoderFormatPlanner.COLOR_FORMAT_SURFACE, plan.colorFormat)
        assertEquals(12_000_000, plan.bitrateBps)
        assertEquals(VideoEncoderFormatPlanner.BITRATE_MODE_CBR, plan.bitrateMode)
        assertEquals(60, plan.frameRate)
        assertEquals(1f, plan.iFrameIntervalSeconds)
        assertEquals(0, plan.priority)
        assertEquals(1, plan.latencyFrames)
        assertEquals(0, plan.maxBFrames)
        assertEquals(60f, plan.maxFpsToEncoder)
    }

    @Test
    fun apiGatedHintsAreOmittedWhenUnsupported() {
        val plan = VideoEncoderFormatPlanner.build(
            request,
            VideoEncoderFormatSupport(
                supportsPriority = false,
                supportsLatency = false,
                supportsMaxBFrames = false,
                supportsMaxFpsToEncoder = false,
            ),
        )

        assertNull(plan.priority)
        assertNull(plan.latencyFrames)
        assertNull(plan.maxBFrames)
        assertNull(plan.maxFpsToEncoder)
    }

    @Test
    fun decoderOnlyLowLatencyKeyIsNotPlanned() {
        val planProperties = VideoEncoderFormatPlan::class.java.declaredFields.map { it.name }

        assertFalse(planProperties.any { it.contains("low", ignoreCase = true) })
    }
}
