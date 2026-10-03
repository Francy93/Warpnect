package io.warpnect.debug.directshell

import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.platform.audio.playback.DebugMicrophoneInjectionSession
import java.util.concurrent.atomic.AtomicBoolean

/** Decoder resets do not end the authenticated Session's Android routing ownership. */
internal class DirectShellMicrophoneInputSession(
    private val input: RemoteMicrophonePcmDestination,
    private val lease: AutoCloseable,
) : DebugMicrophoneInjectionSession {
    private val closed = AtomicBoolean(false)

    override fun wrap(diagnosticPlayback: AudioPlaybackController): AudioPlaybackController =
        DirectShellMicrophonePlayback(diagnosticPlayback) { if (closed.get()) null else input }

    override fun close() {
        if (closed.compareAndSet(false, true)) lease.close()
    }
}
