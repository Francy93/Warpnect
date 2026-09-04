package io.warpnect.video.decoder

data class VideoDecoderCodecInfo(
    val codecName: String,
    val canonicalName: String?,
    val hardwareAcceleration: VideoDecoderHardwareAcceleration,
    val softwareOnly: Boolean?,
    val vendor: Boolean?,
    val alias: Boolean?,
    val lowLatencyFeatureSupported: Boolean?,
)

data class VideoDecoderSupport(
    val widthSupported: Boolean,
    val heightSupported: Boolean,
    val sizeSupported: Boolean,
    val sizeAndRateSupported: Boolean,
    val lowLatencyFeatureSupported: Boolean?,
    val widthAlignment: Int?,
    val heightAlignment: Int?,
    val minWidth: Int?,
    val maxWidth: Int?,
    val minHeight: Int?,
    val maxHeight: Int?,
)

data class VideoDecoderCapabilities(
    val config: VideoDecoderConfig,
    val selectedCodec: VideoDecoderCodecInfo?,
    val support: VideoDecoderSupport?,
    val candidates: List<VideoDecoderCodecInfo> = emptyList(),
    val error: VideoDecoderError = VideoDecoderError.None,
    val qualification: VideoDecoderQualification = VideoDecoderQualification.NotApplicable,
) {
    val isSupported: Boolean
        get() = error == VideoDecoderError.None && selectedCodec != null

    val isQualifiedForClientVideo: Boolean
        get() = isSupported && (
            selectedCodec?.hardwareAcceleration == VideoDecoderHardwareAcceleration.Hardware ||
                qualification == VideoDecoderQualification.ActivePass ||
                qualification == VideoDecoderQualification.CachedPass
            )
}
