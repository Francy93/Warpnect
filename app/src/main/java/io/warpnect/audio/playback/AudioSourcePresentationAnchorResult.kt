package io.warpnect.audio.playback

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind

data class AudioSourcePresentationAnchorResult(
    val error: AudioPlaybackError,
    val valid: Boolean = false,
    val sourceContentTimeUs: Long = 0,
    val sourceCaptureTimeUs: Long = 0,
    val sourceFramePosition: Long = 0,
    val outputFramePosition: Long = 0,
    val localPresentationTimeNs: Long = 0,
    val oboeFramePosition: Long = 0,
    val oboePresentationTimeNs: Long = 0,
    val ageNs: Long = 0,
    val configGeneration: Long = 0,
    val sampleRateHz: Int = 0,
    val lookaheadSamples: Int = 0,
    val timestampQuality: AudioTimestampQuality = AudioTimestampQuality.Unavailable,
    val discontinuityBefore: Boolean = false,
    val frameKind: DecodedAudioFrameKind = DecodedAudioFrameKind.Normal,
    val latencyUs: Long = 0,
)
