package io.warpnect.audio.capture

enum class AudioCaptureState {
    Stopped,
    Preparing,
    Prepared,
    Starting,
    Running,
    Stopping,
    Error,
    Closed,
}
