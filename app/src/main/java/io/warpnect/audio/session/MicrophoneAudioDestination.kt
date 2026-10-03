package io.warpnect.audio.session

/** Host destination only; this does not alter Audio Payload V1 or the required Host capabilities. */
enum class MicrophoneAudioDestination {
    HostVirtualInputNotImplemented,
    DebugPlayback,
    ;

    val receiverAvailable: Boolean get() = this == DebugPlayback

    companion object {
        fun forBuild(debug: Boolean): MicrophoneAudioDestination =
            if (debug) DebugPlayback else HostVirtualInputNotImplemented
    }
}
