package io.warpnect.debug.directshell

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

    override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult {
        release()
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
        val result = diagnostic.submitPcm(buffer, offset, sizeBytes, frameCount, metadata)
        // Speaker diagnostics are not the Android input destination's admission boundary.
        if ((result.isSuccess || result.error == AudioPlaybackError.PlaybackRingFull) &&
            metadata.configGeneration == configGeneration && sizeBytes == frameCount * 2 &&
            source === currentInput()
        ) {
            source?.submit(buffer, offset, frameCount)
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
