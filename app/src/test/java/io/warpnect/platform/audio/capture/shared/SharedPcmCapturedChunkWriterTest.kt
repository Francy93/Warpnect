package io.warpnect.platform.audio.capture.shared

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SharedPcmCapturedChunkWriterTest {
    @Test fun reusedPayloadViewsNeverWriteIntoAReleasedGenerationMapping() {
        val writer = SharedPcmCapturedChunkWriter()
        val oldMemory = ring()
        val freshMemory = ring()
        val pcm = ByteBuffer.allocateDirect(960).apply { put(0, 42) }
        writer.prepare(oldMemory, pcm)
        requireNotNull(writer.write(oldMemory, pcm, 960, 240, 1L, 0))
        pcm.put(0, 21)
        val fresh = requireNotNull(writer.write(freshMemory, pcm, 480, 120, 2L, 0))
        val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(freshMemory, fresh.slotIndex, fresh.generation))
        assertEquals(21.toByte(), view.payload.get(0))
        assertEquals(42.toByte(), SharedPcmAudioRingLayout.payloadSlice(oldMemory, 0).get(0))
        assertEquals(480, view.validBytes)
        assertEquals(240L, view.firstFramePosition)
    }

    @Test
    fun droppedCaptureDoesNotCompressTheSourceTimeline() {
        val memory = ring()
        val writer = SharedPcmCapturedChunkWriter()
        val pcm = ByteBuffer.allocateDirect(960)
        val first = requireNotNull(writer.write(memory, pcm, 960, 240, 1L, 0))
        assertNull(writer.write(memory, pcm, 960, 240, 2L, 0))
        SharedPcmAudioRingLayout.beginRead(memory, first.slotIndex, first.generation)
        SharedPcmAudioRingLayout.completeRead(memory, first.slotIndex, first.generation)
        val fresh = requireNotNull(writer.write(memory, pcm, 960, 240, 3L, 0))
        val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(memory, fresh.slotIndex, fresh.generation))
        assertEquals(480L, view.firstFramePosition)
        assertEquals(3L, view.captureTimeNs)
        assertEquals(1, SharedPcmAudioRingLayout.overruns(memory))
    }

    @Test
    fun slotFreedDuringBlockingReadCanAcceptTheCompletedChunk() {
        val memory = ring()
        val writer = SharedPcmCapturedChunkWriter()
        val pcm = ByteBuffer.allocateDirect(960).apply {
            put(0, 42)
            position(7)
        }
        val old = requireNotNull(writer.write(memory, pcm, 960, 240, 1L, 0))
        // Consumer finishes while AudioRecord is waiting for its next physical block.
        SharedPcmAudioRingLayout.beginRead(memory, old.slotIndex, old.generation)
        SharedPcmAudioRingLayout.completeRead(memory, old.slotIndex, old.generation)
        val fresh = writer.write(memory, pcm, 480, 120, 2L, 0)
        assertNotNull(fresh)
        fresh!!
        val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(memory, fresh.slotIndex, fresh.generation))
        assertEquals(240L, view.firstFramePosition)
        assertEquals(120, view.frameCount)
        assertEquals(480, view.validBytes)
        assertEquals(42.toByte(), view.payload.get(0))
        assertEquals(7, pcm.position())
        assertEquals(0, SharedPcmAudioRingLayout.overruns(memory))
    }

    @Test fun boundedAdmissionRetainsCaptureTimeAndCountsNoDropWhenConsumerFreesSlot() {
        val memory = ring()
        val pcm = ByteBuffer.allocateDirect(960)
        val old = requireNotNull(SharedPcmCapturedChunkWriter().write(memory, pcm, 960, 240, 1L, 0))
        var now = 1L
        val writer = SharedPcmCapturedChunkWriter(5_000_000L, { now }) {
            now += 250_000L
            SharedPcmAudioRingLayout.completeRead(memory, old.slotIndex, old.generation)
        }
        val fresh = requireNotNull(writer.write(memory, pcm, 960, 240, 2L, 0))
        val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(memory, fresh.slotIndex, fresh.generation))
        assertEquals(2L, view.captureTimeNs)
        assertEquals(1L, writer.capacityWaits)
        assertEquals(250_000L, writer.maxCapacityWaitNs)
        assertEquals(0, SharedPcmAudioRingLayout.overruns(memory))
    }

    @Test fun admissionDeadlineDropsOnceAndDoesNotCompressNextCapturePosition() {
        val memory = ring()
        val pcm = ByteBuffer.allocateDirect(960)
        var now = 1L
        val writer = SharedPcmCapturedChunkWriter(5_000_000L, { now }) { now += 250_000L }
        val old = requireNotNull(writer.write(memory, pcm, 960, 240, 1L, 0))
        assertNull(writer.write(memory, pcm, 960, 240, 2L, 0))
        assertEquals(5_000_000L, writer.maxCapacityWaitNs)
        assertEquals(1, SharedPcmAudioRingLayout.overruns(memory))
        SharedPcmAudioRingLayout.completeRead(memory, old.slotIndex, old.generation)
        val fresh = requireNotNull(writer.write(memory, pcm, 960, 240, 3L, 0))
        val view = requireNotNull(SharedPcmAudioRingLayout.beginRead(memory, fresh.slotIndex, fresh.generation))
        assertEquals(480L, view.firstFramePosition)
    }

    private fun ring() = ByteBuffer.allocateDirect(SharedPcmAudioRingLayout.totalBytes(1, 960)).also {
        SharedPcmAudioRingLayout.initialize(it, 1, 960)
    }
}
