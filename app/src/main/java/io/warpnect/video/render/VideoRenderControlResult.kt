package io.warpnect.video.render

data class VideoRenderControlResult(
    val error: VideoRenderError,
    val snapshot: VideoRenderSnapshot,
) {
    val isSuccess: Boolean
        get() = error == VideoRenderError.None
}
