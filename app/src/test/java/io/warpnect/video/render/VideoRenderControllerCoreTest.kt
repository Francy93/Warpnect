package io.warpnect.video.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRenderControllerCoreTest {
    @Test
    fun initialStateWaitsForSurface() {
        val core = VideoRenderControllerCore()

        assertEquals(VideoRenderState.WaitingForSurface, core.snapshot().state)
        assertFalse(core.snapshot().surfaceAvailable)
    }

    @Test
    fun surfaceGenerationAdvancesAcrossRecreation() {
        val core = VideoRenderControllerCore()

        assertEquals(1L, core.surfaceCreated(width = 640, height = 480))
        assertEquals(1L, core.snapshot().surfaceGeneration)
        assertTrue(core.snapshot().surfaceAvailable)

        core.surfaceDestroyed()
        assertEquals(VideoRenderState.SurfaceDestroyed, core.snapshot().state)
        assertFalse(core.snapshot().surfaceAvailable)

        assertEquals(2L, core.surfaceCreated(width = 1280, height = 720))
        assertEquals(2L, core.snapshot().surfaceGeneration)
        assertEquals(2L, core.snapshot().surfaceCreateCount)
        assertEquals(1L, core.snapshot().surfaceDestroyCount)
    }

    @Test
    fun surfaceChangedUpdatesSizeWithoutChangingGeneration() {
        val core = VideoRenderControllerCore()
        core.surfaceCreated(width = 640, height = 480)

        core.surfaceChanged(width = 800, height = 600)

        assertEquals(1L, core.snapshot().surfaceGeneration)
        assertEquals(800, core.snapshot().surfaceWidth)
        assertEquals(600, core.snapshot().surfaceHeight)
    }

    @Test
    fun closePreventsLaterSurfaceEventsFromReopeningRenderer() {
        val core = VideoRenderControllerCore()
        core.surfaceCreated(width = 640, height = 480)

        core.close()
        core.surfaceDestroyed()
        core.surfaceCreated(width = 800, height = 600)

        assertEquals(VideoRenderState.Closed, core.snapshot().state)
        assertFalse(core.snapshot().surfaceAvailable)
    }

    @Test
    fun geometryAndFrameRateValidationAreTyped() {
        val core = VideoRenderControllerCore()

        assertEquals(VideoRenderError.InvalidVideoGeometry, core.setVideoGeometry(0, 720))
        assertEquals(VideoRenderError.InvalidPreferredFrameRate, core.setPreferredFrameRate(Float.NaN))
        assertEquals(VideoRenderError.None, core.setVideoGeometry(1280, 720))
        assertEquals(VideoRenderError.None, core.setPreferredFrameRate(60f))
        assertEquals(60f, core.snapshot().preferredFrameRateHz)
    }

    @Test
    fun decisionCountersDistinguishRenderScheduleAndDrop() {
        val core = VideoRenderControllerCore()
        core.surfaceCreated(width = 640, height = 480)

        core.recordDecision(VideoRenderDecision.RenderImmediately, presentationTimeUs = 1_000)
        core.recordDecision(VideoRenderDecision.RenderAtLocalTime(timestampNs = 20_000), presentationTimeUs = 2_000)
        core.recordDecision(VideoRenderDecision.Drop, presentationTimeUs = 3_000)

        assertEquals(3L, core.snapshot().framesReceived)
        assertEquals(1L, core.snapshot().renderNowDecisions)
        assertEquals(1L, core.snapshot().scheduledRenderDecisions)
        assertEquals(1L, core.snapshot().dropDecisions)
        assertEquals(3_000L, core.snapshot().lastFramePtsUs)
        assertEquals(20_000L, core.snapshot().lastScheduledTimestampNs)
    }
}
