package io.warpnect.audio.decoder

enum class AudioDecoderState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}
