package io.warpnect.audio.session

enum class AudioPlaybackStartGateDecision {
    Start,
    Hold,
}

interface AudioPlaybackStartGate {
    fun evaluate(snapshot: AudioReceiverSessionSnapshot, nowNs: Long): AudioPlaybackStartGateDecision

    fun onPlaybackStarted() = Unit

    fun onPlaybackReset() = Unit
}
