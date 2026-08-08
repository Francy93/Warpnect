package io.warpnect.video.encoder

enum class VideoCodec(
    val mimeType: String,
) {
    Avc("video/avc"),
}

enum class VideoBitrateMode {
    Cbr,
}

enum class VideoEncoderHardwareAcceleration {
    Hardware,
    Software,
    Unknown,
}

enum class VideoEncoderState {
    Stopped,
    Preparing,
    Prepared,
    Starting,
    Running,
    Draining,
    Stopping,
    Error,
}
