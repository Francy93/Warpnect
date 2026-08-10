package io.warpnect.audio.encoder

enum class AudioEncoderState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}
