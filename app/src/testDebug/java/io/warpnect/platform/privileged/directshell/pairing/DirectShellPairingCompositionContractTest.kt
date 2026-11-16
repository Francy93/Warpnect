package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract discriminator only. Port callbacks and drain facts are modeled, not TLS/storage execution. */
class DirectShellPairingCompositionContractTest {
    @Test
    fun conservativeTransportEffectCannotAcknowledgePreparedP1Drain() {
        val f = Boundary()
        try {
            f.prepareAndCancel()
            f.deliverDrain(DirectShellPairingExternalEffect.POSSIBLE)
            assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.owner.state.value.phase)
            assertTrue(f.owner.state.value.cleanupFailed)
            assertFalse(f.owner.state.value.drained)
            assertNull(f.owner.request(1))
        } finally {
            f.owner.close()
        }
    }

    @Test
    fun authenticatedEffectIsOnlyAModeledControlNotPeerTrustEvidence() {
        val f = Boundary()
        try {
            f.prepareAndCancel()
            // This input explains the guard; it is not an admissible upgrade of a transport receipt.
            f.deliverDrain(DirectShellPairingExternalEffect.AUTHENTICATED)
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.owner.state.value.phase)
            assertFalse(f.owner.state.value.cleanupFailed)
            assertTrue(f.owner.state.value.drained)
            assertNull(f.owner.request(1))
        } finally {
            f.owner.close()
        }
    }

    private class Boundary {
        private lateinit var observer: DirectShellPairingObserver
        private var drain: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        private var work: DirectShellPairingWorkContext? = null
        private var commits = 0
        private var resolutions = 0
        private val clock = DirectShellPairingClock { 1_000L }
        private val scheduler = DirectShellPairingScheduler { _, _ -> DirectShellPairingTimer {} }
        private val operation = object : DeadlineAwarePairingOperation {
            override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
                this@Boundary.work = work
            }
            override fun cancel() = Unit
            override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                check(drain == null)
                drain = drained
            }
        }
        val owner = DirectShellPairingCoordinator(
            { pairingRequiredUiState() },
            { DirectShellPairingEnvironment(true, true, 1, 0) },
            clock,
            scheduler,
            { _, observed ->
                observer = observed
                operation
            },
            { _, _, _ -> commits++ },
            "composition-contract-owner",
            { "composition-contract-attempt" },
        )

        fun prepareAndCancel() {
            val ticket = requireNotNull(owner.request(1))
            assertTrue(owner.submitCode(ticket, "314159".toCharArray()))
            assertSame(ticket, work!!.ticket)
            assertSame(clock, work!!.clock)
            assertEquals(61_000L, work!!.deadlineElapsedRealtimeMs)
            assertTrue(observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
            // Deliberate model stimulus: no claim of authenticated protocol, disk persistence or authority.
            observer.prepared(DirectShellStagedAuthorization { resolutions++ })
            assertEquals(DirectShellPairingPhase.COMMITTING, owner.state.value.phase)
            assertEquals(DirectShellPairingExternalEffect.AUTHENTICATED, owner.state.value.externalEffect)
            assertEquals(1, commits)
            assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, owner.cancel(ticket))
            assertEquals(1, resolutions)
        }

        fun deliverDrain(effect: DirectShellPairingExternalEffect) {
            requireNotNull(drain)(
                DirectShellPairingDrainReceipt(
                    effect,
                    DirectShellPairingRecovery.RETAINED_UNCONFIRMED,
                    transportDrained = true,
                    storeDrained = true,
                    codeCopiesReleased = true,
                ),
            )
            assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, owner.state.value.publication)
            assertEquals(1, resolutions)
        }
    }
}
