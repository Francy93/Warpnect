package io.warpnect.video.decoder

data class VideoDecoderPrepareResult(
    val error: VideoDecoderError,
    val capabilities: VideoDecoderCapabilities?,
    val snapshot: VideoDecoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoDecoderError.None
}

data class VideoDecoderStartResult(
    val error: VideoDecoderError,
    val snapshot: VideoDecoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoDecoderError.None
}

data class VideoDecoderControlResult(
    val error: VideoDecoderError,
    val snapshot: VideoDecoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoDecoderError.None
}

data class VideoDecoderStopResult(
    val error: VideoDecoderError,
    val snapshot: VideoDecoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoDecoderError.None
}
