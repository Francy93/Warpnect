package io.warpnect.video.decoder

data class VideoDecoderCandidate(
    val info: VideoDecoderCodecInfo,
    val supportsAvc: Boolean,
    val widthSupported: Boolean,
    val heightSupported: Boolean,
    val sizeSupported: Boolean,
    val sizeAndRateSupported: Boolean = true,
    val lowLatencyFeatureSupported: Boolean? = null,
    val widthAlignment: Int? = null,
    val heightAlignment: Int? = null,
    val minWidth: Int? = null,
    val maxWidth: Int? = null,
    val minHeight: Int? = null,
    val maxHeight: Int? = null,
) {
    fun support(): VideoDecoderSupport = VideoDecoderSupport(
        widthSupported = widthSupported,
        heightSupported = heightSupported,
        sizeSupported = sizeSupported,
        sizeAndRateSupported = sizeAndRateSupported,
        lowLatencyFeatureSupported = lowLatencyFeatureSupported,
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
    )
}

object VideoDecoderSelector {
    fun select(config: VideoDecoderConfig, candidates: List<VideoDecoderCandidate>): VideoDecoderCapabilities {
        val validation = VideoDecoderConfigValidator.validate(config)
        if (validation != VideoDecoderError.None) {
            return unsupported(config, candidates, validation)
        }

        val avcCandidates = candidates.filter { it.supportsAvc }
        if (avcCandidates.isEmpty()) {
            return unsupported(config, candidates, VideoDecoderError.UnsupportedCodec)
        }

        val classified = avcCandidates.filter {
            it.info.hardwareAcceleration != VideoDecoderHardwareAcceleration.Unknown
        }
        if (classified.isEmpty()) {
            return unsupported(config, candidates, VideoDecoderError.HardwareClassificationUnavailable)
        }

        val hardware = classified.filter {
            it.info.hardwareAcceleration == VideoDecoderHardwareAcceleration.Hardware &&
                it.info.softwareOnly != true
        }
        if (hardware.isEmpty()) {
            return unsupported(config, candidates, VideoDecoderError.HardwareDecoderUnavailable)
        }

        val dimensions = hardware.filter {
            it.widthSupported &&
                it.heightSupported &&
                it.sizeSupported
        }
        if (dimensions.isEmpty()) {
            return unsupported(config, candidates, VideoDecoderError.UnsupportedDimensions)
        }

        val frameRate = dimensions.filter { it.sizeAndRateSupported }
        if (frameRate.isEmpty()) {
            return unsupported(config, candidates, VideoDecoderError.UnsupportedFrameRate)
        }

        val selected = frameRate.sortedWith(
            compareBy<VideoDecoderCandidate>(
                { it.lowLatencyFeatureSupported != true },
                { it.info.alias == true },
                { it.info.canonicalName ?: it.info.codecName },
                { it.info.codecName },
            ),
        ).first()

        return VideoDecoderCapabilities(
            config = config,
            selectedCodec = selected.info,
            support = selected.support(),
            candidates = candidates.map { it.info }.sortedBy { it.codecName },
            error = VideoDecoderError.None,
        )
    }

    private fun unsupported(
        config: VideoDecoderConfig,
        candidates: List<VideoDecoderCandidate>,
        error: VideoDecoderError,
    ): VideoDecoderCapabilities = VideoDecoderCapabilities(
        config = config,
        selectedCodec = null,
        support = null,
        candidates = candidates.map { it.info }.sortedBy { it.codecName },
        error = error,
    )
}
