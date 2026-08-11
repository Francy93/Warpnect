package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioTimestampQuality
import java.nio.ByteBuffer

interface DecodedPcmAudioSink {
    fun onOutputFormatChanged(format: DecodedAudioFormat)

    fun onPcmFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeUs: Long,
        timestampQuality: AudioTimestampQuality,
        discontinuityBefore: Boolean,
        frameKind: DecodedAudioFrameKind,
    )

    fun onDecoderError(error: AudioDecoderError) = Unit
}
