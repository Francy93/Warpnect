package io.warpnect.audio.capture

data class AudioCaptureFormat(
    val source: AudioCaptureSource,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: AudioPcmEncoding,
    val bytesPerFrame: Int,
    val targetFramesPerChunk: Int,
    val targetChunkDurationUs: Long,
)
