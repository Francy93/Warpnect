package io.warpnect.video.encoder

import android.view.Surface

data class VideoEncoderPrepareResult(
    val error: VideoEncoderError,
    val inputSurface: Surface?,
    val capabilities: VideoEncoderCapabilities?,
    val snapshot: VideoEncoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoEncoderError.None && inputSurface != null
}

data class VideoEncoderStartResult(
    val error: VideoEncoderError,
    val snapshot: VideoEncoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoEncoderError.None
}

data class VideoEncoderControlResult(
    val error: VideoEncoderError,
    val snapshot: VideoEncoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoEncoderError.None
}

data class VideoEncoderStopResult(
    val error: VideoEncoderError,
    val snapshot: VideoEncoderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoEncoderError.None
}
