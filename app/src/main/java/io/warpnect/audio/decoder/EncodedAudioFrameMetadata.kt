package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioTimestampQuality

data class EncodedAudioFrameMetadata(
    val configGeneration: Long,
    val firstFramePosition: Long,
    val captureTimeUs: Long,
    val timestampQuality: AudioTimestampQuality,
    val discontinuityBefore: Boolean = false,
)
