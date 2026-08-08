package io.warpnect.video.decoder

data class VideoDecoderFormatPlan(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val codecSpecificData: List<ByteArray>,
    val maxInputSizeBytes: Int?,
    val lowLatencyRequested: Boolean,
)

data class VideoDecoderFormatSupport(
    val supportsLowLatencyKey: Boolean,
)

object VideoDecoderFormatPlanner {
    fun build(
        config: VideoDecoderConfig,
        lowLatencyFeatureSupported: Boolean,
        support: VideoDecoderFormatSupport,
    ): VideoDecoderFormatPlan {
        val validation = VideoDecoderConfigValidator.validate(config)
        require(validation == VideoDecoderError.None) { "Invalid decoder config: $validation" }

        return VideoDecoderFormatPlan(
            mimeType = config.codec.mimeType,
            width = config.width,
            height = config.height,
            codecSpecificData = config.copyCsd(),
            maxInputSizeBytes = config.maxInputSizeBytes,
            lowLatencyRequested = support.supportsLowLatencyKey && lowLatencyFeatureSupported,
        )
    }
}
