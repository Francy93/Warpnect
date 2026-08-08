package io.warpnect.video.encoder

data class VideoEncoderCandidate(
    val info: VideoEncoderCodecInfo,
    val supportsAvc: Boolean,
    val supportsSurfaceInput: Boolean,
    val widthSupported: Boolean,
    val heightSupported: Boolean,
    val sizeSupported: Boolean,
    val sizeAndRateSupported: Boolean,
    val bitrateSupported: Boolean,
    val bitrateModeSupported: Boolean,
    val widthAlignment: Int? = null,
    val heightAlignment: Int? = null,
    val minWidth: Int? = null,
    val maxWidth: Int? = null,
    val minHeight: Int? = null,
    val maxHeight: Int? = null,
    val minBitrateBps: Int? = null,
    val maxBitrateBps: Int? = null,
    val supportedProfiles: List<VideoProfileLevel> = emptyList(),
) {
    fun support(): VideoEncoderSupport = VideoEncoderSupport(
        widthSupported = widthSupported,
        heightSupported = heightSupported,
        sizeSupported = sizeSupported,
        sizeAndRateSupported = sizeAndRateSupported,
        bitrateSupported = bitrateSupported,
        bitrateModeSupported = bitrateModeSupported,
        surfaceInputSupported = supportsSurfaceInput,
        widthAlignment = widthAlignment,
        heightAlignment = heightAlignment,
        minWidth = minWidth,
        maxWidth = maxWidth,
        minHeight = minHeight,
        maxHeight = maxHeight,
        minBitrateBps = minBitrateBps,
        maxBitrateBps = maxBitrateBps,
    )
}

object VideoEncoderSelector {
    fun select(request: VideoEncoderRequest, candidates: List<VideoEncoderCandidate>): VideoEncoderCapabilities {
        val validation = VideoEncoderRequestValidator.validate(request)
        if (validation != VideoEncoderError.None) {
            return VideoEncoderCapabilities(
                request = request,
                selectedCodec = null,
                support = null,
                candidates = candidates.map { it.info }.sortedBy { it.codecName },
                error = validation,
            )
        }

        val avcCandidates = candidates.filter { it.supportsAvc }
        if (avcCandidates.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.UnsupportedCodec)
        }

        val classified = avcCandidates.filter {
            it.info.hardwareAcceleration != VideoEncoderHardwareAcceleration.Unknown
        }
        if (classified.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.HardwareClassificationUnavailable)
        }

        val hardware = classified.filter {
            it.info.hardwareAcceleration == VideoEncoderHardwareAcceleration.Hardware &&
                it.info.softwareOnly != true
        }
        if (hardware.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.HardwareEncoderUnavailable)
        }

        val surface = hardware.filter { it.supportsSurfaceInput }
        if (surface.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.SurfaceInputUnsupported)
        }

        val dimensions = surface.filter {
            it.widthSupported &&
                it.heightSupported &&
                it.sizeSupported
        }
        if (dimensions.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.UnsupportedDimensions)
        }

        val frameRate = dimensions.filter { it.sizeAndRateSupported }
        if (frameRate.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.UnsupportedFrameRate)
        }

        val bitrate = frameRate.filter { it.bitrateSupported }
        if (bitrate.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.UnsupportedBitrate)
        }

        val bitrateMode = bitrate.filter { it.bitrateModeSupported }
        if (bitrateMode.isEmpty()) {
            return unsupported(request, candidates, VideoEncoderError.UnsupportedBitrateMode)
        }

        val selected = bitrateMode.sortedWith(
            compareBy<VideoEncoderCandidate>(
                { it.info.alias == true },
                { it.info.canonicalName ?: it.info.codecName },
                { it.info.codecName },
            ),
        ).first()

        return VideoEncoderCapabilities(
            request = request,
            selectedCodec = selected.info,
            support = selected.support(),
            supportedProfiles = selected.supportedProfiles,
            candidates = candidates.map { it.info }.sortedBy { it.codecName },
            error = VideoEncoderError.None,
        )
    }

    private fun unsupported(
        request: VideoEncoderRequest,
        candidates: List<VideoEncoderCandidate>,
        error: VideoEncoderError,
    ): VideoEncoderCapabilities = VideoEncoderCapabilities(
        request = request,
        selectedCodec = null,
        support = null,
        candidates = candidates.map { it.info }.sortedBy { it.codecName },
        error = error,
    )
}
