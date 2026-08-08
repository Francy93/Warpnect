package io.warpnect.video.transport

import android.media.MediaCodec
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SclEncodedVideoSinkTest {
    @Test
    fun outputFormatSubmitsStreamConfig() {
        val backend = FakeBackend()
        val sink = SclEncodedVideoSink(backend)
        val format = outputFormat()

        sink.onOutputFormatChanged(format)

        assertTrue(sink.formatSubmitted)
        assertSame(format, backend.submittedFormat)
        assertEquals(VideoTransportError.None, sink.lastError)
    }

    @Test
    fun accessUnitBeforeConfigFails() {
        val sink = SclEncodedVideoSink(FakeBackend())
        val buffer = ByteBuffer.allocateDirect(16)

        val failure = expectTransportFailure {
            sink.onAccessUnit(buffer, offset = 0, size = 4, presentationTimeUs = 1, flags = 0)
        }

        assertEquals(VideoTransportError.VideoConfigRequired, failure.error)
        assertEquals(VideoTransportError.VideoConfigRequired, sink.lastError)
    }

    @Test
    fun directBufferAccessUnitForwardsMetadataAndPreservesPositionLimit() {
        val backend = FakeBackend()
        val sink = SclEncodedVideoSink(backend)
        sink.onOutputFormatChanged(outputFormat())
        val buffer = ByteBuffer.allocateDirect(32)
        buffer.position(3)
        buffer.limit(19)

        sink.onAccessUnit(
            buffer = buffer,
            offset = 5,
            size = 11,
            presentationTimeUs = 1234,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
        )

        assertSame(buffer, backend.lastBuffer)
        assertEquals(5, backend.lastOffset)
        assertEquals(11, backend.lastSize)
        assertEquals(1234L, backend.lastPresentationTimeUs)
        assertTrue(backend.lastKeyframe)
        assertEquals(3, buffer.position())
        assertEquals(19, buffer.limit())
    }

    @Test
    fun nonDirectBufferIsRejectedWithoutFallbackCopy() {
        val sink = SclEncodedVideoSink(FakeBackend())
        sink.onOutputFormatChanged(outputFormat())

        val failure = expectTransportFailure {
            sink.onAccessUnit(
                buffer = ByteBuffer.allocate(16),
                offset = 0,
                size = 8,
                presentationTimeUs = 1,
                flags = 0,
            )
        }

        assertEquals(VideoTransportError.NonDirectBuffer, failure.error)
    }

    @Test
    fun invalidBufferRangesAreRejected() {
        val sink = SclEncodedVideoSink(FakeBackend())
        sink.onOutputFormatChanged(outputFormat())
        val buffer = ByteBuffer.allocateDirect(10)

        assertEquals(
            VideoTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onAccessUnit(buffer, offset = -1, size = 1, presentationTimeUs = 1, flags = 0)
            }.error,
        )
        assertEquals(
            VideoTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onAccessUnit(buffer, offset = 0, size = 0, presentationTimeUs = 1, flags = 0)
            }.error,
        )
        assertEquals(
            VideoTransportError.InvalidBufferRange,
            expectTransportFailure {
                sink.onAccessUnit(buffer, offset = 8, size = 3, presentationTimeUs = 1, flags = 0)
            }.error,
        )
    }

    @Test
    fun negativePresentationTimestampIsRejected() {
        val sink = SclEncodedVideoSink(FakeBackend())
        sink.onOutputFormatChanged(outputFormat())

        val failure = expectTransportFailure {
            sink.onAccessUnit(
                buffer = ByteBuffer.allocateDirect(16),
                offset = 0,
                size = 8,
                presentationTimeUs = -1,
                flags = 0,
            )
        }

        assertEquals(VideoTransportError.InvalidPresentationTimestamp, failure.error)
    }

    @Test
    fun transportErrorsPropagateSynchronously() {
        val backend = FakeBackend(accessUnitError = VideoTransportError.WouldBlock)
        val sink = SclEncodedVideoSink(backend)
        sink.onOutputFormatChanged(outputFormat())

        val failure = expectTransportFailure {
            sink.onAccessUnit(
                buffer = ByteBuffer.allocateDirect(16),
                offset = 0,
                size = 8,
                presentationTimeUs = 1,
                flags = 0,
            )
        }

        assertEquals(VideoTransportError.WouldBlock, failure.error)
        assertFalse(backend.lastKeyframe)
    }

    @Test
    fun invalidOutputFormatIsRejectedBeforeBackend() {
        val backend = FakeBackend()
        val sink = SclEncodedVideoSink(backend)

        val failure = expectTransportFailure {
            sink.onOutputFormatChanged(outputFormat(width = 0))
        }

        assertEquals(VideoTransportError.InvalidDimensions, failure.error)
        assertEquals(null, backend.submittedFormat)
    }

    private fun outputFormat(width: Int = 1280, height: Int = 720): VideoEncoderOutputFormat = VideoEncoderOutputFormat(
        codec = VideoCodec.Avc,
        mimeType = VideoCodec.Avc.mimeType,
        width = width,
        height = height,
        frameRate = 60,
        bitrateBps = 8_000_000,
        profile = null,
        level = null,
        outputReorderDepth = 0,
        reportedLatencyFrames = 1,
        codecSpecificData = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4)),
    )

    private fun expectTransportFailure(block: () -> Unit): VideoTransportException {
        return try {
            block()
            throw AssertionError("Expected VideoTransportException")
        } catch (error: VideoTransportException) {
            error
        }
    }

    private class FakeBackend(
        private val configError: VideoTransportError = VideoTransportError.None,
        private val accessUnitError: VideoTransportError = VideoTransportError.None,
    ) : EncodedVideoTransportBackend {
        var submittedFormat: VideoEncoderOutputFormat? = null
        var lastBuffer: ByteBuffer? = null
        var lastOffset: Int = -1
        var lastSize: Int = -1
        var lastPresentationTimeUs: Long = -1
        var lastKeyframe: Boolean = false

        override fun submitStreamConfig(format: VideoEncoderOutputFormat): VideoTransportError {
            submittedFormat = format
            return configError
        }

        override fun submitAccessUnit(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            keyframe: Boolean,
        ): VideoTransportError {
            lastBuffer = buffer
            lastOffset = offset
            lastSize = size
            lastPresentationTimeUs = presentationTimeUs
            lastKeyframe = keyframe
            return accessUnitError
        }
    }
}
