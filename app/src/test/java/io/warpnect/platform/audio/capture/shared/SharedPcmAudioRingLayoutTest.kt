package io.warpnect.platform.audio.capture.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPcmAudioRingLayoutTest {
    @Test
    fun metadataAccessPreservesMappingPositionLimitAndOrder() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val memory = ByteBuffer.allocateDirect(SharedPcmAudioRingLayout.totalBytes(2, 16)).order(order)
            memory.position(3)
            memory.limit(memory.capacity() - 1)

            assertTrue(SharedPcmAudioRingLayout.initialize(memory, 2, 16))
            assertTrue(SharedPcmAudioRingLayout.validate(memory))
            assertEquals(0, SharedPcmAudioRingLayout.beginWrite(memory))
            assertEquals(1, SharedPcmAudioRingLayout.slotGeneration(memory, 0))
            assertEquals(3, memory.position())
            assertEquals(memory.capacity() - 1, memory.limit())
            assertEquals(order, memory.order())
        }
    }

    @Test
    fun calculatesBoundedMemorySize() {
        val total = SharedPcmAudioRingLayout.totalBytes(slotCount = 8, payloadCapacityBytes = 960)

        assertEquals(64 + 8 * (64 + 960), total)
    }

    @Test
    fun slotLifecyclePublishesReadsAndFrees() {
        val memory = ByteBuffer.allocateDirect(
            SharedPcmAudioRingLayout.totalBytes(slotCount = 2, payloadCapacityBytes = 16),
        )
        assertTrue(SharedPcmAudioRingLayout.initialize(memory, slotCount = 2, payloadCapacityBytes = 16))

        val slot = SharedPcmAudioRingLayout.beginWrite(memory)
        val payload = SharedPcmAudioRingLayout.payloadSlice(memory, slot)
        payload.put(0, 0x12)
        val generation = SharedPcmAudioRingLayout.slotGeneration(memory, slot)
        assertTrue(
            SharedPcmAudioRingLayout.publish(
                memory = memory,
                slot = slot,
                sequence = 1,
                validBytes = 1,
                frameCount = 1,
                firstFramePosition = 99,
                captureTimeNs = 1234,
                timestampQualityCode = 0,
                flags = 0,
                publishTimeNs = 5678,
            ),
        )

        val view = SharedPcmAudioRingLayout.beginRead(memory, slot, generation)
        assertNotNull(view)
        requireNotNull(view)
        assertEquals(1, view.validBytes)
        assertEquals(0x12.toByte(), view.payload.get(0))
        assertTrue(SharedPcmAudioRingLayout.completeRead(memory, slot, generation))
        assertEquals(SharedPcmAudioRingLayout.SLOT_STATE_FREE, SharedPcmAudioRingLayout.slotState(memory, slot))
    }

    @Test
    fun fullRingRecordsOverrunInsteadOfGrowing() {
        val memory = ByteBuffer.allocateDirect(
            SharedPcmAudioRingLayout.totalBytes(slotCount = 1, payloadCapacityBytes = 16),
        )
        SharedPcmAudioRingLayout.initialize(memory, slotCount = 1, payloadCapacityBytes = 16)
        val slot = SharedPcmAudioRingLayout.beginWrite(memory)
        SharedPcmAudioRingLayout.publish(
            memory = memory,
            slot = slot,
            sequence = 1,
            validBytes = 1,
            frameCount = 1,
            firstFramePosition = 0,
            captureTimeNs = 1,
            timestampQualityCode = 0,
            flags = 0,
            publishTimeNs = 1,
        )

        assertEquals(-1, SharedPcmAudioRingLayout.beginWrite(memory))
        assertEquals(1, SharedPcmAudioRingLayout.overruns(memory))
    }

    @Test
    fun signalRecordRoundTripsFixedSizeMessage() {
        val output = ByteArrayOutputStream()
        val scratch = ByteArray(PcmRingSignalRecord.WIRE_SIZE)

        assertTrue(PcmRingSignalRecord.write(output, PcmRingSignalRecord(3, 7), scratch))
        val decoded = PcmRingSignalRecord.read(ByteArrayInputStream(output.toByteArray()), scratch)

        assertEquals(PcmRingSignalRecord(3, 7), decoded)
    }

    @Test fun reusableSignalCodecPreservesWireIdentityAndDetectsIncompleteAck() {
        val codec = PcmRingSignalRecord.Codec()
        val output = ByteArrayOutputStream()
        assertTrue(codec.write(output, 3, 7))
        assertTrue(codec.write(output, 1, 9))
        val input = ByteArrayInputStream(output.toByteArray())
        assertEquals(PcmRingSignalRecord(3, 7), PcmRingSignalRecord.read(input))
        assertEquals(PcmRingSignalRecord(1, 9), PcmRingSignalRecord.read(input))
        assertTrue(codec.discardAck(ByteArrayInputStream(ByteArray(8))))
        org.junit.Assert.assertFalse(codec.discardAck(ByteArrayInputStream(ByteArray(7))))
    }
}
