package io.warpnect.audio.transport

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.EncodedAudioFormat
import java.nio.ByteBuffer

interface EncodedAudioTransportBackend {
    fun submitStreamConfig(format: EncodedAudioFormat): AudioTransportError

    fun submitAudioFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
    ): AudioTransportError
}
