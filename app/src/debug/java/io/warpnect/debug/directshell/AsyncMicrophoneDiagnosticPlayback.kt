package io.warpnect.debug.directshell

import android.util.Log
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackResult
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioPlaybackState
import io.warpnect.audio.playback.AudioPresentationTimestampResult
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.playback.DecodedPcmMetadata
import io.warpnect.audio.playback.MicrophonePcmRingAdmission
import io.warpnect.platform.audio.capture.AndroidAudioThreadPriority
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One SDK owner; native ring admission does not depend on that owner's executor. */
internal class AsyncMicrophoneDiagnosticPlayback(
    private val diagnostic: AudioPlaybackController,
    private val initialization: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MicrophoneDiagnosticInit").apply { isDaemon = true }
    },
    private val configureOwner: () -> Boolean = AndroidAudioThreadPriority::applyMicrophoneReceiverPriority,
    private val nowNs: () -> Long = System::nanoTime,
) : AudioPlaybackController {
    private val closed = AtomicBoolean()
    private val epoch = AtomicLong()
    private val readyEpoch = AtomicLong()
    private val startEpoch = AtomicLong()
    private val skippedFrames = AtomicLong()
    private val ringAdmission = diagnostic as? MicrophonePcmRingAdmission
    private val slots = if (ringAdmission == null) Array(4) { PcmSlot() } else emptyArray()
    private val nextOrder = AtomicLong()
    private val writerScheduled = AtomicBoolean()
    private val dropReports = AtomicLong()
    private val enqueuedFrames = AtomicLong()
    private val clearedFrames = AtomicLong()
    private val writtenFrames = AtomicLong()
    private val queueHwm = AtomicInteger()
    private var maxQueueAgeNs = 0L
    private var maxSubmitNs = 0L
    private var slowWriteReports = 0
    private val reservedFrames = AtomicInteger()
    private data class DiscardBoundary(val epoch: Long, val nextPosition: Long)
    private val discardBoundary = AtomicReference<DiscardBoundary?>()
    private val reprimeReports = AtomicLong()

    private class PcmSlot {
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(1_920)
        val state = AtomicInteger(FREE)
        var frames = 0
        var bytes = 0
        var epoch = 0L
        var enqueuedNs = 0L
        var order = 0L
        var metadata: DecodedPcmMetadata? = null
    }

    @Volatile private var config: AudioPlaybackConfig? = null

    @Volatile private var latest = AudioPlaybackResult(AudioPlaybackError.None, AudioPlaybackSnapshot())

    init {
        initialization.execute {
            val applied = configureOwner()
            val actual = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
            log("MIC_DEBUG_ASYNC_OWNER priority_applied=$applied nice=$actual")
        }
    }

    override fun prepare(config: AudioPlaybackConfig): AudioPlaybackResult {
        if (closed.get()) return result(AudioPlaybackError.Closed)
        val owner = epoch.incrementAndGet()
        clearPending()
        readyEpoch.set(0)
        startEpoch.set(0)
        this.config = config
        latest = AudioPlaybackResult(
            AudioPlaybackError.None,
            AudioPlaybackSnapshot(state = AudioPlaybackState.Preparing, configGeneration = config.configGeneration),
        )
        initialization.execute {
            if (isCurrent(owner)) {
                val before = System.nanoTime()
                val prepared = diagnostic.prepare(config)
                if (isCurrent(owner)) latest = prepared
                log(
                    "MIC_DEBUG_ASYNC_PREPARE generation=${config.configGeneration} " +
                        "elapsed_ns=${System.nanoTime() - before}",
                )
            }
        }
        return latest
    }

    override fun start(): AudioPlaybackResult {
        if (closed.get()) return result(AudioPlaybackError.Closed)
        val owner = epoch.get()
        if (config == null) return result(AudioPlaybackError.NotPrepared)
        if (startEpoch.getAndSet(owner) != owner) {
            initialization.execute {
                if (isCurrent(owner) && latest.isSuccess) {
                    val started = diagnostic.start()
                    if (isCurrent(owner)) {
                        latest = started
                        if (started.isSuccess || started.error == AudioPlaybackError.AlreadyRunning) {
                            readyEpoch.set(
                                owner,
                            )
                        }
                    }
                    log(
                        "MIC_DEBUG_ASYNC_READY epoch=$owner error=${started.error} " +
                            "skipped_frames=${skippedFrames.get()}",
                    )
                }
            }
        }
        return result(if (readyEpoch.get() == owner) AudioPlaybackError.AlreadyRunning else AudioPlaybackError.None)
    }

    override fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        metadata: DecodedPcmMetadata,
    ): AudioPlaybackResult {
        if (closed.get()) return result(AudioPlaybackError.Closed)
        val owner = epoch.get()
        if (metadata.configGeneration != config?.configGeneration) {
            return result(
                AudioPlaybackError.ConfigGenerationMismatch,
            )
        }
        if (!latest.isSuccess && latest.error != AudioPlaybackError.PlaybackRingFull) return result(latest.error)
        if (readyEpoch.get() == epoch.get()) {
            if (ringAdmission != null) {
                enqueuedFrames.addAndGet(frameCount.toLong())
                val started = nowNs()
                val written = ringAdmission.admitMicrophonePcm(
                    buffer,
                    offset,
                    sizeBytes,
                    frameCount,
                    metadataForWrite(owner, metadata),
                )
                maxSubmitNs = maxOf(maxSubmitNs, nowNs() - started)
                if (written.isSuccess) {
                    writtenFrames.addAndGet(frameCount.toLong())
                } else {
                    dropped(frameCount, "WRITE_${written.error}", owner, metadata.firstFramePosition)
                }
                if (isCurrent(owner)) latest = written
                return written
            }
            if (frameCount !in 1..960 || sizeBytes != frameCount * 2 ||
                offset < 0 || sizeBytes > buffer.limit() - offset
            ) {
                return result(AudioPlaybackError.InvalidBufferRange)
            }
            // Four nominal 5ms blocks including in-flight work, not another playout reserve.
            if (!reserve(frameCount)) return dropped(frameCount, "CAPACITY", owner, metadata.firstFramePosition)
            val slot = slots.firstOrNull { it.state.compareAndSet(FREE, COPYING) }
            if (slot == null) {
                reservedFrames.addAndGet(-frameCount)
                return dropped(frameCount, "CAPACITY", owner, metadata.firstFramePosition)
            }
            slot.frames = frameCount
            slot.bytes = sizeBytes
            slot.epoch = owner
            slot.enqueuedNs = nowNs()
            slot.order = nextOrder.incrementAndGet()
            slot.metadata = metadata
            slot.buffer.clear()
            for (i in 0 until sizeBytes) slot.buffer.put(i, buffer.get(offset + i))
            if (!isCurrent(owner) || readyEpoch.get() != owner) {
                skippedFrames.addAndGet(frameCount.toLong())
                clearedFrames.addAndGet(frameCount.toLong())
                releaseSlot(slot)
                return result(AudioPlaybackError.ConfigGenerationMismatch)
            }
            enqueuedFrames.addAndGet(frameCount.toLong())
            slot.state.set(PENDING)
            scheduleWriter()
            return result(AudioPlaybackError.None)
        }
        // The wrapper independently offers this live PCM to the Session-owned Android input.
        // Do not retain stale speaker samples or call these skipped frames native acceptance.
        skippedFrames.addAndGet(frameCount.toLong())
        return result(latest.error)
    }

    override fun snapshot(): AudioPlaybackSnapshot = if (ringAdmission != null && isReady()) {
        diagnostic.snapshot().copy(lastError = latest.error)
    } else {
        latest.snapshot
    }

    override fun queryPresentationTimestamp(): AudioPresentationTimestampResult =
        if (ringAdmission == null && isReady()) {
            diagnostic.queryPresentationTimestamp()
        } else {
            AudioPresentationTimestampResult(AudioPlaybackError.PresentationTimestampUnavailable)
        }

    override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult =
        if (ringAdmission == null && isReady()) {
            diagnostic.querySourcePresentationAnchor()
        } else {
            AudioSourcePresentationAnchorResult(AudioPlaybackError.PresentationTimestampUnavailable)
        }

    override fun stop(): AudioPlaybackResult {
        if (closed.get()) return result(AudioPlaybackError.Closed)
        epoch.incrementAndGet()
        clearPending()
        readyEpoch.set(0)
        config = null
        latest = AudioPlaybackResult(AudioPlaybackError.None, AudioPlaybackSnapshot())
        initialization.execute { diagnostic.stop() }
        return latest
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        epoch.incrementAndGet()
        clearPending()
        readyEpoch.set(0)
        config = null
        initialization.execute {
            diagnostic.close()
            log(
                "MIC_DEBUG_ASYNC_RELEASE skipped_frames=${skippedFrames.get()} " +
                    "cleared_frames=${clearedFrames.get()} enqueued_frames=${enqueuedFrames.get()} " +
                    "written_frames=${writtenFrames.get()} hwm_frames=${queueHwm.get()} " +
                    "max_queue_age_ns=$maxQueueAgeNs max_submit_ns=$maxSubmitNs",
            )
        }
        initialization.shutdown()
    }

    private fun isCurrent(owner: Long) = !closed.get() && epoch.get() == owner
    private fun isReady() = !closed.get() && config != null && readyEpoch.get() == epoch.get()
    private fun result(error: AudioPlaybackError) = AudioPlaybackResult(error, snapshot())
    private fun dropped(frames: Int, reason: String, owner: Long, position: Long): AudioPlaybackResult {
        skippedFrames.addAndGet(frames.toLong())
        discardBoundary.updateAndGet { previous ->
            DiscardBoundary(owner, maxOf(position + frames, previous?.takeIf { it.epoch == owner }?.nextPosition ?: 0L))
        }
        if (dropReports.getAndIncrement() < 16) log("MIC_DEBUG_ASYNC_DROP reason=$reason frames=$frames")
        return result(AudioPlaybackError.PlaybackRingFull)
    }

    private fun metadataForWrite(slot: PcmSlot): DecodedPcmMetadata {
        return metadataForWrite(slot.epoch, requireNotNull(slot.metadata))
    }

    private fun metadataForWrite(owner: Long, metadata: DecodedPcmMetadata): DecodedPcmMetadata {
        val boundary = discardBoundary.get()
        if (boundary != null && boundary.epoch == owner &&
            metadata.firstFramePosition >= boundary.nextPosition &&
            metadata.frameKind == DecodedAudioFrameKind.Normal &&
            discardBoundary.compareAndSet(boundary, null)
        ) {
            if (reprimeReports.getAndIncrement() < 16) {
                log(
                    "MIC_DEBUG_ASYNC_REPRIME position=${metadata.firstFramePosition} " +
                        "after_position=${boundary.nextPosition}",
                )
            }
            return metadata.copy(discontinuityBefore = true)
        }
        return metadata
    }

    private fun clearPending() {
        for (slot in slots) {
            if (slot.state.compareAndSet(PENDING, OWNED)) {
                skippedFrames.addAndGet(slot.frames.toLong())
                clearedFrames.addAndGet(slot.frames.toLong())
                releaseSlot(slot)
            }
        }
    }

    private fun reserve(frames: Int): Boolean {
        while (true) {
            val before = reservedFrames.get()
            if (before + frames > 960) return false
            if (reservedFrames.compareAndSet(before, before + frames)) {
                queueHwm.updateAndGet { maxOf(it, before + frames) }
                return true
            }
        }
    }

    private fun releaseSlot(slot: PcmSlot) {
        for (i in 0 until slot.bytes) slot.buffer.put(i, 0.toByte())
        slot.metadata = null
        reservedFrames.addAndGet(-slot.frames)
        slot.state.set(FREE)
    }

    private fun takePending(): PcmSlot? {
        while (true) {
            var first: PcmSlot? = null
            for (slot in slots) {
                if (slot.state.get() == PENDING && (first == null || slot.order < first.order)) first = slot
            }
            val slot = first ?: return null
            if (slot.state.compareAndSet(PENDING, OWNED)) return slot
        }
    }

    private fun scheduleWriter() {
        if (writerScheduled.compareAndSet(false, true)) {
            try {
                initialization.execute(::writePending)
            } catch (stopped: java.util.concurrent.RejectedExecutionException) {
                writerScheduled.set(false)
                clearPending()
                if (!closed.get()) throw stopped
            }
        }
    }

    private fun writePending() {
        try {
            while (true) {
                val slot = takePending() ?: break
                try {
                    val age = nowNs() - slot.enqueuedNs
                    maxQueueAgeNs = maxOf(maxQueueAgeNs, age)
                    if (!isCurrent(slot.epoch)) {
                        skippedFrames.addAndGet(slot.frames.toLong())
                    } else if (age < 0L || age >= 40_000_000L) {
                        dropped(slot.frames, "STALE", slot.epoch, requireNotNull(slot.metadata).firstFramePosition)
                    } else {
                        val started = nowNs()
                        val traced = runCatching {
                            android.os.Trace.beginSection("WarpnectMicDebugWrite")
                            true
                        }.getOrDefault(false)
                        val written = try {
                            diagnostic.submitPcm(
                                slot.buffer,
                                0,
                                slot.bytes,
                                slot.frames,
                                metadataForWrite(slot),
                            )
                        } catch (failure: Exception) {
                            log("MIC_DEBUG_ASYNC_WRITE_EXCEPTION type=${failure.javaClass.simpleName}")
                            AudioPlaybackResult(
                                AudioPlaybackError.StreamDisconnected,
                                latest.snapshot.copy(state = AudioPlaybackState.Error),
                            )
                        } finally {
                            if (traced) runCatching { android.os.Trace.endSection() }
                        }
                        val ended = nowNs()
                        maxSubmitNs = maxOf(maxSubmitNs, ended - started)
                        if (ended - started >= 8_000_000L && slowWriteReports++ < 16) {
                            log(
                                "MIC_DEBUG_ASYNC_WRITE_STALL begin_ns=$started end_ns=$ended " +
                                    "queue_age_ns=$age position=${slot.metadata?.firstFramePosition} " +
                                    "reserved_frames=${reservedFrames.get()} error=${written.error}",
                            )
                        }
                        if (written.isSuccess) {
                            writtenFrames.addAndGet(slot.frames.toLong())
                        } else {
                            dropped(
                                slot.frames,
                                "WRITE_${written.error}",
                                slot.epoch,
                                requireNotNull(slot.metadata).firstFramePosition,
                            )
                        }
                        if (isCurrent(slot.epoch)) {
                            latest = written.copy(snapshot = written.snapshot.copy(lastError = written.error))
                        }
                    }
                } finally {
                    releaseSlot(slot)
                }
            }
        } finally {
            writerScheduled.set(false)
            val remaining = slots.any { it.state.get() == PENDING }
            if (remaining && !closed.get()) scheduleWriter()
        }
    }

    private companion object {
        const val FREE = 0
        const val COPYING = 1
        const val PENDING = 2
        const val OWNED = 3
    }
    private fun log(message: String) {
        runCatching { Log.i("WarpnectAudioDiagnostics", message) }
    }
}
