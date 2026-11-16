package io.warpnect.platform.audio.playback

import android.util.Log
import io.warpnect.BuildConfig
import io.warpnect.audio.capture.AudioCaptureSource
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
import io.warpnect.audio.playback.MicrophonePcmRingAdmission
import io.warpnect.platform.audio.PcmDeliveryDebugProbe
import io.warpnect.telemetry.NativeAudioPlaybackTelemetry
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

class NativeOboeAudioPlaybackController internal constructor(
    private val backend: OboeAudioPlaybackBackend,
    private val playbackTelemetry: NativeAudioPlaybackTelemetry? = null,
    private val observations: ScheduledExecutorService? = null,
) : AudioPlaybackController, MicrophonePcmRingAdmission {
    constructor(telemetry: NativeAudioPlaybackTelemetry? = null) : this(
        NativeOboeAudioPlaybackBackend,
        telemetry,
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "MicrophoneDebugObservation").apply { isDaemon = true }
        },
    )

    private val lock = Any()
    private val lifetime = ReentrantReadWriteLock()
    private var observationTask: ScheduledFuture<*>? = null
    private var state = AudioPlaybackState.Stopped
    private var handle: Long = 0L
    private var config: AudioPlaybackConfig? = null

    @Volatile private var localSnapshot = AudioPlaybackSnapshot()
    private data class MicrophoneAdmission(val handle: Long, val config: AudioPlaybackConfig)

    @Volatile private var microphoneAdmission: MicrophoneAdmission? = null
    private var deliveryProbe: PcmDeliveryDebugProbe? = null
    private var startupRejectLogs = 0
    private var firstConsumerLogged = false
    private var lastLoggedUnderrunNs = 0L
    private var underrunLogs = 0
    private var observationStartNs = 0L
    private var steadyRejectLogs = 0
    private var steadyUnderrunLogs = 0

    @Volatile private var lastSubmitNs = 0L

    override fun admitMicrophonePcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult {
        val lease = lifetime.readLock()
        if (!lease.tryLock()) return AudioPlaybackResult(AudioPlaybackError.NotRunning, localSnapshot)
        try {
            val admission = microphoneAdmission
                ?: return AudioPlaybackResult(AudioPlaybackError.NotRunning, localSnapshot)
            val validation = validateBuffer(buffer, offset, sizeBytes, frameCount, metadata, admission.config)
            if (validation != AudioPlaybackError.None) return AudioPlaybackResult(validation, localSnapshot)
            // The native MicrophoneAudio branch only copies into the existing SPSC ring.
            val error = backend.submitPcm(admission.handle, buffer, offset, sizeBytes, frameCount, metadata)
            if (BuildConfig.DEBUG) lastSubmitNs = System.nanoTime()
            return AudioPlaybackResult(error, localSnapshot.copy(lastError = error))
        } finally {
            lease.unlock()
        }
    }

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

        val prepareStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val created = backend.create(config)
        if (BuildConfig.DEBUG) {
            logDiagnostic(
                "AUDIO_PLAYBACK_PREPARE source=${config.source} generation=${config.configGeneration} " +
                    "start_ns=$prepareStartNs end_ns=${System.nanoTime()} error=${created.error}",
            )
        }
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
        startupRejectLogs = 0
        firstConsumerLogged = false
        lastLoggedUnderrunNs = 0L
        underrunLogs = 0
        observationStartNs = System.nanoTime()
        steadyRejectLogs = 0
        steadyUnderrunLogs = 0
        lastSubmitNs = 0L
        // Microphone signal proof uses the capture/ordinary-consumer probes. Do
        // not re-analyse every decoded packet on the protected receive thread.
        deliveryProbe = if (config.source == AudioCaptureSource.MicrophoneAudio) {
            null
        } else {
            PcmDeliveryDebugProbe.create("${config.source}_decoded_playback")
        }
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
        if (BuildConfig.DEBUG) lastSubmitNs = System.nanoTime()
        if (currentConfig.source == AudioCaptureSource.MicrophoneAudio) {
            if (error != AudioPlaybackError.None) recordFailure(error)
            return@synchronized resultLocked(error)
        }
        deliveryProbe?.observe(buffer, offset, sizeBytes, frameCount, System.nanoTime())
        if (error != AudioPlaybackError.None) {
            recordFailure(error)
            if (BuildConfig.DEBUG &&
                error == AudioPlaybackError.PlaybackRingFull && allowRejectDiagnostic()
            ) {
                refreshSnapshot()
                logDiagnostic(
                    "AUDIO_PLAYBACK_FULL local_ns=${System.nanoTime()} position=${metadata.firstFramePosition} " +
                        "occupancy=${localSnapshot.ringOccupancyFrames} consumed=${localSnapshot.pcmFramesConsumed} " +
                        "source=${currentConfig.source} generation=${metadata.configGeneration}",
                )
            }
            return@synchronized resultLocked(error)
        }
        refreshSnapshot()
        if (BuildConfig.DEBUG &&
            localSnapshot.lastUnderrunTimeNs > lastLoggedUnderrunNs && allowUnderrunDiagnostic()
        ) {
            lastLoggedUnderrunNs = localSnapshot.lastUnderrunTimeNs
            logDiagnostic(
                "AUDIO_PLAYBACK_UNDERRUN callback_ns=$lastLoggedUnderrunNs " +
                    "missing_frames=${localSnapshot.lastUnderrunMissingFrames} " +
                    "total=${localSnapshot.underrunCallbacks} occupancy=${localSnapshot.ringOccupancyFrames}",
            )
        }
        if (BuildConfig.DEBUG && !firstConsumerLogged &&
            localSnapshot.pcmFramesConsumed > 0
        ) {
            firstConsumerLogged = true
            logDiagnostic(
                "AUDIO_PLAYBACK_FIRST_CONSUMPTION_OBSERVED local_ns=${System.nanoTime()} " +
                    "consumed=${localSnapshot.pcmFramesConsumed}",
            )
        }
        localSnapshot = localSnapshot.copy(lastError = AudioPlaybackError.None)
        resultLocked(AudioPlaybackError.None)
    }

    private fun allowRejectDiagnostic(): Boolean = if (System.nanoTime() - observationStartNs < 10_000_000_000L) {
        startupRejectLogs++ < 12
    } else {
        steadyRejectLogs++ < 8
    }

    private fun allowUnderrunDiagnostic(): Boolean = if (System.nanoTime() - observationStartNs < 10_000_000_000L) {
        underrunLogs++ < 16
    } else {
        steadyUnderrunLogs++ < 8
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
        val startedNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        val error = backend.start(handle)
        if (BuildConfig.DEBUG && error == AudioPlaybackError.None) {
            logDiagnostic(
                "AUDIO_PLAYBACK_START_RETURN start_ns=$startedNs end_ns=${System.nanoTime()}",
            )
        }
        if (error == AudioPlaybackError.None) {
            state = AudioPlaybackState.Running
            refreshSnapshot()
            localSnapshot = localSnapshot.copy(state = state, lastError = AudioPlaybackError.None)
            if (config?.source == AudioCaptureSource.MicrophoneAudio) {
                microphoneAdmission = MicrophoneAdmission(handle, requireNotNull(config))
            }
            if (config?.source == AudioCaptureSource.MicrophoneAudio && observationTask == null) {
                observationTask = observations?.scheduleWithFixedDelay(
                    { sampleMicrophoneDiagnostics() },
                    0,
                    100,
                    TimeUnit.MILLISECONDS,
                )
            }
        } else {
            localSnapshot = localSnapshot.copy(lastError = error)
        }
        resultLocked(error)
    }

    override fun stop(): AudioPlaybackResult {
        microphoneAdmission = null
        if (!lifetime.writeLock().tryLock(3500, TimeUnit.MILLISECONDS)) {
            return synchronized(lock) { resultLocked(AudioPlaybackError.StreamStopFailed) }
        }
        return try {
            stopLocked()
        } finally {
            lifetime.writeLock().unlock()
        }
    }

    private fun stopLocked(): AudioPlaybackResult = synchronized(lock) {
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
        observationTask?.cancel(false)
        observationTask = null
        val finalXrun = if (config?.source == AudioCaptureSource.MicrophoneAudio) {
            backend.sampleXRunCount(
                handle,
            )
        } else {
            -1
        }
        if (finalXrun >= 0) localSnapshot = localSnapshot.copy(xRunCount = finalXrun)
        val stopStartNs = if (BuildConfig.DEBUG) System.nanoTime() else 0L
        if (BuildConfig.DEBUG) {
            refreshSnapshot()
            logDiagnostic(
                "AUDIO_PLAYBACK_STOP_BEGIN source=${localSnapshot.source} " +
                    "generation=${localSnapshot.configGeneration} local_ns=$stopStartNs " +
                    "last_submit_ns=$lastSubmitNs last_underrun_ns=${localSnapshot.lastUnderrunTimeNs} " +
                    "underrun=${localSnapshot.underrunCallbacks} rejected=${localSnapshot.pcmFramesRejected} " +
                    "occupancy=${localSnapshot.ringOccupancyFrames} xrun=${localSnapshot.xRunCount}",
            )
        }
        val error = backend.stop(handle)
        state = if (error == AudioPlaybackError.None) AudioPlaybackState.Prepared else AudioPlaybackState.Error
        refreshSnapshot()
        localSnapshot = localSnapshot.copy(state = state, lastError = error)
        if (BuildConfig.DEBUG) {
            // The last callback can occur after the last PCM submission; retain its exact boundary.
            logDiagnostic(
                "AUDIO_PLAYBACK_STOP source=${localSnapshot.source} generation=${localSnapshot.configGeneration} " +
                    "start_ns=$stopStartNs end_ns=${System.nanoTime()} last_submit_ns=$lastSubmitNs " +
                    "last_underrun_ns=${localSnapshot.lastUnderrunTimeNs} " +
                    "last_missing_frames=${localSnapshot.lastUnderrunMissingFrames} " +
                    "post_reset_underrun=${localSnapshot.underrunCallbacks} " +
                    "post_reset_rejected=${localSnapshot.pcmFramesRejected} " +
                    "xrun=${localSnapshot.xRunCount} error=$error",
            )
        }
        resultLocked(error)
    }

    override fun snapshot(): AudioPlaybackSnapshot {
        if (localSnapshot.source == AudioCaptureSource.MicrophoneAudio) return localSnapshot
        return synchronized(lock) {
            if (config?.source != AudioCaptureSource.MicrophoneAudio) refreshSnapshot()
            localSnapshot
        }
    }

    /** No PCM mailbox: only numeric observation is offloaded. Close pins the native lifetime. */
    internal fun sampleMicrophoneDiagnostics() = lifetime.read {
        val observedHandle = synchronized(lock) {
            if (state != AudioPlaybackState.Running ||
                config?.source != AudioCaptureSource.MicrophoneAudio
            ) {
                return@read
            }
            handle
        }
        val xrun = backend.sampleXRunCount(observedHandle)
        val message = synchronized(lock) {
            if (handle != observedHandle || state != AudioPlaybackState.Running) return@synchronized null
            refreshSnapshot()
            if (xrun >= 0) localSnapshot = localSnapshot.copy(xRunCount = xrun)
            if (BuildConfig.DEBUG &&
                localSnapshot.lastUnderrunTimeNs > lastLoggedUnderrunNs && allowUnderrunDiagnostic()
            ) {
                lastLoggedUnderrunNs = localSnapshot.lastUnderrunTimeNs
                "AUDIO_PLAYBACK_UNDERRUN callback_ns=$lastLoggedUnderrunNs " +
                    "missing_frames=${localSnapshot.lastUnderrunMissingFrames} " +
                    "total=${localSnapshot.underrunCallbacks} occupancy=${localSnapshot.ringOccupancyFrames}"
            } else {
                null
            }
        }
        if (message != null) logDiagnostic(message)
    }

    override fun queryPresentationTimestamp(): AudioPresentationTimestampResult = synchronized(lock) {
        if (config?.source == AudioCaptureSource.MicrophoneAudio) {
            return@synchronized AudioPresentationTimestampResult(AudioPlaybackError.PresentationTimestampUnavailable)
        }
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
        if (config?.source == AudioCaptureSource.MicrophoneAudio) {
            return@synchronized AudioSourcePresentationAnchorResult(AudioPlaybackError.PresentationTimestampUnavailable)
        }
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
        microphoneAdmission = null
        check(lifetime.writeLock().tryLock(3500, TimeUnit.MILLISECONDS)) {
            "Microphone diagnostic observer still owns native playback; handle retained, not freed"
        }
        try {
            synchronized(lock) {
                closeLocked()
            }
        } finally {
            lifetime.writeLock().unlock()
        }
    }

    private fun closeLocked() {
        if (state == AudioPlaybackState.Closed) return
        observationTask?.cancel(false)
        observationTask = null
        observations?.shutdown()
        if (handle != 0L) {
            backend.destroy(handle)
        }
        handle = 0L
        deliveryProbe?.flush(System.nanoTime())
        deliveryProbe = null
        config = null
        state = AudioPlaybackState.Closed
        localSnapshot = AudioPlaybackSnapshot(state = state, lastError = AudioPlaybackError.None)
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
        return validateBuffer(buffer, offset, sizeBytes, frameCount, metadata, config)
    }

    private fun validateBuffer(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
        config: AudioPlaybackConfig,
    ): AudioPlaybackError {
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
            val sampled = backend.snapshot(handle, state)
            localSnapshot = if (config?.source == AudioCaptureSource.MicrophoneAudio) {
                sampled.copy(xRunCount = localSnapshot.xRunCount)
            } else {
                sampled
            }
        }
    }

    private fun logDiagnostic(message: String) {
        runCatching { Log.i("WarpnectAudioDiagnostics", message) }
    }

    private fun recordFailure(error: AudioPlaybackError) {
        if (error == AudioPlaybackError.PlaybackRingFull ||
            error == AudioPlaybackError.ConfigGenerationMismatch ||
            error == AudioPlaybackError.PlaybackNotPrimed
        ) {
            if (config?.source != AudioCaptureSource.MicrophoneAudio) refreshSnapshot()
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
