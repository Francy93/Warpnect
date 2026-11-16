package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellPairingAuthorization.Grant
import io.warpnect.debug.directshell.DirectShellPairingAuthorization.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectShellPairingAuthorizationTest {
    private val store = MemoryStore()
    private var boot = 12
    private var now = 1_000L
    private val first = Grant("57bf5937-f6bf-4ef9-8647-81d14be396b5", boot, now)
    private val second = Grant("8b97c533-668c-4903-a288-03c18bb0d69f", boot, now + 1)

    @Test
    fun duplicateDeliveryNeverRearmsAfterCompletionOrProcessRecreation() {
        val owner = gate()
        assertTrue(owner.present(first))
        assertTrue(owner.present(first))
        val lease = requireNotNull(owner.claim(first.token))
        assertNull(owner.claim(first.token))
        assertTrue(owner.finish(lease))
        assertNull(owner.claim(first.token))
        val restarted = gate()
        assertFalse(restarted.present(first))
        assertNull(restarted.claim(first.token))
        assertFalse(restarted.finish(lease))
    }

    @Test
    fun twoConcurrentDeliveriesClaimExactlyOnce() {
        val owner = gate()
        assertTrue(owner.present(first))
        val barrier = CyclicBarrier(3)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val claims = List(2) {
                executor.submit<Boolean> {
                    barrier.await(5, TimeUnit.SECONDS)
                    owner.claim(first.token) != null
                }
            }
            barrier.await(5, TimeUnit.SECONDS)
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(2, store.writes)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oldActivityAndLateCompletionCannotReplaceANewerGrant() {
        val owner = gate()
        assertTrue(owner.present(first))
        val old = requireNotNull(owner.claim(first.token))
        now++
        assertFalse(owner.present(second))
        assertTrue(owner.finish(old))
        assertTrue(owner.present(second))
        val restarted = gate()
        assertFalse(restarted.present(first))
        assertNull(restarted.claim(first.token))
        val current = requireNotNull(restarted.claim(second.token))
        assertFalse(restarted.finish(old))
        restarted.dismiss(first)
        assertTrue(restarted.finish(current))
    }

    @Test
    fun staleAfterDismissalExpiryOrRebootCannotDispatch() {
        val owner = gate()
        assertTrue(owner.present(first))
        owner.dismiss(first)
        assertNull(owner.claim(first.token))
        now++
        assertTrue(owner.present(second))
        boot++
        assertNull(gate().claim(second.token))
        boot--
        now += DirectShellPairingAuthorization.LIFETIME_MS
        assertNull(owner.claim(second.token))
        assertFalse(owner.present(second))
    }

    @Test
    fun noWorkerIsAdmittedWhenConsumptionWasNotPersisted() {
        val owner = gate()
        assertTrue(owner.present(first))
        store.rejectWrite = true
        assertThrows(IOException::class.java) { owner.claim(first.token) }
        store.rejectWrite = false
        assertNotNull(gate().claim(first.token))
        assertNull(gate().claim(first.token))
    }

    private fun gate() = DirectShellPairingAuthorization(store, { boot }, { now })

    private class MemoryStore : DirectShellPairingAuthorization.Store {
        private var record: Record? = null
        var rejectWrite = false
        var writes = 0

        override fun read(): Record? = record

        override fun write(record: Record) {
            if (rejectWrite) throw IOException("modeled_write_failed")
            this.record = record
            writes++
        }
    }
}
