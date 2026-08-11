package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.EncodedAudioFormat
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SclEncodedAudioSinkTest {
    @Test
    fun outputFormatSubmitsStreamConfig() {
        val backend = FakeBackend()
        val sink = SclEncodedAudioSink(backend)
        val format = encodedFormat()

        sink.onOutputFormatChanged(format)

        assertTrue(sink.formatSubmitted)
        assertSame(format, backend.submittedFormat)
        assertEquals(AudioTransportError.None, sink.lastError)
    }

    @Test
    fun frameBeforeConfigFails() {
        val sink = SclEncodedAudioSink(FakeBackend())
        val buffer = ByteBuffer.allocateDirect(16)

        val failure = expectTransportFailure {
            sink.onEncodedFrame(
                buffer = buffer,
                offset = 0,
                sizeBytes = 4,
                firstFramePosition = 0,
                captureTimeNs = 1,
                timestampQuality = AudioTimestampQuality.Unavailable,
                encodedFrameIndex = 0,
            )
        }

        assertEquals(AudioTransportError.AudioConfigRequired, failure.error)
    }

    @Test
    fun directBufferFrameForwardsMetadataAndPreservesPositionLimit() {
        val backend = FakeBackend()
        val sink = SclEncodedAudioSink(backend)
        sink.onOutputFormatChanged(encodedFormat())
        val buffer = ByteBuffer.allocateDirect(32)
        buffer.position(2)
        buffer.limit(21)

        sink.onEncodedFrame(
            buffer = buffer,
            offset = 5,
            sizeBytes = 11,
            firstFramePosition = 240,
            captureTimeNs = 1_234_567_899,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            encodedFrameIndex = 7,
        )

        assertSame(buffer, backend.lastBuffer)
        assertEquals(5, backend.lastOffset)
        assertEquals(11, backend.lastSize)
        assertEquals(240L, backend.lastFirstFramePosition)
        assertEquals(1_234_567_899L, backend.lastCaptureTimeNs)
        assertEquals(AudioTimestampQuality.AudioRecordTimestamp, backend.lastTimestampQuality)
        assertFalse(backend.lastDiscontinuityBefore)
        assertEquals(2, buffer.position())
        assertEquals(21, buffer.limit())
    }

    @Test
    fun nonDirectBufferIsRejectedWithoutFallbackCopy() {
        val sink = SclEncodedAudioSink(FakeBackend())
        sink.onOutputFormatChanged(encodedFormat())

        val failure = expectTransportFailure {
            sink.onEncodedFrame(
                buffer = ByteBuffer.allocate(16),
                offset = 0,
                sizeBytes = 8,
                firstFramePosition = 0,
                captureTimeNs = 1,
                timestampQuality = AudioTimestampQuality.Unavailable,
                encodedFrameIndex = 0,
            )
        }

        assertEquals(AudioTransportError.NonDirectBuffer, failure.error)
    }

    @Test
    fun invalidFrameRangeAndTimestampsAreRejected() {
        val sink = SclEncodedAudioSink(FakeBackend())
        sink.onOutputFormatChanged(encodedFormat())
        val buffer = ByteBuffer.allocateDirect(10)

        assertEquals(
            AudioTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, -1, 1, 0, 1, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
        assertEquals(
            AudioTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, 0, 0, 0, 1, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
        assertEquals(
            AudioTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, 8, 3, 0, 1, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
        assertEquals(
            AudioTransportError.InvalidFramePosition,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, 0, 1, -1, 1, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
        assertEquals(
            AudioTransportError.InvalidCaptureTimestamp,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, 0, 1, 0, -1, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
    }

    @Test
    fun invalidFormatIsRejectedBeforeBackend() {
        val backend = FakeBackend()
        val sink = SclEncodedAudioSink(backend)

        val failure = expectTransportFailure {
            sink.onOutputFormatChanged(encodedFormat(sampleRateHz = 44_100))
        }

        assertEquals(AudioTransportError.InvalidSampleRate, failure.error)
        assertEquals(null, backend.submittedFormat)
    }

    @Test
    fun discontinuityFlagAppliesToNextSuccessfulFrameOnly() {
        val backend = FakeBackend()
        val sink = SclEncodedAudioSink(backend)
        sink.onOutputFormatChanged(encodedFormat())
        val buffer = ByteBuffer.allocateDirect(16)

        sink.onEncodedFrame(buffer, 0, 4, 0, 1_000, AudioTimestampQuality.Unavailable, 0)
        sink.onAudioDiscontinuity(expectedFramePosition = 240, actualFramePosition = 480)
        sink.onEncodedFrame(buffer, 0, 4, 480, 2_000, AudioTimestampQuality.Unavailable, 1)
        sink.onEncodedFrame(buffer, 0, 4, 720, 3_000, AudioTimestampQuality.Unavailable, 2)

        assertEquals(listOf(false, true, false), backend.discontinuityFlags)
    }

    @Test
    fun pendingDiscontinuitySurvivesFailedSubmission() {
        val backend = FakeBackend(frameErrors = ArrayDeque(listOf(AudioTransportError.WouldBlock)))
        val sink = SclEncodedAudioSink(backend)
        sink.onOutputFormatChanged(encodedFormat())
        val buffer = ByteBuffer.allocateDirect(16)
        sink.onAudioDiscontinuity(expectedFramePosition = 0, actualFramePosition = 240)

        assertEquals(
            AudioTransportError.WouldBlock,
            expectTransportFailure {
                sink.onEncodedFrame(buffer, 0, 4, 240, 1_000, AudioTimestampQuality.Unavailable, 0)
            }.error,
        )
        sink.onEncodedFrame(buffer, 0, 4, 480, 2_000, AudioTimestampQuality.Unavailable, 1)

        assertEquals(listOf(true, true), backend.discontinuityFlags)
    }

    private fun encodedFormat(
        source: AudioCaptureSource = AudioCaptureSource.MicrophoneAudio,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 1,
        frameDurationUs: Int = 5_000,
    ): EncodedAudioFormat = EncodedAudioFormat(
        codec = AudioCodec.Opus,
        source = source,
        sampleRateHz = sampleRateHz,
        channelCount = channelCount,
        frameDurationUs = frameDurationUs,
        samplesPerFrame = 240,
        bitrateBps = 64_000,
        bitrateMode = AudioBitrateMode.ConstantBitrate,
        complexity = 5,
        dtxEnabled = false,
        inBandFecEnabled = false,
        lookaheadSamples = 120,
    )

    private fun expectTransportFailure(block: () -> Unit): AudioTransportException {
        return try {
            block()
            throw AssertionError("Expected AudioTransportException")
        } catch (error: AudioTransportException) {
            error
        }
    }

    private class FakeBackend(
        private val configError: AudioTransportError = AudioTransportError.None,
        private val frameErrors: ArrayDeque<AudioTransportError> = ArrayDeque(),
    ) : EncodedAudioTransportBackend {
        var submittedFormat: EncodedAudioFormat? = null
        var lastBuffer: ByteBuffer? = null
        var lastOffset: Int = -1
        var lastSize: Int = -1
        var lastFirstFramePosition: Long = -1
        var lastCaptureTimeNs: Long = -1
        var lastTimestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable
        var lastDiscontinuityBefore: Boolean = false
        val discontinuityFlags = mutableListOf<Boolean>()

        override fun submitStreamConfig(format: EncodedAudioFormat): AudioTransportError {
            submittedFormat = format
            return configError
        }

        override fun submitAudioFrame(
            buffer: ByteBuffer,
            offset: Int,
            sizeBytes: Int,
            firstFramePosition: Long,
            captureTimeNs: Long,
            timestampQuality: AudioTimestampQuality,
            discontinuityBefore: Boolean,
        ): AudioTransportError {
            lastBuffer = buffer
            lastOffset = offset
            lastSize = sizeBytes
            lastFirstFramePosition = firstFramePosition
            lastCaptureTimeNs = captureTimeNs
            lastTimestampQuality = timestampQuality
            lastDiscontinuityBefore = discontinuityBefore
            discontinuityFlags += discontinuityBefore
            return frameErrors.removeFirstOrNull() ?: AudioTransportError.None
        }
    }
}
