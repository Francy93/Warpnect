package io.warpnect.platform.audio.playback

import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPlaybackValidation
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import io.warpnect.telemetry.NativeAudioPlaybackTelemetry
import java.nio.ByteBuffer

class NativeOboeAudioPlaybackController internal constructor(
    private val backend: OboeAudioPlaybackBackend,
    private val playbackTelemetry: NativeAudioPlaybackTelemetry? = null,
) : AudioPlaybackController {
    constructor(telemetry: NativeAudioPlaybackTelemetry? = null) : this(NativeOboeAudioPlaybackBackend, telemetry)

    private val lock = Any()
    private var state = AudioPlaybackState.Stopped
    private var handle: Long = 0L
    private var config: AudioPlaybackConfig? = null
    private var localSnapshot = AudioPlaybackSnapshot()

    override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult = synchronized(lock) {
        val validation = AudioPlaybackValidation.validate(config)
        if (validation != AudioPlaybackError.None) {
            failLocked(validation)
            return@synchronized resultLocked(validation)
        }
        if (state != AudioPlaybackState.Stopped) {
            val error = if (state == AudioPlaybackState.Closed) {
                AudioPlaybackError.Closed
            } else {
                AudioPlaybackError.AlreadyPrepared
            }
            failLocked(error)
            return@synchronized resultLocked(error)
        }

        state = AudioPlaybackState.Preparing
        localSnapshot = AudioPlaybackSnapshot(
            state = state,
            source = config.source,
            configGeneration = config.configGeneration,
            requestedSampleRateHz = config.sampleRateHz,
            requestedChannelCount = config.channelCount,
            frameDurationUs = config.frameDurationUs,
            framesPerCodecFrame = config.framesPerCodecFrame,
            lookaheadSamples = config.lookaheadSamples,
            requestedBufferBursts = config.requestedBufferBursts,
        )

        val created = backend.create(config)
        if (created.error != AudioPlaybackError.None || created.handle == 0L) {
            state = AudioPlaybackState.Error
            localSnapshot = localSnapshot.copy(state = state, lastError = created.error)
            return@synchronized resultLocked(created.error)
        }
        handle = created.handle
        val telemetryError = playbackTelemetry?.sourceId?.let { backend.attachTelemetry(handle, it.value.toLong()) }
        if (telemetryError != null && telemetryError != AudioPlaybackError.None) {
            // Telemetry is observational: native source attachment failure never affects playback.
        }
        this.config = config
        state = AudioPlaybackState.Prepared
        localSnapshot = created.snapshot.copy(state = state, lastError = AudioPlaybackError.None)
        resultLocked(AudioPlaybackError.None)
    }

    override fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult = synchronized(lock) {
        val currentConfig = config ?: return@synchronized resultLocked(AudioPlaybackError.NotPrepared)
        val validation = validateSubmit(buffer, offset, sizeBytes, frameCount, metadata, currentConfig)
        if (validation != AudioPlaybackError.None) {
            recordFailure(validation)
            return@synchronized resultLocked(validation)
        }
        val error = backend.submitPcm(handle, buffer, offset, sizeBytes, frameCount, metadata)
        if (error != AudioPlaybackError.None) {
            recordFailure(error)
            return@synchronized resultLocked(error)
        }
        refreshSnapshot()
        localSnapshot = localSnapshot.copy(lastError = AudioPlaybackError.None)
        resultLocked(AudioPlaybackError.None)
    }

    override fun start(): AudioPlaybackResult = synchronized(lock) {
        if (state == AudioPlaybackState.Running) {
            return@synchronized resultLocked(AudioPlaybackError.AlreadyRunning)
        }
        if (state != AudioPlaybackState.Prepared) {
            val error = if (state == AudioPlaybackState.Closed) {
                AudioPlaybackError.Closed
            } else {
                AudioPlaybackError.NotPrepared
            }
            return@synchronized resultLocked(error)
        }
        val error = backend.start(handle)
        if (error == AudioPlaybackError.None) {
            state = AudioPlaybackState.Running
            refreshSnapshot()
            localSnapshot = localSnapshot.copy(state = state, lastError = AudioPlaybackError.None)
        } else {
            localSnapshot = localSnapshot.copy(lastError = error)
        }
        resultLocked(error)
    }

    override fun stop(): AudioPlaybackResult = synchronized(lock) {
        if (state == AudioPlaybackState.Stopped) {
            return@synchronized resultLocked(AudioPlaybackError.None)
        }
        if (state == AudioPlaybackState.Closed) {
            return@synchronized resultLocked(AudioPlaybackError.Closed)
        }
        if (state != AudioPlaybackState.Running) {
            return@synchronized resultLocked(AudioPlaybackError.NotRunning)
        }
        state = AudioPlaybackState.Stopping
        val error = backend.stop(handle)
        state = if (error == AudioPlaybackError.None) AudioPlaybackState.Prepared else AudioPlaybackState.Error
        refreshSnapshot()
        localSnapshot = localSnapshot.copy(state = state, lastError = error)
        resultLocked(error)
    }

    override fun snapshot(): AudioPlaybackSnapshot = synchronized(lock) {
        refreshSnapshot()
        localSnapshot
    }

    override fun queryPresentationTimestamp(): AudioPresentationTimestampResult = synchronized(lock) {
        if (state == AudioPlaybackState.Closed) {
            return@synchronized AudioPresentationTimestampResult(error = AudioPlaybackError.Closed)
        }
        if (handle == 0L) {
            return@synchronized AudioPresentationTimestampResult(error = AudioPlaybackError.NotPrepared)
        }
        val result = backend.queryPresentationTimestamp(handle)
        refreshSnapshot()
        result
    }

    override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult = synchronized(lock) {
        if (state == AudioPlaybackState.Closed) {
            return@synchronized AudioSourcePresentationAnchorResult(error = AudioPlaybackError.Closed)
        }
        if (handle == 0L) {
            return@synchronized AudioSourcePresentationAnchorResult(error = AudioPlaybackError.NotPrepared)
        }
        val result = backend.querySourcePresentationAnchor(handle)
        refreshSnapshot()
        result
    }

    override fun close() {
        synchronized(lock) {
            if (state == AudioPlaybackState.Closed) return
            if (handle != 0L) {
                backend.destroy(handle)
            }
            handle = 0L
            config = null
            state = AudioPlaybackState.Closed
            localSnapshot = AudioPlaybackSnapshot(state = state, lastError = AudioPlaybackError.None)
        }
    }

    private fun validateSubmit(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
        config: AudioPlaybackConfig,
    ): AudioPlaybackError {
        if (state != AudioPlaybackState.Prepared && state != AudioPlaybackState.Running) {
            return AudioPlaybackError.NotPrepared
        }
        if (!buffer.isDirect) return AudioPlaybackError.NonDirectBuffer
        if (offset < 0 || sizeBytes <= 0) return AudioPlaybackError.InvalidBufferRange
        if (offset.toLong() + sizeBytes.toLong() > buffer.capacity().toLong()) {
            return AudioPlaybackError.InvalidBufferRange
        }
        if (frameCount <= 0 || frameCount > config.framesPerCodecFrame) {
            return AudioPlaybackError.InvalidFrameCount
        }
        val expectedBytes = frameCount.toLong() * config.channelCount.toLong() * Short.SIZE_BYTES.toLong()
        if (expectedBytes != sizeBytes.toLong()) return AudioPlaybackError.InvalidBufferRange
        if (metadata.configGeneration != config.configGeneration) {
            return AudioPlaybackError.ConfigGenerationMismatch
        }
        if (metadata.firstFramePosition < 0 || metadata.captureTimeUs < 0) {
            return AudioPlaybackError.InvalidConfiguration
        }
        return AudioPlaybackError.None
    }

    private fun refreshSnapshot() {
        if (handle != 0L && state != AudioPlaybackState.Closed) {
            localSnapshot = backend.snapshot(handle, state)
        }
    }

    private fun recordFailure(error: AudioPlaybackError) {
        if (error == AudioPlaybackError.PlaybackRingFull ||
            error == AudioPlaybackError.ConfigGenerationMismatch ||
            error == AudioPlaybackError.PlaybackNotPrimed
        ) {
            refreshSnapshot()
            localSnapshot = localSnapshot.copy(lastError = error)
        } else {
            failLocked(error)
        }
    }

    private fun failLocked(error: AudioPlaybackError) {
        state = if (state == AudioPlaybackState.Closed) AudioPlaybackState.Closed else AudioPlaybackState.Error
        localSnapshot = localSnapshot.copy(state = state, lastError = error)
    }

    private fun resultLocked(error: AudioPlaybackError): AudioPlaybackResult =
        AudioPlaybackResult(error = error, snapshot = localSnapshot.copy(state = state))
}
