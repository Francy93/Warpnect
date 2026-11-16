package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellPairingDeadlineHandoffTest {
    @Test fun passiveOwnerNeverDispatchesContext() {
        val f = Fixture()
        assertNull(f.work)
        f.owner.close()
        assertNull(f.work)
    }

    @Test fun factoryDelayDoesNotRenewOriginalDeadline() {
        val f = Fixture(delay = 5_000)
        val ticket = f.owner.request(1)!!
        assertTrue(f.owner.submitCode(ticket, "314159".toCharArray()))
        val work = f.work!!
        assertSame(ticket, work.ticket)
        assertSame(f.clock, work.clock)
        assertSame(f.scheduler, work.scheduler)
        assertEquals(61_000, work.deadlineElapsedRealtimeMs)
        assertEquals(55_000, work.deadlineElapsedRealtimeMs - work.clock.elapsedRealtimeMs())
        f.owner.close()
    }

    @Test fun alreadyExpiredDispatchCannotCreateAnotherWorkBudget() {
        val f = Fixture(delay = 60_000)
        val ticket = f.owner.request(1)!!
        f.owner.submitCode(ticket, "314159".toCharArray())
        assertEquals(61_000, f.work!!.deadlineElapsedRealtimeMs)
        assertFalse(f.work!!.deadlineElapsedRealtimeMs > f.now)
        f.owner.close()
    }

    @Test fun cancelledConsentCannotLaterDispatchWork() {
        val f = Fixture()
        val ticket = f.owner.request(1)!!
        f.owner.cancel(ticket)
        assertFalse(f.owner.submitCode(ticket, "314159".toCharArray()))
        assertNull(f.work)
    }

    @Test fun deadlineAwareLegacyEntryFailsClosed() {
        val f = Fixture()
        val code = DirectShellPairingCode.take("314159".toCharArray())!!
        code.use { assertThrows(IllegalStateException::class.java) { f.operation.start(it) } }
        assertNull(f.work)
        assertFalse(f.operation.toString().contains("314159"))
        f.owner.close()
    }

    private class Fixture(val delay: Long = 0) {
        var now = 1_000L
        val clock = DirectShellPairingClock { now }
        val scheduler = DirectShellPairingScheduler { _, _ -> DirectShellPairingTimer {} }
        var work: DirectShellPairingWorkContext? = null
        val operation = object : DeadlineAwarePairingOperation {
            override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
                this@Fixture.work = work
            }
            override fun cancel() = Unit
            override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                drained(
                    DirectShellPairingDrainReceipt(
                        DirectShellPairingExternalEffect.NONE_POSSIBLE,
                        DirectShellPairingRecovery.NONE,
                    ),
                )
            }
        }
        val owner = DirectShellPairingCoordinator(
            { pairingRequiredUiState() },
            { DirectShellPairingEnvironment(true, true, 1, 0) },
            clock,
            scheduler,
            { _, _ ->
                now += delay
                operation
            },
            { _, _, _ -> error("unexpected_commit") },
            "owner",
            { "nonce" },
        )
    }
}
