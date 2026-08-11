package io.warpnect.audio.playback

data class AudioPlaybackResult(
    val error: AudioPlaybackError,
    val snapshot: AudioPlaybackSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioPlaybackError.None
}
