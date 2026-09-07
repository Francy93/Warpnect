package io.warpnect.video.session

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoReceiverSessionCoreTest {
    @Test
    fun surfaceCreatedBeforeSessionStartIsRememberedWithoutStartingTheSession() {
        val core = VideoReceiverSessionCore()

        assertEquals(VideoSessionState.Idle, core.onSurfaceAvailable())
        assertEquals(VideoSessionState.WaitingForConfig, core.start(surfaceAvailable = true, configAvailable = false))
    }

    @Test
    fun surfaceDestroyedBeforeSessionStartKeepsTheSessionIdle() {
        val core = VideoReceiverSessionCore()

        core.onSurfaceAvailable()

        assertEquals(VideoSessionState.Idle, core.onSurfaceDestroyed())
        assertEquals(VideoSessionState.WaitingForSurface, core.start(surfaceAvailable = false, configAvailable = false))
    }

    @Test
    fun surfaceFirstThenConfigPreparesDecoderAndWaitsForKeyframe() {
        val core = VideoReceiverSessionCore()

        assertEquals(VideoSessionState.WaitingForSurface, core.start(surfaceAvailable = false, configAvailable = false))
        assertEquals(VideoSessionState.WaitingForConfig, core.onSurfaceAvailable())
        assertEquals(VideoSessionState.PreparingDecoder, core.onStreamConfigReady())
        assertEquals(VideoSessionState.WaitingForKeyFrame, core.onDecoderPrepared())
        assertEquals(VideoSessionState.WaitingForKeyFrame, core.onAccessUnitReady(keyframe = false))
        assertEquals(VideoSessionState.Streaming, core.onAccessUnitReady(keyframe = true))
    }

    @Test
    fun configFirstWaitsForSurfaceBeforePrepare() {
        val core = VideoReceiverSessionCore()

        assertEquals(VideoSessionState.WaitingForSurface, core.start(surfaceAvailable = false, configAvailable = false))
        assertEquals(VideoSessionState.WaitingForSurface, core.onStreamConfigReady())
        assertEquals(VideoSessionState.PreparingDecoder, core.onSurfaceAvailable())
    }

    @Test
    fun discontinuityReturnsToKeyframeGateWhenPrerequisitesRemainAvailable() {
        val core = VideoReceiverSessionCore()

        assertEquals(VideoSessionState.PreparingDecoder, core.start(surfaceAvailable = true, configAvailable = true))
        assertEquals(VideoSessionState.WaitingForKeyFrame, core.onDecoderPrepared())
        assertEquals(VideoSessionState.Streaming, core.onAccessUnitReady(keyframe = true))
        assertEquals(VideoSessionState.WaitingForKeyFrame, core.onDiscontinuity())
    }

    @Test
    fun surfaceDestroyedInvalidatesStreamingUntilRecreated() {
        val core = VideoReceiverSessionCore()

        assertEquals(VideoSessionState.PreparingDecoder, core.start(surfaceAvailable = true, configAvailable = true))
        assertEquals(VideoSessionState.WaitingForKeyFrame, core.onDecoderPrepared())
        assertEquals(VideoSessionState.Streaming, core.onAccessUnitReady(keyframe = true))
        assertEquals(VideoSessionState.WaitingForSurface, core.onSurfaceDestroyed())
        assertEquals(VideoSessionState.PreparingDecoder, core.onSurfaceAvailable())
    }
}
