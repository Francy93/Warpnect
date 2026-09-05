package io.warpnect.platform.video.decoder

/** DEBUG-only, one-shot observations of remote decoder progression with no frame data. */
fun interface VideoDecoderDebugObserver {
    fun onEvent(event: VideoDecoderDebugEvent)

    /** Bounded DEBUG-only local timing observation with no frame contents or peer data. */
    fun onPresentationObservation(observation: VideoDecoderPresentationObservation) = Unit

    companion object {
        val None = VideoDecoderDebugObserver {}
    }
}

enum class VideoDecoderPresentationStage {
    InputQueued,
    OutputAvailable,
    OutputReleasedForSurface,
    FrameRendered,
}

data class VideoDecoderPresentationObservation(
    val stage: VideoDecoderPresentationStage,
    val presentationTimeUs: Long,
    val localMonotonicNs: Long,
    val renderedToSurface: Boolean? = null,
    val scheduledRenderTimestampNs: Long? = null,
    val codecCallbackNanoTime: Long? = null,
)

enum class VideoDecoderDebugEvent {
    DecoderStarted,
    FirstAccessUnitSubmitted,
    FirstOutputAvailable,
    FirstFrameRendered,
}
