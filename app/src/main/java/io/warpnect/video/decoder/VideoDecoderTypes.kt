package io.warpnect.video.decoder

enum class VideoDecoderCodec(
    val mimeType: String,
) {
    Avc("video/avc"),
}

enum class VideoDecoderHardwareAcceleration {
    Hardware,
    Software,
    Unknown,
}

enum class VideoDecoderState {
    Stopped,
    Preparing,
    Prepared,
    Starting,
    Running,
    Draining,
    Stopping,
    Error,
}
