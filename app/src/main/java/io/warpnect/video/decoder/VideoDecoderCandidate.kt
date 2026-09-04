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

        val hardware = avcCandidates.filter {
            it.info.hardwareAcceleration == VideoDecoderHardwareAcceleration.Hardware &&
                it.info.softwareOnly != true
        }
        val hardwareSelection =
            selectStaticCandidate(config, candidates, hardware, VideoDecoderQualification.FrameworkHardware)
        if (hardwareSelection != null) return hardwareSelection

        val legacyCandidates = avcCandidates.filter {
            it.info.hardwareAcceleration == VideoDecoderHardwareAcceleration.Unknown &&
                !LegacyVideoDecoderSoftwareClassifier.isKnownSoftwareFamily(
                    codecName = it.info.codecName,
                    canonicalName = it.info.canonicalName,
                )
        }
        val legacySelection = selectStaticCandidate(
            config = config,
            candidates = candidates,
            pool = legacyCandidates,
            qualification = VideoDecoderQualification.LegacyCandidate,
            error = VideoDecoderError.LegacyQualificationRequired,
        )
        if (legacySelection != null) return legacySelection

        val staticCandidates = hardware + legacyCandidates
        if (staticCandidates.isNotEmpty()) {
            if (staticCandidates.none { it.widthSupported && it.heightSupported && it.sizeSupported }) {
                return unsupported(config, candidates, VideoDecoderError.UnsupportedDimensions)
            }
            if (staticCandidates.none {
                    it.widthSupported && it.heightSupported && it.sizeSupported && it.sizeAndRateSupported
                }
            ) {
                return unsupported(config, candidates, VideoDecoderError.UnsupportedFrameRate)
            }
        }

        val hasExplicitSoftware = avcCandidates.any {
            it.info.hardwareAcceleration == VideoDecoderHardwareAcceleration.Software ||
                it.info.softwareOnly == true
        }
        val hasLegacySoftware = avcCandidates.any {
            it.info.hardwareAcceleration == VideoDecoderHardwareAcceleration.Unknown &&
                LegacyVideoDecoderSoftwareClassifier.isKnownSoftwareFamily(it.info.codecName, it.info.canonicalName)
        }
        return unsupported(
            config = config,
            candidates = candidates,
            error = if (hasExplicitSoftware || hasLegacySoftware) {
                VideoDecoderError.HardwareDecoderUnavailable
            } else {
                VideoDecoderError.HardwareClassificationUnavailable
            },
            qualification = when {
                hasExplicitSoftware -> VideoDecoderQualification.ExplicitSoftwareRejected
                hasLegacySoftware -> VideoDecoderQualification.LegacySoftwareFamilyRejected
                else -> VideoDecoderQualification.NotApplicable
            },
        )
    }

    private fun selectStaticCandidate(
        config: VideoDecoderConfig,
        candidates: List<VideoDecoderCandidate>,
        pool: List<VideoDecoderCandidate>,
        qualification: VideoDecoderQualification,
        error: VideoDecoderError = VideoDecoderError.None,
    ): VideoDecoderCapabilities? {
        if (pool.isEmpty()) return null
        val dimensions = pool.filter {
            it.widthSupported &&
                it.heightSupported &&
                it.sizeSupported
        }
        if (dimensions.isEmpty()) {
            return null
        }

        val frameRate = dimensions.filter { it.sizeAndRateSupported }
        if (frameRate.isEmpty()) {
            return null
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
            error = error,
            qualification = qualification,
        )
    }

    private fun unsupported(
        config: VideoDecoderConfig,
        candidates: List<VideoDecoderCandidate>,
        error: VideoDecoderError,
        qualification: VideoDecoderQualification = VideoDecoderQualification.NotApplicable,
    ): VideoDecoderCapabilities = VideoDecoderCapabilities(
        config = config,
        selectedCodec = null,
        support = null,
        candidates = candidates.map { it.info }.sortedBy { it.codecName },
        error = error,
        qualification = qualification,
    )
}
