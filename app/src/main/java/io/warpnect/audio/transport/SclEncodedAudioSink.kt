package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import java.nio.ByteBuffer

class SclEncodedAudioSink(
    private val backend: EncodedAudioTransportBackend,
) : EncodedAudioSink {
    var lastError: AudioTransportError = AudioTransportError.None
        private set

    var formatSubmitted: Boolean = false
        private set

    private var pendingDiscontinuity: Boolean = false

    override fun onOutputFormatChanged(format: EncodedAudioFormat) {
        validateFormat(format).throwIfFailed()
        backend.submitStreamConfig(format).throwIfFailed()
        formatSubmitted = true
    }

    override fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    ) {
        if (!formatSubmitted) {
            AudioTransportError.AudioConfigRequired.throwIfFailed()
        }
        validateFrame(buffer, offset, sizeBytes, firstFramePosition, captureTimeNs).throwIfFailed()
        val discontinuity = pendingDiscontinuity
        backend.submitAudioFrame(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
            timestampQuality = timestampQuality,
            discontinuityBefore = discontinuity,
        ).throwIfFailed()
        if (discontinuity) {
            pendingDiscontinuity = false
        }
    }

    override fun onAudioDiscontinuity(expectedFramePosition: Long, actualFramePosition: Long) {
        pendingDiscontinuity = true
    }

    override fun onEncoderError(error: AudioEncoderError) = Unit

    private fun validateFormat(format: EncodedAudioFormat): AudioTransportError {
        if (format.codec != AudioCodec.Opus) {
            return AudioTransportError.UnsupportedAudioCodec
        }
        if (format.sampleRateHz !in SUPPORTED_SAMPLE_RATES) {
            return AudioTransportError.InvalidSampleRate
        }
        if (format.channelCount !in 1..2) {
            return AudioTransportError.InvalidChannelCount
        }
        if (format.frameDurationUs !in SUPPORTED_FRAME_DURATIONS_US) {
            return AudioTransportError.InvalidFrameDuration
        }
        if (format.lookaheadSamples < 0) {
            return AudioTransportError.InvalidLookahead
        }
        return AudioTransportError.None
    }

    private fun validateFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
    ): AudioTransportError {
        if (!buffer.isDirect) {
            return AudioTransportError.NonDirectBuffer
        }
        if (firstFramePosition < 0) {
            return AudioTransportError.InvalidFramePosition
        }
        if (captureTimeNs < 0) {
            return AudioTransportError.InvalidCaptureTimestamp
        }
        if (offset < 0 || sizeBytes <= 0) {
            return AudioTransportError.InvalidBufferRange
        }
        val end = offset.toLong() + sizeBytes.toLong()
        if (end > buffer.capacity().toLong()) {
            return AudioTransportError.InvalidBufferRange
        }
        return AudioTransportError.None
    }

    private fun AudioTransportError.throwIfFailed() {
        lastError = this
        if (this != AudioTransportError.None) {
            throw AudioTransportException(this)
        }
    }

    private companion object {
        val SUPPORTED_SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
        val SUPPORTED_FRAME_DURATIONS_US = setOf(2_500, 5_000, 10_000, 20_000)
    }
}

class AudioTransportException(
    val error: AudioTransportError,
) : RuntimeException("Audio transport failed: $error")
