package io.warpnect.audio.session

/** Optional synchronous DEBUG tracing; no executor, PCM queue or lifecycle owner. */
interface AudioPathTrace {
    fun waiting(stage: AudioPathStage)

    fun acquired(stage: AudioPathStage)

    fun finished()
}

enum class AudioPathStage(val waitName: String, val heldName: String) {
    SessionFrame("AudioSessionFrame.wait", "AudioSessionFrame.held"),
    SessionSnapshot("AudioSessionSnapshot.wait", "AudioSessionSnapshot.held"),
    DecoderFrame("AudioDecoderFrame.wait", "AudioDecoderFrame.held"),
}

internal inline fun <T> tracedAudioMonitor(
    trace: AudioPathTrace?,
    stage: AudioPathStage,
    monitor: Any,
    block: () -> T,
): T {
    trace?.waiting(stage)
    return synchronized(monitor) {
        trace?.acquired(stage)
        try {
            block()
        } finally {
            trace?.finished()
        }
    }
}
