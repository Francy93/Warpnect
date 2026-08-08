package io.warpnect.video.encoder

data class VideoEncoderRequest(
    val codec: VideoCodec = VideoCodec.Avc,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitrateBps: Int,
    val iFrameIntervalSeconds: Float,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.Cbr,
)

object VideoEncoderRequestValidator {
    fun validate(request: VideoEncoderRequest): VideoEncoderError = when {
        request.codec != VideoCodec.Avc -> VideoEncoderError.UnsupportedCodec
        request.width <= 0 -> VideoEncoderError.InvalidRequest
        request.height <= 0 -> VideoEncoderError.InvalidRequest
        request.frameRate <= 0 -> VideoEncoderError.InvalidRequest
        request.bitrateBps <= 0 -> VideoEncoderError.InvalidRequest
        request.iFrameIntervalSeconds < 0f -> VideoEncoderError.InvalidRequest
        !request.iFrameIntervalSeconds.isFinite() -> VideoEncoderError.InvalidRequest
        request.bitrateMode != VideoBitrateMode.Cbr -> VideoEncoderError.UnsupportedBitrateMode
        else -> VideoEncoderError.None
    }
}
