package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemAudioPlayoutReserveTest {
    @Test fun boundedStereoReserveDoesNotChangeCodecTransportOrDeviceBuffer() {
        val original = config(AudioCaptureSource.SystemAudio)
        val result = SystemAudioPlayoutReserve.apply(original, 5_000)
        assertEquals(17, result.playbackRingCapacityCodecFrames)
        assertEquals(14, result.playbackStartThresholdCodecFrames)
        assertTrue(result.playbackWarmStart)
        assertSame(original.receiverRuntimeConfig, result.receiverRuntimeConfig)
        assertEquals(original.playbackRequestedBufferBursts, result.playbackRequestedBufferBursts)
        assertEquals(original.maxImmediatePlcFrames, result.maxImmediatePlcFrames)
    }

    @Test fun microphoneReserveIsUntouched() {
        val original = config(AudioCaptureSource.MicrophoneAudio)
        assertSame(original, SystemAudioPlayoutReserve.apply(original, 5_000))
    }

    @Test fun durationsAreRoundedUpWithoutReducingExplicitLargerReserves() {
        val rounded = SystemAudioPlayoutReserve.apply(config(AudioCaptureSource.SystemAudio), 20_000)
        assertEquals(4, rounded.playbackStartThresholdCodecFrames)
        val original = config(AudioCaptureSource.SystemAudio).copy(
            playbackRingCapacityCodecFrames = 32,
            playbackStartThresholdCodecFrames = 20,
        )
        assertEquals(original.copy(playbackWarmStart = true), SystemAudioPlayoutReserve.apply(original, 5_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDurationIsRejected() {
        SystemAudioPlayoutReserve.apply(config(AudioCaptureSource.SystemAudio), 0)
    }

    private fun config(source: AudioCaptureSource) = AudioReceiverSessionConfig(
        receiverRuntimeConfig = AudioReceiverRuntimeConfig(source, localPort = 40_000, maxWireDatagramSize = 1_200),
    )
}
