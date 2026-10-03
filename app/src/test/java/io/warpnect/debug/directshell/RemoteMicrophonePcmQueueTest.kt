package io.warpnect.debug.directshell

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMicrophonePcmQueueTest {
    private fun packet(at: Long) = ByteBuffer.allocate(500).order(ByteOrder.LITTLE_ENDIAN)
        .putLong(1).putInt(240).putLong(at).apply { repeat(240) { putShort(123) } }.array()

    @Test fun expiredHistoryCannotRejectFreshAudioAtCapacity() {
        val queue = RemoteMicrophonePcmQueue(2)
        val old = packet(1)
        val recent = packet(35_000_001)
        val current = packet(41_000_001)
        assertTrue(queue.offer(old, 1))
        assertTrue(queue.offer(recent, 35_000_001))
        assertTrue(queue.offer(current, 41_000_001))
        assertEquals(2, queue.size)
        assertEquals(240, queue.expiredFrames)
        assertArrayEquals(ByteArray(500), old)
        assertSame(recent, queue.poll())
        assertSame(current, queue.poll())
    }

    @Test fun freshValidAudioIsNotSilentlyEvictedToHideOverflow() {
        val queue = RemoteMicrophonePcmQueue(1)
        val first = packet(1)
        assertTrue(queue.offer(first, 1))
        assertFalse(queue.offer(packet(2), 2))
        assertEquals(0, queue.expiredFrames)
        assertSame(first, queue.poll())
    }

    @Test fun exactAgeBoundaryRemainsEligible() {
        val queue = RemoteMicrophonePcmQueue(1)
        val first = packet(1)
        assertTrue(queue.offer(first, 1))
        assertFalse(queue.offer(packet(40_000_001), 40_000_001))
        assertEquals(0, queue.expiredFrames)
        assertSame(first, queue.poll())
    }

    @Test fun boundedSweepCountsEveryExpiredFrameAndPreservesOrder() {
        val queue = RemoteMicrophonePcmQueue(2)
        assertTrue(queue.offer(packet(1), 1))
        assertTrue(queue.offer(packet(2), 2))
        val current = packet(41_000_001)
        assertTrue(queue.offer(current, 41_000_001))
        assertEquals(480, queue.expiredFrames)
        assertEquals(1, queue.size)
        assertSame(current, queue.poll())
    }
}
