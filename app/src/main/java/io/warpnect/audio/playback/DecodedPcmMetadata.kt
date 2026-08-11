package io.warpnect.audio.playback

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind

data class DecodedPcmMetadata(
    val configGeneration: Long,
    val firstFramePosition: Long,
    val captureTimeUs: Long,
    val timestampQuality: AudioTimestampQuality,
    val discontinuityBefore: Boolean,
    val frameKind: DecodedAudioFrameKind,
)
