package io.warpnect.codec

class VideoEncoderPipeline {
    fun configure(configuration: VideoEncoderConfiguration): VideoPipelineResult =
        VideoPipelineResult.NotImplemented("Hardware video encoding is reserved for a later phase.")

    fun start(): VideoPipelineResult =
        VideoPipelineResult.NotImplemented("The Warpnect transmitter video path is not implemented in Phase 0.")

    fun stop() {
        // Phase 0 owns no encoder resources.
    }
}

data class VideoEncoderConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateBps: Int,
)

sealed interface VideoPipelineResult {
    data object Ready : VideoPipelineResult

    data class NotImplemented(
        val reason: String,
    ) : VideoPipelineResult
}
