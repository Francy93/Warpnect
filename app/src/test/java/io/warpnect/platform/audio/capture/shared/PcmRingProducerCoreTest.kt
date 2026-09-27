package io.warpnect.platform.audio.capture.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingProducerCoreTest {
    private fun ring() = ByteBuffer.allocate(SharedPcmAudioRingLayout.totalBytes(2, 16)).also {
        SharedPcmAudioRingLayout.initialize(it, 2, 16)
    }

    @Test fun `real V1 write read and ACK contract`() {
        val ring = ring()
        val output = ByteArrayOutputStream()
        val ack = ByteArrayOutputStream()
        PcmRingSignalRecord.write(ack, PcmRingSignalRecord(0, 1), ByteArray(8))
        val producer =
            PcmRingProducerCore(ring, output, ByteArrayInputStream(ack.toByteArray()), { target, _ ->
                target.putInt(0, 42)
                16
            })
        producer.step()
        val notification = PcmRingSignalRecord.read(ByteArrayInputStream(output.toByteArray()), ByteArray(8))!!
        val read = SharedPcmAudioRingLayout.beginRead(ring, notification.slotIndex, notification.generation)!!
        assertEquals(42, read.payload.getInt(0))
        assertEquals(4, read.frameCount)
        assertEquals(1L, producer.counters.acks.get())
        assertTrue(SharedPcmAudioRingLayout.completeRead(ring, read.slotIndex, read.generation))
    }

    @Test fun `bounded full ring drops without expanding capacity`() {
        val ring = ring()
        val producer =
            PcmRingProducerCore(ring, ByteArrayOutputStream(), ByteArrayInputStream(byteArrayOf()), { _, _ -> 16 })
        repeat(3) { producer.step() }
        assertEquals(2, SharedPcmAudioRingLayout.occupancy(ring))
        assertEquals(1L, producer.counters.dropped.get())
        assertEquals(1, SharedPcmAudioRingLayout.overruns(ring))
    }

    @Test fun `slot reuse advances generation and rejects old signal`() {
        val ring = ring()
        val producer =
            PcmRingProducerCore(ring, ByteArrayOutputStream(), ByteArrayInputStream(byteArrayOf()), { _, _ -> 16 })
        producer.step()
        SharedPcmAudioRingLayout.beginRead(ring, 0, 1)
        SharedPcmAudioRingLayout.completeRead(ring, 0, 1)
        producer.step()
        assertEquals(2, SharedPcmAudioRingLayout.slotGeneration(ring, 0))
        assertNull(SharedPcmAudioRingLayout.beginRead(ring, 0, 1))
    }

    @Test fun `negative read releases writing slot`() {
        val ring = ring()
        val producer =
            PcmRingProducerCore(ring, ByteArrayOutputStream(), ByteArrayInputStream(byteArrayOf()), { _, _ -> -3 })
        assertTrue(runCatching { producer.step() }.isFailure)
        assertEquals(SharedPcmAudioRingLayout.SLOT_STATE_FREE, SharedPcmAudioRingLayout.slotState(ring, 0))
    }

    @Test fun `zero read publishes nothing`() {
        val ring = ring()
        val producer =
            PcmRingProducerCore(ring, ByteArrayOutputStream(), ByteArrayInputStream(byteArrayOf()), { _, _ -> 0 })
        producer.step()
        assertEquals(0L, producer.counters.notifications.get())
    }

    @Test fun `invalid ring rejected`() {
        assertTrue(
            runCatching {
                PcmRingProducerCore(
                    ByteBuffer.allocate(64),
                    ByteArrayOutputStream(),
                    ByteArrayInputStream(byteArrayOf()),
                    { _, _ -> 0 },
                )
            }.isFailure,
        )
    }
}
