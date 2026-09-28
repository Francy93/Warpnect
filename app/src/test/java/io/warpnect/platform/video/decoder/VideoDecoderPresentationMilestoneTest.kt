package io.warpnect.platform.video.decoder

import io.warpnect.video.decoder.VideoDecoderState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderPresentationMilestoneTest {
    @Test
    fun onlySuccessfulSurfaceHandoffMarksTheClientStreamActive() {
        assertFalse(shouldMarkClientVideoStreaming(VideoDecoderDebugEvent.FirstOutputAvailable))
        assertTrue(shouldMarkClientVideoStreaming(VideoDecoderDebugEvent.FirstOutputReleasedForSurface))
        assertFalse(shouldMarkClientVideoStreaming(VideoDecoderDebugEvent.FirstCodecRenderCallback))
    }

    @Test
    fun delayedOrStaleRenderCallbacksCannotUpdateANewDecoderGeneration() {
        val oldCodec = Any()
        val currentCodec = Any()
        assertTrue(isCurrentDecoderRenderCallback(currentCodec, currentCodec, VideoDecoderState.Running))
        assertTrue(isCurrentDecoderRenderCallback(currentCodec, currentCodec, VideoDecoderState.Draining))
        assertFalse(isCurrentDecoderRenderCallback(oldCodec, currentCodec, VideoDecoderState.Running))
        assertFalse(isCurrentDecoderRenderCallback(oldCodec, null, VideoDecoderState.Stopped))
        assertFalse(isCurrentDecoderRenderCallback(currentCodec, currentCodec, VideoDecoderState.Stopped))
    }
}
