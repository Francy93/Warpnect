package io.warpnect.platform.audio.playback

import io.warpnect.BuildConfig
import io.warpnect.audio.playback.AudioPlaybackController

/** Integration seam for the optional, independently qualified Android input destination. */
fun interface DebugMicrophoneInjectionOverride {
    fun createSession(): DebugMicrophoneInjectionSession
}

interface DebugMicrophoneInjectionSession : AutoCloseable {
    fun wrap(diagnosticPlayback: AudioPlaybackController): AudioPlaybackController
}

object DebugMicrophoneInjectionOverrideRegistry {
    @Volatile private var current: DebugMicrophoneInjectionOverride? = null

    @Synchronized fun install(override: DebugMicrophoneInjectionOverride): Boolean {
        if (!BuildConfig.DEBUG || current != null) return false
        current = override
        return true
    }

    @Synchronized fun clear(override: DebugMicrophoneInjectionOverride) {
        if (current === override) current = null
    }

    fun createSession(): DebugMicrophoneInjectionSession? = if (BuildConfig.DEBUG) current?.createSession() else null
}
