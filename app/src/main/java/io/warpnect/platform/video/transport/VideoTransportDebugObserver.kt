package io.warpnect.platform.video.transport

/** DEBUG-only, one-shot observations at existing protected video transport boundaries. */
fun interface VideoTransportDebugObserver {
    fun onEvent(event: VideoTransportDebugEvent)

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
