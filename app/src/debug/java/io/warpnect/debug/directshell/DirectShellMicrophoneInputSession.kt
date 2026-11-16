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
    private val accepting = AtomicBoolean(true)

    override fun wrap(diagnosticPlayback: AudioPlaybackController): AudioPlaybackController =
        DirectShellMicrophonePlayback(AsyncMicrophoneDiagnosticPlayback(diagnosticPlayback)) {
            if (closed.get() || !accepting.get()) null else input
        }

    override fun stopAcceptingPcm() {
        accepting.set(false)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            stopAcceptingPcm()
            lease.close()
        }
    }
}
