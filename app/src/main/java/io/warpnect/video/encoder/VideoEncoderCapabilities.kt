package io.warpnect.video.encoder

data class VideoEncoderCodecInfo(
    val codecName: String,
    val canonicalName: String?,
    val hardwareAcceleration: VideoEncoderHardwareAcceleration,
    val softwareOnly: Boolean?,
    val vendor: Boolean?,
    val alias: Boolean?,
)

data class VideoEncoderSupport(
    val widthSupported: Boolean,
    val heightSupported: Boolean,
    val sizeSupported: Boolean,
    val sizeAndRateSupported: Boolean,
    val bitrateSupported: Boolean,
    val bitrateModeSupported: Boolean,
    val surfaceInputSupported: Boolean,
    val widthAlignment: Int?,
    val heightAlignment: Int?,
    val minWidth: Int?,
    val maxWidth: Int?,
    val minHeight: Int?,
    val maxHeight: Int?,
    val minBitrateBps: Int?,
    val maxBitrateBps: Int?,
)

data class VideoEncoderCapabilities(
    val request: VideoEncoderRequest,
    val selectedCodec: VideoEncoderCodecInfo?,
    val support: VideoEncoderSupport?,
    val supportedProfiles: List<VideoProfileLevel> = emptyList(),
    val candidates: List<VideoEncoderCodecInfo> = emptyList(),
    val error: VideoEncoderError = VideoEncoderError.None,
) {
    val isSupported: Boolean
        get() = error == VideoEncoderError.None && selectedCodec != null
}

data class VideoProfileLevel(
    val profile: Int,
    val level: Int,
)
