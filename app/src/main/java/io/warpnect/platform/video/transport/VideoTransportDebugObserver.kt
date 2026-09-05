package io.warpnect.platform.video.transport

/** DEBUG-only, one-shot observations at existing protected video transport boundaries. */
fun interface VideoTransportDebugObserver {
    fun onEvent(event: VideoTransportDebugEvent)

    /** Bounded DEBUG-only local observation after an AU has been reconstructed for the decoder. */
    fun onAccessUnitReady(presentationTimeUs: Long, keyframe: Boolean, localMonotonicNs: Long) = Unit

    companion object {
        val None = VideoTransportDebugObserver {}
    }
}

enum class VideoTransportDebugEvent {
    FirstVideoDatagramSent,
    FirstVideoDatagramReceived,
    FirstStreamConfigAvailable,
    FirstVideoAccessUnitReceived,
}
