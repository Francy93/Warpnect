package io.warpnect.platform.audio.capture

import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.capture.PcmAudioSink
import io.warpnect.platform.audio.capture.shared.PcmRingSignalRecord
import io.warpnect.platform.audio.capture.shared.SharedPcmAudioRingLayout
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("NewApi")
internal class SharedPcmAudioDrain(
    private val sharedMemory: SharedMemory,
    private val notifyReadFd: ParcelFileDescriptor,
    private val ackWriteFd: ParcelFileDescriptor,
    private val sink: PcmAudioSink,
    private val onError: (AudioCaptureError) -> Unit,
    private val onRingState: (occupancy: Int, highWater: Int, overruns: Int) -> Unit,
    private val onPcmAccepted: (frameCount: Int) -> Unit = {},
) : AutoCloseable {
    @Volatile
    private var running = false

    private var mappedMemory: ByteBuffer? = null
    private var drainThread: Thread? = null
    private var notifyInput: InputStream? = null
    private var ackOutput: OutputStream? = null

    fun start(): AudioCaptureError {
        if (running) {
            return AudioCaptureError.AlreadyRunning
        }
        val mapped = try {
            sharedMemory.mapReadWrite().order(ByteOrder.nativeOrder())
        } catch (_: Exception) {
            return AudioCaptureError.SharedMemoryMappingFailed
        }
        if (!SharedPcmAudioRingLayout.validate(mapped)) {
            SharedMemory.unmap(mapped)
            return AudioCaptureError.SharedRingCorrupt
        }
        mappedMemory = mapped
        notifyInput = ParcelFileDescriptor.AutoCloseInputStream(notifyReadFd)
        ackOutput = ParcelFileDescriptor.AutoCloseOutputStream(ackWriteFd)
        running = true
        drainThread = Thread(::drainLoop, DRAIN_THREAD_NAME).apply { start() }
        return AudioCaptureError.None
    }

    override fun close() {
        running = false
        runCatching { notifyReadFd.close() }
        runCatching { ackWriteFd.close() }
        val thread = drainThread
        if (thread != null && thread != Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        runCatching { notifyInput?.close() }
        runCatching { ackOutput?.close() }
        mappedMemory?.let { buffer -> runCatching { SharedMemory.unmap(buffer) } }
        mappedMemory = null
        runCatching { sharedMemory.close() }
    }

    private fun drainLoop() {
        val input = notifyInput ?: return
        val output = ackOutput ?: return
        val ring = mappedMemory ?: return
        val readScratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
        val writeScratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
        while (running) {
            val record = PcmRingSignalRecord.read(input, readScratch) ?: return
            val slot = SharedPcmAudioRingLayout.beginRead(
                memory = ring,
                slot = record.slotIndex,
                generation = record.generation,
            )
            if (slot == null) {
                onError(AudioCaptureError.SharedRingCorrupt)
                continue
            }
            try {
                sink.onPcmChunk(
                    buffer = slot.payload,
                    offset = 0,
                    sizeBytes = slot.validBytes,
                    frameCount = slot.frameCount,
                    firstFramePosition = slot.firstFramePosition,
                    captureTimeNs = slot.captureTimeNs,
                    timestampQuality = AudioTimestampQuality.entries.getOrElse(
                        slot.timestampQualityCode,
                    ) {
                        AudioTimestampQuality.Unavailable
                    },
                )
                onPcmAccepted(slot.frameCount)
            } catch (_: RuntimeException) {
                onError(AudioCaptureError.SinkFailure)
            } finally {
                SharedPcmAudioRingLayout.completeRead(ring, slot.slotIndex, slot.generation)
                PcmRingSignalRecord.write(
                    output = output,
                    record = PcmRingSignalRecord(slot.slotIndex, slot.generation),
                    scratch = writeScratch,
                )
                onRingState(
                    SharedPcmAudioRingLayout.occupancy(ring),
                    SharedPcmAudioRingLayout.highWater(ring),
                    SharedPcmAudioRingLayout.overruns(ring),
                )
            }
        }
    }

    private companion object {
        const val DRAIN_THREAD_NAME = "WarpnectSystemAudioDrain"
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
