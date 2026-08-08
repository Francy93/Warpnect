package io.warpnect.video.transport

import android.media.MediaCodec
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import java.nio.ByteBuffer

class SclEncodedVideoSink(
    private val backend: EncodedVideoTransportBackend,
) : EncodedVideoSink {
    var lastError: VideoTransportError = VideoTransportError.None
        private set

    var formatSubmitted: Boolean = false
        private set

    override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
        validateFormat(format).throwIfFailed()
        backend.submitStreamConfig(format).throwIfFailed()
        formatSubmitted = true
    }

    override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        if (!formatSubmitted) {
            VideoTransportError.VideoConfigRequired.throwIfFailed()
        }
        validateAccessUnit(buffer, offset, size, presentationTimeUs).throwIfFailed()
        val keyframe = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        backend.submitAccessUnit(
            buffer = buffer,
            offset = offset,
            size = size,
            presentationTimeUs = presentationTimeUs,
            keyframe = keyframe,
        ).throwIfFailed()
    }

    override fun onEncoderError(error: VideoEncoderError) = Unit

    private fun validateFormat(format: VideoEncoderOutputFormat): VideoTransportError {
        if (format.codec != VideoCodec.Avc) {
            return VideoTransportError.UnsupportedVideoCodec
        }
        if (format.width !in 1..U16_MAX || format.height !in 1..U16_MAX) {
            return VideoTransportError.InvalidDimensions
        }
        if (
            format.codecSpecificData.isEmpty() ||
            format.codecSpecificData.size > MAX_CSD_ENTRIES
        ) {
            return VideoTransportError.InvalidCsdCount
        }
        if (format.codecSpecificData.any { it.isEmpty() }) {
            return VideoTransportError.MalformedCsd
        }
        return VideoTransportError.None
    }

    private fun validateAccessUnit(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
    ): VideoTransportError {
        if (!buffer.isDirect) {
            return VideoTransportError.NonDirectBuffer
        }
        if (presentationTimeUs < 0) {
            return VideoTransportError.InvalidPresentationTimestamp
        }
        if (offset < 0 || size <= 0) {
            return VideoTransportError.InvalidBufferRange
        }
        val end = offset.toLong() + size.toLong()
        if (end > buffer.capacity().toLong()) {
            return VideoTransportError.InvalidBufferRange
        }
        return VideoTransportError.None
    }

    private fun VideoTransportError.throwIfFailed() {
        lastError = this
        if (this != VideoTransportError.None) {
            throw VideoTransportException(this)
        }
    }

    private companion object {
        const val U16_MAX = 65_535
        const val MAX_CSD_ENTRIES = 4
    }
}

class VideoTransportException(
    val error: VideoTransportError,
) : RuntimeException("Video transport failed: $error")
