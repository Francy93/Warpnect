package io.warpnect.platform.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedServiceResolutionQueueTest {
    @Test
    fun startsOnlyOneResolutionUntilTheActiveWorkCompletes() {
        val queue = SerializedServiceResolutionQueue<String>(maxPending = 3)

        val first = requireStart(queue.enqueue("first", "one"))
        assertTrue(queue.enqueue("second", "two") is SerializedServiceResolutionQueue.EnqueueResult.Queued)
        assertEquals(2, queue.pendingCount)

        val second = requireNotNull(queue.complete(first))
        assertEquals("second", second.key)
        assertEquals("two", second.value)
        assertEquals(1, queue.pendingCount)
        assertNull(queue.complete(second))
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun cancelledActiveWorkRetainsTheFrameworkSlotUntilItsTerminalCallback() {
        val queue = SerializedServiceResolutionQueue<String>(maxPending = 3)
        val first = requireStart(queue.enqueue("first", "one"))
        assertTrue(queue.enqueue("second", "two") is SerializedServiceResolutionQueue.EnqueueResult.Queued)

        val cancellation = queue.cancel("first")
        assertTrue(cancellation is SerializedServiceResolutionQueue.Cancellation.Active)
        assertTrue(first.cancelled)
        assertTrue(queue.enqueue("third", "three") is SerializedServiceResolutionQueue.EnqueueResult.Queued)

        val second = requireNotNull(queue.complete(first))
        assertEquals("second", second.key)
        assertFalse(second.cancelled)
        assertEquals("third", requireNotNull(queue.complete(second)).key)
    }

    @Test
    fun removesQueuedWorkWithoutDisplacingTheActiveResolution() {
        val queue = SerializedServiceResolutionQueue<String>(maxPending = 3)
        val first = requireStart(queue.enqueue("first", "one"))
        assertTrue(queue.enqueue("second", "two") is SerializedServiceResolutionQueue.EnqueueResult.Queued)
        assertTrue(queue.cancel("second") is SerializedServiceResolutionQueue.Cancellation.Queued)

        assertNull(queue.complete(first))
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun cancelledGenerationCannotStartNewWorkBeforeItsTerminalCallback() {
        val queue = SerializedServiceResolutionQueue<String>(maxPending = 3)
        val oldGeneration = requireStart(queue.enqueue("old", "old-value"))
        assertTrue(queue.enqueue("old-queued", "discarded") is SerializedServiceResolutionQueue.EnqueueResult.Queued)

        assertSame(oldGeneration, queue.cancelAll())
        assertTrue(oldGeneration.cancelled)
        assertTrue(queue.enqueue("new", "new-value") is SerializedServiceResolutionQueue.EnqueueResult.Queued)

        val newGeneration = requireNotNull(queue.complete(oldGeneration))
        assertEquals("new", newGeneration.key)
        assertEquals("new-value", newGeneration.value)
    }

    @Test
    fun rejectsDuplicateAndOverCapacityWorkWithoutReplacingTheActiveGeneration() {
        val queue = SerializedServiceResolutionQueue<String>(maxPending = 2)
        val first = requireStart(queue.enqueue("first", "one"))

        assertTrue(queue.enqueue("first", "replacement") is SerializedServiceResolutionQueue.EnqueueResult.Duplicate)
        assertTrue(queue.enqueue("second", "two") is SerializedServiceResolutionQueue.EnqueueResult.Queued)
        assertTrue(queue.enqueue("third", "three") is SerializedServiceResolutionQueue.EnqueueResult.CapacityReached)
        assertTrue(queue.isActive(first))
        assertEquals("second", requireNotNull(queue.complete(first)).key)
    }

    private fun requireStart(
        result: SerializedServiceResolutionQueue.EnqueueResult<String>,
    ): SerializedServiceResolutionQueue.Work<String> {
        assertTrue(result is SerializedServiceResolutionQueue.EnqueueResult.Start)
        return (result as SerializedServiceResolutionQueue.EnqueueResult.Start).work
    }
}
