package io.warpnect.platform.video.decoder

/** DEBUG-only, one-shot observations of remote decoder progression with no frame data. */
fun interface VideoDecoderDebugObserver {
    fun onEvent(event: VideoDecoderDebugEvent)

    companion object {
        val None = VideoDecoderDebugObserver {}
    }
}

enum class VideoDecoderDebugEvent {
    DecoderStarted,
    FirstAccessUnitSubmitted,
    FirstOutputAvailable,
    FirstFrameRendered,
}
