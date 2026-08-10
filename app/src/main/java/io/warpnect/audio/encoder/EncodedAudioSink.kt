package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioTimestampQuality
import java.nio.ByteBuffer

interface EncodedAudioSink {
    fun onOutputFormatChanged(format: EncodedAudioFormat)

    fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    )

    fun onAudioDiscontinuity(expectedFramePosition: Long, actualFramePosition: Long) = Unit

    fun onEncoderError(error: AudioEncoderError) = Unit
}
