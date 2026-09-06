package io.warpnect.platform.audio.capture

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureResult
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.PcmAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureCapabilityQueryTest {
    private val request = AudioCaptureRequest(source = AudioCaptureSource.SystemAudio)

    @Test
    fun closesControllerAfterCapabilityQuery() {
        val controller = FakeController()

        val result = queryCapabilitiesAndClose(controller, request)

        assertTrue(result.available)
        assertEquals(1, controller.closeCount)
    }

    @Test
    fun closesControllerWhenCapabilityQueryFails() {
        val controller = FakeController(queryFailure = IllegalStateException("expected"))

        val result = runCatching { queryCapabilitiesAndClose(controller, request) }

        assertTrue(result.isFailure)
        assertEquals(1, controller.closeCount)
    }

    private class FakeController(
        private val queryFailure: RuntimeException? = null,
    ) : AudioCaptureController {
        var closeCount = 0

        override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
            queryFailure?.let { throw it }
            return AudioCaptureCapabilities(source = request.source, available = true)
        }

        override suspend fun prepare(request: AudioCaptureRequest, sink: PcmAudioSink): AudioCaptureResult =
            AudioCaptureResult(AudioCaptureError.None, snapshot())

        override suspend fun start(): AudioCaptureResult = AudioCaptureResult(AudioCaptureError.None, snapshot())

        override suspend fun stop(): AudioCaptureResult = AudioCaptureResult(AudioCaptureError.None, snapshot())

        override fun snapshot(): AudioCaptureSnapshot = AudioCaptureSnapshot(source = AudioCaptureSource.SystemAudio)

        override fun close() {
            closeCount++
        }
    }
}
