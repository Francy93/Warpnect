package io.warpnect.platform.audio.capture.shared

import java.nio.ByteBuffer
import java.util.concurrent.locks.LockSupport

/** Admission is decided after capture completes; discarded input still advances its timeline. */
class SharedPcmCapturedChunkWriter @JvmOverloads constructor(
    private val capacityWaitNs: Long = 0,
    private val clockNs: () -> Long = System::nanoTime,
    private val pause: () -> Unit = { LockSupport.parkNanos(250_000L) },
) {
    private var framePosition = 0L
    private var sequence = 0L
    private var mapping: ByteBuffer? = null
    private var source: ByteBuffer? = null
    private var payloads = emptyArray<ByteBuffer>()
    private var sourceView: ByteBuffer? = null
    var capacityWaits = 0L
        private set
    var maxCapacityWaitNs = 0L
        private set

    init {
        require(capacityWaitNs in 0..5_000_000L)
    }

    fun prepare(memory: ByteBuffer, pcm: ByteBuffer) {
        payloads = Array(SharedPcmAudioRingLayout.slotCount(memory)) {
            SharedPcmAudioRingLayout.payloadSlice(memory, it)
        }
        sourceView = pcm.duplicate()
        mapping = memory
        source = pcm
    }

    fun write(
        memory: ByteBuffer,
        pcm: ByteBuffer,
        validBytes: Int,
        frameCount: Int,
        completionNs: Long,
        timestampQualityCode: Int,
    ): PcmRingSignalRecord? {
        val slot = writeSlot(memory, pcm, validBytes, frameCount, completionNs, timestampQualityCode)
        return if (slot < 0) null else PcmRingSignalRecord(slot, SharedPcmAudioRingLayout.slotGeneration(memory, slot))
    }

    fun writeSlot(
        memory: ByteBuffer,
        pcm: ByteBuffer,
        validBytes: Int,
        frameCount: Int,
        completionNs: Long,
        timestampQualityCode: Int,
    ): Int {
        require(frameCount > 0 && validBytes > 0 && validBytes <= pcm.capacity())
        require(validBytes <= SharedPcmAudioRingLayout.payloadCapacity(memory))
        if (mapping !== memory || source !== pcm) prepare(memory, pcm)
        val firstFramePosition = framePosition
        framePosition += frameCount
        if (capacityWaitNs > 0 && !SharedPcmAudioRingLayout.hasWritableSlot(memory)) {
            val started = clockNs()
            capacityWaits++
            while (!SharedPcmAudioRingLayout.hasWritableSlot(memory) &&
                clockNs() - started in 0 until capacityWaitNs && !Thread.currentThread().isInterrupted
            ) {
                pause()
            }
            maxCapacityWaitNs = maxOf(maxCapacityWaitNs, (clockNs() - started).coerceAtLeast(0L))
        }
        val slot = SharedPcmAudioRingLayout.beginWrite(memory)
        if (slot < 0) return -1
        val target = payloads[slot]
        target.clear()
        val input = requireNotNull(sourceView)
        input.clear()
        input.limit(validBytes)
        target.put(input)
        if (!SharedPcmAudioRingLayout.publish(
                memory, slot, sequence++, validBytes, frameCount, firstFramePosition,
                completionNs, timestampQualityCode, 0, completionNs,
            )
        ) {
            SharedPcmAudioRingLayout.cancelWrite(memory, slot)
            return -1
        }
        return slot
    }
}
