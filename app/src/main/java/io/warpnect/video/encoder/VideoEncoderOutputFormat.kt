package io.warpnect.video.encoder

data class VideoEncoderOutputFormat(
    val codec: VideoCodec,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameRate: Int?,
    val bitrateBps: Int?,
    val profile: Int?,
    val level: Int?,
    val outputReorderDepth: Int?,
    val reportedLatencyFrames: Int?,
    val codecSpecificData: List<ByteArray>,
) {
    fun hasCodecSpecificData(): Boolean = codecSpecificData.isNotEmpty()
}
