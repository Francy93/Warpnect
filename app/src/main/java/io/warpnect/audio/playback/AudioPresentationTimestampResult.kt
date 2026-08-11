package io.warpnect.audio.playback

data class AudioPresentationTimestampResult(
    val error: AudioPlaybackError,
    val timestampValid: Boolean = false,
    val streamFramePosition: Long = 0,
    val presentationTimeNs: Long = 0,
    val latencyUs: Long = 0,
)
