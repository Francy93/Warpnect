package io.warpnect.platform.video.decoder

import io.warpnect.video.decoder.VideoDecoderState

/** Decoder milestones for UI state plus bounded diagnostics; no frame data. */
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
    CodecRenderCallback,
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
    FirstOutputReleasedForSurface,
    FirstCodecRenderCallback,
}

internal fun isCurrentDecoderRenderCallback(callbackCodec: Any, activeCodec: Any?, state: VideoDecoderState): Boolean =
    callbackCodec === activeCodec && (state == VideoDecoderState.Running || state == VideoDecoderState.Draining)

internal fun shouldMarkClientVideoStreaming(event: VideoDecoderDebugEvent): Boolean =
    event == VideoDecoderDebugEvent.FirstOutputReleasedForSurface
