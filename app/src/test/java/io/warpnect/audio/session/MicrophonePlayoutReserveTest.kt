package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MicrophonePlayoutReserveTest {
    @Test fun fiveMillisecondMonoFramesHaveBoundedReserveWithoutChangingTransport() {
        val original = config(AudioCaptureSource.MicrophoneAudio)
        val result = MicrophonePlayoutReserve.apply(original, 5_000)
        assertEquals(16, result.playbackRingCapacityCodecFrames)
        assertEquals(14, result.playbackStartThresholdCodecFrames)
        assertSame(original.receiverRuntimeConfig, result.receiverRuntimeConfig)
        assertEquals(original.playbackRequestedBufferBursts, result.playbackRequestedBufferBursts)
        assertEquals(original.maxImmediatePlcFrames, result.maxImmediatePlcFrames)
    }

    @Test fun reserveIsDurationBasedRatherThanAssumingFiveMillisecondPackets() {
        val result = MicrophonePlayoutReserve.apply(config(AudioCaptureSource.MicrophoneAudio), 20_000)
        assertEquals(4, result.playbackRingCapacityCodecFrames)
        assertEquals(4, result.playbackStartThresholdCodecFrames)
    }

    @Test fun explicitLargerCapacityAndPrimeAreNotSilentlyReduced() {
        val original = config(AudioCaptureSource.MicrophoneAudio).copy(
            playbackRingCapacityCodecFrames = 32,
            playbackStartThresholdCodecFrames = 20,
        )
        assertEquals(original.copy(playbackWarmStart = true), MicrophonePlayoutReserve.apply(original, 5_000))
    }

    @Test fun systemAudioConfigurationIsUnchanged() {
        val original = config(AudioCaptureSource.SystemAudio)
        assertSame(original, MicrophonePlayoutReserve.apply(original, 5_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPacketDurationCannotCreateUnboundedReserve() {
        MicrophonePlayoutReserve.apply(config(AudioCaptureSource.MicrophoneAudio), 0)
    }

    private fun config(source: AudioCaptureSource) = AudioReceiverSessionConfig(
        receiverRuntimeConfig = AudioReceiverRuntimeConfig(
            source = source,
            localPort = 40_000,
            maxWireDatagramSize = 1_200,
        ),
    )
}
