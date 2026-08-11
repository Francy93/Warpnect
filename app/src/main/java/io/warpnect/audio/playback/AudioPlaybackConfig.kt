package io.warpnect.audio.playback

import io.warpnect.audio.capture.AudioCaptureSource

data class AudioPlaybackConfig(
    val source: AudioCaptureSource,
    val configGeneration: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val frameDurationUs: Int,
    val framesPerCodecFrame: Int,
    val ringCapacityCodecFrames: Int = 4,
    val startThresholdCodecFrames: Int = 1,
    val sharingPolicy: AudioPlaybackSharingPolicy = AudioPlaybackSharingPolicy.PreferExclusiveAllowShared,
    val requestedBufferBursts: Int = 2,
    val requireLowLatencyPerformanceMode: Boolean = true,
)
