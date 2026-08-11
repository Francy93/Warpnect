package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.platform.audio.transport.NativeSclAudioTransportController
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTransportJniInstrumentationTest {
    @Test
    fun directEncodedBufferIsAcceptedAndNonDirectIsRejected() {
        val controller = NativeSclAudioTransportController()
        val open = controller.open(
            AudioTransportConfig(
                source = AudioCaptureSource.MicrophoneAudio,
                remoteAddress = "127.0.0.1",
                remotePort = 46_000,
                maxWireDatagramSize = 1200,
            ),
        )
        assertEquals(AudioTransportError.None, open.error)
        assertEquals(AudioTransportError.None, controller.submitStreamConfig(format()))

        val direct = ByteBuffer.allocateDirect(32)
        direct.put(0, 1)
        assertEquals(
            AudioTransportError.None,
            controller.submitAudioFrame(
                buffer = direct,
                offset = 0,
                sizeBytes = 1,
                firstFramePosition = 0,
                captureTimeNs = 1_234_567_899,
                timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
                discontinuityBefore = false,
            ),
        )
        val nonDirect = ByteBuffer.allocate(32)
        assertEquals(
            AudioTransportError.NonDirectBuffer,
            controller.submitAudioFrame(
                buffer = nonDirect,
                offset = 0,
                sizeBytes = 1,
                firstFramePosition = 240,
                captureTimeNs = 1_234_568_899,
                timestampQuality = AudioTimestampQuality.Unavailable,
                discontinuityBefore = false,
            ),
        )
        controller.close()
    }

    private fun format(): EncodedAudioFormat = EncodedAudioFormat(
        codec = AudioCodec.Opus,
        source = AudioCaptureSource.MicrophoneAudio,
        sampleRateHz = 48_000,
        channelCount = 1,
        frameDurationUs = 5_000,
        samplesPerFrame = 240,
        bitrateBps = 64_000,
        bitrateMode = AudioBitrateMode.ConstantBitrate,
        complexity = 5,
        dtxEnabled = false,
        inBandFecEnabled = false,
        lookaheadSamples = 120,
    )
}
