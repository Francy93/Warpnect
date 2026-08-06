package io.warpnect.codec

class VideoDecoderPipeline {
    fun configure(configuration: VideoDecoderConfiguration): VideoPipelineResult =
        VideoPipelineResult.NotImplemented("Hardware video decoding is reserved for a later phase.")

    fun attachRenderSurface(surfaceToken: Any): VideoPipelineResult =
        VideoPipelineResult.NotImplemented("Zero-copy rendering integration is reserved for a later phase.")

    fun start(): VideoPipelineResult =
        VideoPipelineResult.NotImplemented("The Warpnect receiver video path is not implemented in Phase 0.")

    fun stop() {
        // Phase 0 owns no decoder resources.
    }
}

data class VideoDecoderConfiguration(
    val codecName: String,
    val width: Int,
    val height: Int,
)
