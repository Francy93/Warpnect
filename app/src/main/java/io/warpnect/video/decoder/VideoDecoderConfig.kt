package io.warpnect.video.decoder

data class VideoDecoderConfig(
    val codec: VideoDecoderCodec = VideoDecoderCodec.Avc,
    val width: Int,
    val height: Int,
    val configGeneration: Long,
    val codecSpecificData: List<ByteArray>,
    val maxInputSizeBytes: Int? = null,
) {
    fun copyCsd(): List<ByteArray> = codecSpecificData.map { it.copyOf() }

    companion object {
        const val MAX_CSD_ENTRIES = 4
    }
}

object VideoDecoderConfigValidator {
    fun validate(config: VideoDecoderConfig): VideoDecoderError = when {
        config.codec != VideoDecoderCodec.Avc -> VideoDecoderError.UnsupportedCodec
        config.width <= 0 -> VideoDecoderError.InvalidConfiguration
        config.height <= 0 -> VideoDecoderError.InvalidConfiguration
        config.configGeneration <= 0L -> VideoDecoderError.InvalidConfigGeneration
        config.codecSpecificData.isEmpty() -> VideoDecoderError.MissingCodecSpecificData
        config.codecSpecificData.size > VideoDecoderConfig.MAX_CSD_ENTRIES ->
            VideoDecoderError.InvalidConfiguration
        config.codecSpecificData.any { it.isEmpty() } -> VideoDecoderError.MissingCodecSpecificData
        config.maxInputSizeBytes != null && config.maxInputSizeBytes <= 0 ->
            VideoDecoderError.InvalidConfiguration
        else -> VideoDecoderError.None
    }
}
