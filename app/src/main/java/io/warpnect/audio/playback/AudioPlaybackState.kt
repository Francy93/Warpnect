package io.warpnect.audio.playback

enum class AudioPlaybackState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}
