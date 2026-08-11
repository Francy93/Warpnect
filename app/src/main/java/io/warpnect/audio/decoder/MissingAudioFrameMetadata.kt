package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioTimestampQuality

data class MissingAudioFrameMetadata(
    val configGeneration: Long,
    val firstFramePosition: Long,
    val captureTimeUs: Long,
    val timestampQuality: AudioTimestampQuality,
)
