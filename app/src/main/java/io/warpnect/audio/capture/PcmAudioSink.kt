package io.warpnect.audio.capture

import java.nio.ByteBuffer

interface PcmAudioSink {
    fun onFormatChanged(format: AudioCaptureFormat)

    fun onPcmChunk(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    )

    fun onCaptureError(error: AudioCaptureError) = Unit
}
