package io.warpnect.audio.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.platform.audio.playback.NativeOboeAudioPlaybackController
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OboeAudioPlaybackInstrumentationTest {
    @Test
    fun directPcmSubmissionLifecycleAndTimestampQuery() {
        val controller = NativeOboeAudioPlaybackController()
        val config = AudioPlaybackConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            configGeneration = 1,
            sampleRateHz = 48000,
            channelCount = 1,
            frameDurationUs = 5000,
            framesPerCodecFrame = 240,
            ringCapacityCodecFrames = 4,
            startThresholdCodecFrames = 1,
            sharingPolicy = AudioPlaybackSharingPolicy.PreferExclusiveAllowShared,
            requestedBufferBursts = 2,
            requireLowLatencyPerformanceMode = false,
        )
        val prepared = controller.prepare(config)
        assertEquals(AudioPlaybackError.None, prepared.error)

        val pcm = sineFrame()
        val metadata = DecodedPcmMetadata(
            configGeneration = 1,
            firstFramePosition = 0,
            captureTimeUs = 1000,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = false,
            frameKind = DecodedAudioFrameKind.Normal,
        )
        assertEquals(
            AudioPlaybackError.None,
            controller.submitPcm(pcm, 0, pcm.capacity(), 240, metadata).error,
        )
        assertEquals(AudioPlaybackError.None, controller.start().error)
        Thread.sleep(30)
        val snapshot = controller.snapshot()
        assertTrue(snapshot.framesPerBurst >= 0)
        assertTrue(snapshot.pcmFramesSubmitted >= 240L)
        controller.queryPresentationTimestamp()
        assertEquals(AudioPlaybackError.None, controller.stop().error)
        controller.close()
    }

    @Test
    fun invalidPcmInputIsRejected() {
        val controller = NativeOboeAudioPlaybackController()
        val config = AudioPlaybackConfig(
            source = AudioCaptureSource.MicrophoneAudio,
            configGeneration = 1,
            sampleRateHz = 48000,
            channelCount = 1,
            frameDurationUs = 5000,
            framesPerCodecFrame = 240,
            requireLowLatencyPerformanceMode = false,
        )
        assertEquals(AudioPlaybackError.None, controller.prepare(config).error)
        val heap = ByteBuffer.allocate(480)
        val metadata = DecodedPcmMetadata(
            configGeneration = 1,
            firstFramePosition = 0,
            captureTimeUs = 1000,
            timestampQuality = AudioTimestampQuality.Unavailable,
            discontinuityBefore = false,
            frameKind = DecodedAudioFrameKind.Normal,
        )
        assertEquals(
            AudioPlaybackError.NonDirectBuffer,
            controller.submitPcm(heap, 0, heap.capacity(), 240, metadata).error,
        )
        controller.close()
    }

    private fun sineFrame(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(240 * 2).order(ByteOrder.nativeOrder())
        for (frame in 0 until 240) {
            val value = (sin((frame.toDouble() * 440.0 * 2.0 * PI) / 48000.0) * 8000.0).toInt()
            buffer.putShort(value.toShort())
        }
        buffer.clear()
        return buffer
    }
}
