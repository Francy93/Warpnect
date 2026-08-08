package io.warpnect.video.encoder

data class VideoEncoderFormatPlan(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val colorFormat: Int,
    val bitrateBps: Int,
    val bitrateMode: Int,
    val frameRate: Int,
    val iFrameIntervalSeconds: Float,
    val priority: Int?,
    val latencyFrames: Int?,
    val maxBFrames: Int?,
    val maxFpsToEncoder: Float?,
)

data class VideoEncoderFormatSupport(
    val supportsPriority: Boolean,
    val supportsLatency: Boolean,
    val supportsMaxBFrames: Boolean,
    val supportsMaxFpsToEncoder: Boolean,
)

object VideoEncoderFormatPlanner {
    const val COLOR_FORMAT_SURFACE = 0x7F000789
    const val BITRATE_MODE_CBR = 2
    const val REALTIME_PRIORITY = 0
    const val LOW_LATENCY_FRAMES = 1

    fun build(request: VideoEncoderRequest, support: VideoEncoderFormatSupport): VideoEncoderFormatPlan {
        val validation = VideoEncoderRequestValidator.validate(request)
        require(validation == VideoEncoderError.None) { "Invalid encoder request: $validation" }

        return VideoEncoderFormatPlan(
            mimeType = request.codec.mimeType,
            width = request.width,
            height = request.height,
            colorFormat = COLOR_FORMAT_SURFACE,
            bitrateBps = request.bitrateBps,
            bitrateMode = BITRATE_MODE_CBR,
            frameRate = request.frameRate,
            iFrameIntervalSeconds = request.iFrameIntervalSeconds,
            priority = if (support.supportsPriority) REALTIME_PRIORITY else null,
            latencyFrames = if (support.supportsLatency) LOW_LATENCY_FRAMES else null,
            maxBFrames = if (support.supportsMaxBFrames) 0 else null,
            maxFpsToEncoder = if (support.supportsMaxFpsToEncoder) request.frameRate.toFloat() else null,
        )
    }
}
