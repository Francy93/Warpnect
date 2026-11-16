package io.warpnect.debug.directshell

import android.util.Log
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import java.nio.ByteBuffer

/** Diagnostic Oboe playout is unchanged; Android input integration has separate ownership/results. */
internal class DirectShellMicrophonePlayback(
    private val diagnostic: AudioPlaybackController,
    private val currentInput: () -> RemoteMicrophonePcmDestination?,
) : AudioPlaybackController by diagnostic {
    private var source: RemoteMicrophonePcmDestination? = null
    private var configGeneration: Long? = null
    private var rejectionLogs = 0
    private var slowDiagnosticLogs = 0

    override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult {
        release()
        rejectionLogs = 0
        slowDiagnosticLogs = 0
        val result = diagnostic.prepare(config)
        if (result.isSuccess && config.sampleRateHz == 48_000 && config.channelCount == 1) {
            source = currentInput()
            configGeneration = config.configGeneration
        } else {
            release()
        }
        return result
    }

    override fun start(): AudioPlaybackResult {
        val result = diagnostic.start()
        if ((result.error != AudioPlaybackError.None && result.error != AudioPlaybackError.AlreadyRunning) ||
            runCatching { source?.start() }.getOrNull() != true
        ) {
            release()
        }
        return result
    }

    override fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult {
        val admittedNs = System.nanoTime()
        var inputOffered = false
        // Input admission precedes potentially blocking speaker diagnostics; preserve this clock stamp.
        if (metadata.configGeneration == configGeneration && frameCount in 1..960 &&
            sizeBytes == frameCount * 2 && offset >= 0 && sizeBytes <= buffer.limit() - offset &&
            source === currentInput()
        ) {
            source?.let {
                it.submitTimed(buffer, offset, frameCount, metadata.firstFramePosition, admittedNs)
                inputOffered = true
            }
        }
        val diagnosticBeginNs = System.nanoTime()
        val result = diagnostic.submitPcm(buffer, offset, sizeBytes, frameCount, metadata)
        val diagnosticEndNs = System.nanoTime()
        if (diagnosticEndNs - diagnosticBeginNs >= 8_000_000 && slowDiagnosticLogs++ < 16) {
            runCatching {
                Log.i(
                    "WarpnectAudioDiagnostics",
                    "MIC_DEBUG_SUBMIT_STALL begin_ns=$diagnosticBeginNs end_ns=$diagnosticEndNs " +
                        "input_elapsed_ns=${diagnosticBeginNs - admittedNs} " +
                        "position=${metadata.firstFramePosition} frames=$frameCount error=${result.error}",
                )
            }
        }
        if (result.error == AudioPlaybackError.PlaybackRingFull && rejectionLogs++ < 8) {
            runCatching {
                Log.i(
                    "WarpnectAudioDiagnostics",
                    "MIC_DEBUG_REJECTION local_ns=${System.nanoTime()} generation=${metadata.configGeneration} " +
                        "position=${metadata.firstFramePosition} frames=$frameCount " +
                        "android_input_offered=$inputOffered",
                )
            }
        }
        return result
    }

    override fun stop(): AudioPlaybackResult {
        release()
        return diagnostic.stop()
    }

    override fun close() {
        release()
        diagnostic.close()
    }

    private fun release() {
        source = null
        configGeneration = null
    }
}
