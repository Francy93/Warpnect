package io.warpnect.video.transport

data class VideoTransportOpenResult(
    val error: VideoTransportError,
    val snapshot: VideoTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoTransportError.None
}

data class VideoTransportSubmitResult(
    val error: VideoTransportError,
    val snapshot: VideoTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoTransportError.None
}

data class VideoTransportCloseResult(
    val error: VideoTransportError,
    val snapshot: VideoTransportSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoTransportError.None
}
