package io.warpnect.avsync

import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.video.render.VideoRenderPolicy
import java.util.concurrent.atomic.AtomicReference

fun interface AudioPresentationAnchorSource {
    fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult
}

interface AvSyncController : AutoCloseable {
    fun start(config: AvSyncConfig): AvSyncResult

    fun stop(): AvSyncResult

    fun observeAudioTiming(captureTimeUs: Long, readyLocalUs: Long)

    fun observeVideoDecoded(presentationTimeUs: Long, readyLocalUs: Long)

    fun invalidateAudioUnderrun()

    fun invalidateAudioReset()

    fun notifyVideoReset()

    fun renderPolicy(): VideoRenderPolicy

    fun snapshot(): AvSyncSnapshot

    override fun close()
}

class DefaultAvSyncController(
    private val audioAnchorSource: AudioPresentationAnchorSource,
    private val clock: AvSyncClock = SystemAvSyncClock,
    private val startWorker: Boolean = true,
) : AvSyncController {
    constructor(
        playbackController: AudioPlaybackController,
        clock: AvSyncClock = SystemAvSyncClock,
        startWorker: Boolean = true,
    ) : this(
        audioAnchorSource = AudioPresentationAnchorSource {
            playbackController.querySourcePresentationAnchor()
        },
        clock = clock,
        startWorker = startWorker,
    )

    private val stateLock = Any()
    private val modelRef = AtomicReference(AvSyncModel())
    private val snapshotRef = AtomicReference(AvSyncSnapshot())

    @Volatile
    private var config = AvSyncConfig(enabled = false)
    private var validator = VideoTimestampDomainValidator(config)
    private val startupGate = AvSyncPlaybackStartGate({ config }, clock)
    private val renderPolicy = AvSynchronizedVideoRenderPolicy(
        configProvider = { config },
        modelProvider = { modelRef.get() },
    )

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    private var worker: Thread? = null
    private var syncAcquisitions = 0L
    private var syncLosses = 0L
    private var audioUnderrunInvalidations = 0L
    private var audioResetInvalidations = 0L
    private var videoResetEvents = 0L

    override fun start(config: AvSyncConfig): AvSyncResult = synchronized(stateLock) {
        if (closed) return@synchronized resultLocked(AvSyncError.Closed)
        val validation = config.validate()
        if (validation != AvSyncError.None) return@synchronized resultLocked(validation)
        this.config = config
        validator = VideoTimestampDomainValidator(config)
        renderPolicy.reset()
        startupGate.onPlaybackReset()
        syncAcquisitions = 0L
        syncLosses = 0L
        audioUnderrunInvalidations = 0L
        audioResetInvalidations = 0L
        videoResetEvents = 0L
        val state = if (config.enabled) AvSyncState.WaitingForAudio else AvSyncState.Disabled
        modelRef.set(AvSyncModel(state = state, manualAvOffsetUs = config.manualAvOffsetUs))
        snapshotRef.set(
            AvSyncSnapshot(
                state = state,
                audioMasterSource = config.audioMasterSource,
                manualAvOffsetUs = config.manualAvOffsetUs,
            ),
        )
        if (config.enabled && startWorker) startWorkerLocked()
        resultLocked(AvSyncError.None)
    }

    override fun stop(): AvSyncResult = synchronized(stateLock) {
        stopWorkerLocked()
        modelRef.set(AvSyncModel(state = AvSyncState.Disabled))
        snapshotRef.updateAndGet { it.copy(state = AvSyncState.Disabled, lastError = AvSyncError.None) }
        AvSyncResult(AvSyncError.None, snapshot())
    }

    override fun observeAudioTiming(captureTimeUs: Long, readyLocalUs: Long) {
        validator.addAudioObservation(captureTimeUs, readyLocalUs)
    }

    override fun observeVideoDecoded(presentationTimeUs: Long, readyLocalUs: Long) {
        validator.addVideoObservation(presentationTimeUs, readyLocalUs)
        startupGate.notifyVideoReady()
    }

    override fun invalidateAudioUnderrun() {
        audioUnderrunInvalidations += 1
        invalidateModel(AudioPresentationModelQuality.Discontinuous)
    }

    override fun invalidateAudioReset() {
        audioResetInvalidations += 1
        invalidateModel(AudioPresentationModelQuality.Discontinuous)
        validator.reset()
    }

    override fun notifyVideoReset() {
        videoResetEvents += 1
        validator.reset()
        renderPolicy.reset()
        invalidateModel(modelRef.get().audioPresentationQuality)
    }

    override fun renderPolicy(): VideoRenderPolicy = renderPolicy

    override fun snapshot(): AvSyncSnapshot {
        publishSnapshot(lastError = snapshotRef.get().lastError)
        return snapshotRef.get()
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            stopWorkerLocked()
            closed = true
            modelRef.set(AvSyncModel(state = AvSyncState.Closed))
            snapshotRef.updateAndGet { it.copy(state = AvSyncState.Closed, lastError = AvSyncError.Closed) }
        }
    }

    fun playbackStartGate(): AvSyncPlaybackStartGate = startupGate

    fun sampleOnceForTests() {
        sampleAudioPresentation()
    }

    private fun startWorkerLocked() {
        if (running) return
        running = true
        worker = Thread(::runLoop, "WarpnectAvSync").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopWorkerLocked() {
        val workerToStop = worker
        running = false
        worker = null
        workerToStop?.interrupt()
        if (workerToStop != null && workerToStop !== Thread.currentThread()) {
            try {
                workerToStop.join(WORKER_STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun runLoop() {
        while (running) {
            sampleAudioPresentation()
            try {
                Thread.sleep(config.syncSampleIntervalUs.usToMillisAtLeastOne())
            } catch (_: InterruptedException) {
                // Re-check running; stop interrupts the low-rate sampler.
            }
        }
    }

    private fun sampleAudioPresentation() {
        if (!config.enabled) return
        val nowNs = clock.nowNs()
        val anchor = audioAnchorSource.querySourcePresentationAnchor()
        val audioQuality = audioQuality(anchor, nowNs)
        if (!anchor.valid || anchor.error != AudioPlaybackError.None ||
            audioQuality != AudioPresentationModelQuality.Valid
        ) {
            updateModel(
                state = if (anchor.error == AudioPlaybackError.PresentationTimestampUnavailable) {
                    AvSyncState.WaitingForAudio
                } else {
                    AvSyncState.Degraded
                },
                audioQuality = audioQuality,
                anchor = anchor,
                domainSnapshot = validator.snapshot(nowNs / NANOS_PER_MICRO),
                nowNs = nowNs,
                error = if (anchor.error == AudioPlaybackError.None) {
                    AvSyncError.AudioPresentationUnavailable
                } else {
                    AvSyncError.AudioPresentationUnavailable
                },
            )
            return
        }

        observeAudioTiming(anchor.sourceCaptureTimeUs, anchor.localPresentationTimeNs / NANOS_PER_MICRO)
        val domainSnapshot = validator.snapshot(nowNs / NANOS_PER_MICRO)
        val nextState = when (domainSnapshot.quality) {
            VideoTimestampDomainQuality.SenderMonotonicCompatible -> AvSyncState.Synchronized
            VideoTimestampDomainQuality.Rejected -> AvSyncState.Degraded
            VideoTimestampDomainQuality.Stale -> AvSyncState.Degraded
            VideoTimestampDomainQuality.Calibrating -> AvSyncState.Calibrating
            VideoTimestampDomainQuality.Unknown -> AvSyncState.WaitingForVideoTimestampDomain
        }
        val error = when (domainSnapshot.quality) {
            VideoTimestampDomainQuality.SenderMonotonicCompatible -> AvSyncError.None
            VideoTimestampDomainQuality.Rejected -> AvSyncError.VideoTimestampDomainRejected
            else -> AvSyncError.VideoTimestampDomainUnverified
        }
        updateModel(nextState, audioQuality, anchor, domainSnapshot, nowNs, error)
    }

    private fun audioQuality(anchor: AudioSourcePresentationAnchorResult, nowNs: Long): AudioPresentationModelQuality {
        if (!anchor.valid || anchor.error != AudioPlaybackError.None) return AudioPresentationModelQuality.Unavailable
        if (anchor.discontinuityBefore) return AudioPresentationModelQuality.Discontinuous
        if (anchor.timestampQuality == AudioTimestampQuality.Unavailable) {
            return AudioPresentationModelQuality.Unavailable
        }
        if (anchor.timestampQuality != AudioTimestampQuality.AudioRecordTimestamp &&
            !config.allowEstimatedAudioTimestamp
        ) {
            return AudioPresentationModelQuality.Unavailable
        }
        if (anchor.localPresentationTimeNs <= 0L || nowNs < anchor.localPresentationTimeNs) {
            return AudioPresentationModelQuality.WarmingUp
        }
        if ((nowNs - anchor.localPresentationTimeNs) / NANOS_PER_MICRO > config.maxSyncModelAgeUs) {
            return AudioPresentationModelQuality.Stale
        }
        return AudioPresentationModelQuality.Valid
    }

    private fun updateModel(
        state: AvSyncState,
        audioQuality: AudioPresentationModelQuality,
        anchor: AudioSourcePresentationAnchorResult,
        domainSnapshot: VideoTimestampDomainSnapshot,
        nowNs: Long,
        error: AvSyncError,
    ) {
        val previous = modelRef.get()
        val nextState = if (state == AvSyncState.Synchronized && audioQuality == AudioPresentationModelQuality.Valid) {
            AvSyncState.Synchronized
        } else {
            state
        }
        if (previous.state != AvSyncState.Synchronized && nextState == AvSyncState.Synchronized) {
            syncAcquisitions += 1
        } else if (previous.state == AvSyncState.Synchronized && nextState != AvSyncState.Synchronized) {
            syncLosses += 1
        }
        modelRef.set(
            AvSyncModel(
                state = nextState,
                audioPresentationQuality = audioQuality,
                audioSourceTimeUs = anchor.sourceContentTimeUs,
                audioPresentationTimeNs = anchor.localPresentationTimeNs,
                audioTimestampQuality = anchor.timestampQuality,
                audioPresentationAnchorValid = anchor.valid && audioQuality == AudioPresentationModelQuality.Valid,
                videoTimestampDomainQuality = domainSnapshot.quality,
                generation = anchor.configGeneration,
                manualAvOffsetUs = config.manualAvOffsetUs,
                modelUpdatedAtNs = nowNs,
            ),
        )
        publishSnapshot(error)
    }

    private fun invalidateModel(audioQuality: AudioPresentationModelQuality) {
        val previous = modelRef.get()
        if (previous.state == AvSyncState.Synchronized) {
            syncLosses += 1
        }
        modelRef.set(
            previous.copy(
                state = AvSyncState.Degraded,
                audioPresentationQuality = audioQuality,
                audioPresentationAnchorValid = false,
                modelUpdatedAtNs = clock.nowNs(),
            ),
        )
        publishSnapshot(AvSyncError.SyncModelInvalid)
    }

    private fun publishSnapshot(lastError: AvSyncError) {
        val nowNs = clock.nowNs()
        val model = modelRef.get()
        val render = renderPolicy.snapshot()
        val gate = startupGate.snapshot()
        snapshotRef.set(
            AvSyncSnapshot(
                state = model.state,
                audioMasterSource = config.audioMasterSource,
                audioTimestampQuality = model.audioTimestampQuality,
                audioPresentationQuality = model.audioPresentationQuality,
                audioPresentationAnchorValid = model.audioPresentationAnchorValid,
                audioAnchorAgeUs = if (model.modelUpdatedAtNs > 0L && nowNs >= model.modelUpdatedAtNs) {
                    (nowNs - model.modelUpdatedAtNs) / NANOS_PER_MICRO
                } else {
                    0L
                },
                videoTimestampDomainQuality = model.videoTimestampDomainQuality,
                videoCalibrationSamples = validator.snapshot(nowNs / NANOS_PER_MICRO).videoCalibrationSamples,
                startupHoldUs = gate.startupHoldUs,
                startupHoldCapacityLimitUs = gate.startupHoldCapacityLimitUs,
                startupHoldExpired = gate.startupHoldExpired,
                currentEstimatedAvSkewUs = render.currentEstimatedAvSkewUs,
                videoFramesScheduled = render.videoFramesScheduled,
                videoFramesRenderedImmediately = render.videoFramesRenderedImmediately,
                videoScheduleClamped = render.videoScheduleClamped,
                latestVideoLateUs = render.latestVideoLateUs,
                minVideoLateUs = render.minVideoLateUs,
                maxVideoLateUs = render.maxVideoLateUs,
                latestVideoScheduleAheadUs = render.latestVideoScheduleAheadUs,
                syncAcquisitions = syncAcquisitions,
                syncLosses = syncLosses,
                audioUnderrunInvalidations = audioUnderrunInvalidations,
                audioResetInvalidations = audioResetInvalidations,
                videoResetEvents = videoResetEvents,
                manualAvOffsetUs = config.manualAvOffsetUs,
                modelAgeUs = if (model.modelUpdatedAtNs > 0L && nowNs >= model.modelUpdatedAtNs) {
                    (nowNs - model.modelUpdatedAtNs) / NANOS_PER_MICRO
                } else {
                    0L
                },
                lastError = lastError,
            ),
        )
    }

    private fun resultLocked(error: AvSyncError): AvSyncResult {
        publishSnapshot(error)
        return AvSyncResult(error, snapshotRef.get())
    }

    private fun Long.usToMillisAtLeastOne(): Long = (this / MICROS_PER_MILLI).coerceAtLeast(1L)

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
        const val MICROS_PER_MILLI = 1_000L
        const val WORKER_STOP_JOIN_TIMEOUT_MS = 100L
    }
}
