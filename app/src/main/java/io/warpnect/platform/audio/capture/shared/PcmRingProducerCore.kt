package io.warpnect.platform.audio.capture.shared

import io.warpnect.audio.capture.AudioTimestampQuality
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Producer over the validated V1 layout and pipe contract; no alternate PCM ring or transport. */
internal class PcmRingProducerCore(
    private val ring: ByteBuffer,
    private val notify: OutputStream,
    private val ack: InputStream,
    private val read: (ByteBuffer, Int) -> Int,
    val counters: Counters = Counters(),
) {
    private val capacity = SharedPcmAudioRingLayout.payloadCapacity(ring)
    private val discard = ByteBuffer.allocateDirect(capacity)
    private val signal = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
    private val ackScratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)
    private var sequence = 1L
    private var framePosition = 0L

    init {
        require(SharedPcmAudioRingLayout.validate(ring))
    }

    fun step() {
        drainAcks()
        val slot = SharedPcmAudioRingLayout.beginWrite(ring)
        val target = if (slot >= 0) SharedPcmAudioRingLayout.payloadSlice(ring, slot) else discard
        target.clear()
        val count = try {
            read(target, capacity)
        } catch (error: Exception) {
            if (slot >= 0) SharedPcmAudioRingLayout.cancelWrite(ring, slot)
            counters.errors.incrementAndGet()
            throw error
        }
        if (count < 0 || count > capacity || count % 4 != 0) {
            if (slot >= 0) SharedPcmAudioRingLayout.cancelWrite(ring, slot)
            counters.errors.incrementAndGet()
            error("pcm_read_invalid_$count")
        }
        val frames = count / 4
        val firstFrame = framePosition
        framePosition += frames
        counters.captured.addAndGet(frames.toLong())
        if (slot < 0) {
            counters.dropped.incrementAndGet()
            return
        }
        if (count == 0) {
            SharedPcmAudioRingLayout.cancelWrite(ring, slot)
            return
        }
        val generation = SharedPcmAudioRingLayout.slotGeneration(ring, slot)
        val now = System.nanoTime()
        check(
            SharedPcmAudioRingLayout.publish(
                ring, slot, sequence++, count, frames, firstFrame, now,
                AudioTimestampQuality.EstimatedFromReadCompletion.ordinal, 0, now,
            ),
        )
        check(PcmRingSignalRecord.write(notify, PcmRingSignalRecord(slot, generation), signal)) {
            "pcm_notify_failed"
        }
        counters.written.addAndGet(frames.toLong())
        counters.bytes.addAndGet(count.toLong())
        counters.chunks.incrementAndGet()
        counters.notifications.incrementAndGet()
    }

    fun drainAcks() {
        while (ack.available() >= PcmRingSignalRecord.WIRE_SIZE) {
            check(PcmRingSignalRecord.read(ack, ackScratch) != null) { "pcm_ack_closed" }
            counters.acks.incrementAndGet()
        }
    }

    class Counters {
        val captured = AtomicLong()
        val written = AtomicLong()
        val bytes = AtomicLong()
        val chunks = AtomicLong()
        val dropped = AtomicLong()
        val notifications = AtomicLong()
        val acks = AtomicLong()
        val errors = AtomicLong()
    }
}
