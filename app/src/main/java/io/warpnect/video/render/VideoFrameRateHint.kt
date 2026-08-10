package io.warpnect.video.render

enum class VideoFrameRateCompatibility {
    FixedSource,
}
enum class VideoFrameRateChangeStrategy {
    OnlyIfSeamless,
}

data class VideoFrameRateHintPlan(
    val frameRateHz: Float,
    val clearsPreference: Boolean,
    val compatibility: VideoFrameRateCompatibility,
    val changeStrategy: VideoFrameRateChangeStrategy?,
)

object VideoFrameRateHintPlanner {
    fun validate(preferredFrameRateHz: Float?): VideoRenderError = when {
        preferredFrameRateHz == null -> VideoRenderError.None
        !preferredFrameRateHz.isFinite() -> VideoRenderError.InvalidPreferredFrameRate
        preferredFrameRateHz <= 0f -> VideoRenderError.InvalidPreferredFrameRate
        else -> VideoRenderError.None
    }

    fun plan(preferredFrameRateHz: Float?, supportsChangeStrategy: Boolean): VideoFrameRateHintPlan =
        VideoFrameRateHintPlan(
            frameRateHz = preferredFrameRateHz ?: CLEAR_FRAME_RATE,
            clearsPreference = preferredFrameRateHz == null,
            compatibility = VideoFrameRateCompatibility.FixedSource,
            changeStrategy = if (supportsChangeStrategy) {
                VideoFrameRateChangeStrategy.OnlyIfSeamless
            } else {
                null
            },
        )

    private const val CLEAR_FRAME_RATE = 0f
}
