package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias EnvironmentChange = (DirectShellPairingEnvironment) -> DirectShellPairingEnvironment

class DirectShellPairingCoordinatorTest {
    @Test
    fun `owned store revision transition is distinct from foreign invalidation`() {
        val f = Fixture()
        f.environment = f.environment.copy(credentialRevision = 0)
        f.start()
        f.prepared()
        val flight = f.reserved()
        f.environment = f.environment.copy(credentialRevision = 1)
        flight.receipt(published().copy(publishedCredentialRevision = 1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `unnotified environment changes are revalidated at final drain`() {
        environmentChanges().forEach { (reason, change) ->
            val f = ready()
            f.reserved().receipt(published())
            f.environment = if (reason == DirectShellPairingReason.CREDENTIALS_CHANGED) {
                f.environment.copy(credentialRevision = 3)
            } else {
                change(f.environment)
            }
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
            assertNull(f.owner.request(1))
        }
    }

    @Test
    fun `unclaimed reservation cannot start work after an observed stop`() {
        listOf("cancel", "timeout", "network", "owner").forEach { stop ->
            val f = ready()
            val flight = f.flight
            assertNotNull(flight.reserve())
            when (stop) {
                "cancel" -> f.owner.cancel(flight.prepared.ticket)
                "timeout" -> f.advance(DirectShellPairingCoordinator.WORK_MS)
                "network" -> {
                    f.environment = f.environment.copy(networkEpoch = 2)
                    f.owner.environmentChanged()
                }
                "owner" -> f.owner.close()
            }
            assertFalse(flight.claim())
            flight.receipt(notPublished())
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
            assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, f.owner.state.value.publication)
            assertNull(f.owner.request(1))
        }
    }

    @Test
    fun `stale pre-PAKE callbacks cannot alter a next clean attempt`() {
        val f = Fixture()
        val oldTicket = f.start()
        val oldOperation = f.operation
        f.owner.cancel(oldTicket)
        oldOperation.drain()
        val next = f.start()
        val before = f.owner.state.value
        assertFalse(oldOperation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        val late = Authorization()
        oldOperation.observer.prepared(late.handle)
        oldOperation.observer.failed(DirectShellPairingReason.BACKEND_FAILURE)
        oldOperation.drain()
        assertSame(before, f.owner.state.value)
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), late.resolutions)
        assertEquals(2, f.operations.size)
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(next))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CANCELLED, f.phase)
    }

    @Test
    fun `different staged handle retention failure cannot fake clean drain`() {
        val f = ready()
        f.operation.observer.prepared(DirectShellStagedAuthorization { throw Exception(CODE) })
        f.owner.cancel(f.flight.prepared.ticket)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        assertEquals(1, f.operation.closes)
    }

    @Test
    fun `inconsistent publication durability receipt fails closed`() {
        listOf(
            notPublished().copy(durability = DirectShellPairingDurability.SYNC_CONFIRMED),
            published().copy(publishedCredentialRevision = null),
            published().copy(publishedCredentialRevision = 3),
            unknown().copy(publishedCredentialRevision = 2),
        ).forEach { receipt ->
            val f = ready()
            f.reserved().receipt(receipt)
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
            assertEquals(DirectShellPairingReason.RECEIPT_INVALID, f.owner.state.value.reason)
            assertNull(f.owner.request(1))
        }
    }

    @Test
    fun `reservation is not publication and claim binds exact prepared identity`() {
        val f = ready()
        val flight = f.flight
        val reservation = flight.reserve()!!
        assertEquals(DirectShellPairingPhase.COMMIT_RESERVED, f.phase)
        assertEquals(DirectShellPairingPublication.UNKNOWN, f.owner.state.value.publication)
        assertEquals(0, f.publications)
        val copy = DirectShellPairingPreparedCommit(flight.prepared.ticket, flight.prepared.authorization)
        assertFalse(reservation.claim(copy))
        assertTrue(flight.claim())
        assertFalse(flight.claim())
    }

    @Test
    fun `reservation rechecks every environment coordinate`() {
        environmentChanges().forEach { (_, change) ->
            val f = ready()
            f.environment = change(f.environment)
            assertNull(f.flight.reserve())
            assertEquals(0, f.publications)
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        }
    }

    @Test
    fun `copied ticket or mismatched expected revision cannot reserve`() {
        val f = ready()
        val prepared = f.flight.prepared
        val clone = DirectShellPairingTicket("test-owner", "test-nonce", 1, 1, 1)
        assertNull(f.flight.control.reserve(DirectShellPairingPreparedCommit(clone, prepared.authorization)))
        assertNull(
            f.flight.control.reserve(
                DirectShellPairingPreparedCommit(prepared.ticket, prepared.authorization, 2),
            ),
        )
        assertNotNull(f.flight.reserve())
    }

    @Test
    fun `duplicate reserve never creates a second publication opportunity`() {
        val f = ready()
        val flight = f.reserved()
        assertNull(flight.reserve())
        assertFalse(flight.claim())
        assertEquals(1, f.commits.size)
        assertEquals(1, f.operations.size)
    }

    @Test
    fun `cancel before reservation prevents store entry`() {
        val f = ready()
        val ticket = f.flight.prepared.ticket
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(ticket))
        assertNull(f.flight.reserve())
        assertFalse(f.publish())
        assertEquals(0, f.publications)
        assertEquals(1, f.operation.closes)
    }

    @Test
    fun `cancel after claimed reservation cannot acknowledge rollback`() {
        val f = ready()
        val flight = f.reserved()
        assertEquals(
            DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE,
            f.owner.cancel(flight.prepared.ticket),
        )
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        flight.receipt(published())
        f.operation.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `unclaimed receipt cannot declare successful publication`() {
        val f = ready()
        f.flight.reserve()
        f.flight.receipt(published())
        assertEquals(DirectShellPairingReason.RECEIPT_INVALID, f.owner.state.value.reason)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `identical receipts are inert before and after physical drain`() {
        val f = ready()
        val flight = f.reserved()
        repeat(3) { flight.receipt(published()) }
        assertEquals(1, f.operation.closes)
        f.operation.drain()
        repeat(3) { flight.receipt(published()) }
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
    }

    @Test
    fun `contradictory receipt poisons eligibility without erasing published knowledge`() {
        val f = ready()
        val flight = f.reserved()
        flight.receipt(published())
        flight.receipt(notPublished())
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `contradiction after finalization cannot create another enrollment`() {
        val f = ready()
        val flight = f.reserved()
        flight.receipt(published())
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        flight.receipt(unknown())
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(f.owner.request(1))
        assertEquals(1, f.operations.size)
    }

    @Test
    fun `unknown receipt remains unknown after truthful late drain`() {
        val f = ready()
        f.reserved().receipt(unknown())
        f.operation.drain()
        assertTrue(f.owner.state.value.drained)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertEquals(0, f.scheduler.pending)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `published but unsynced material requires reconciliation`() {
        val f = ready()
        f.reserved().receipt(published().copy(durability = DirectShellPairingDurability.UNCONFIRMED))
        f.operation.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `post-publication redundant cleanup error cannot erase the record`() {
        val f = ready()
        f.reserved().receipt(published().copy(cleanupComplete = false))
        f.operation.drain()
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `not-published store result cannot undo possible Android trust`() {
        val f = ready()
        f.reserved().receipt(notPublished())
        f.operation.drain()
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingRecovery.RETAINED_UNCONFIRMED, f.owner.state.value.recovery)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `work deadline during reserved publication cannot grant more work`() {
        val f = ready()
        val flight = f.reserved()
        f.advance(DirectShellPairingCoordinator.WORK_MS)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(flight.reserve())
        assertEquals(1, f.operation.cancels)
        flight.receipt(published())
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `delayed work timer cannot authorize reservation after expiry`() {
        val f = ready()
        f.clock.now += DirectShellPairingCoordinator.WORK_MS
        assertNull(f.flight.reserve())
        assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, f.owner.state.value.reason)
        assertEquals(0, f.publications)
    }

    @Test
    fun `environment loss after reservation cannot rebind or yield eligible success`() {
        environmentChanges().forEach { (_, change) ->
            val f = ready()
            val flight = f.reserved()
            f.environment = change(f.environment)
            f.owner.environmentChanged()
            assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
            flight.receipt(published())
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
            assertEquals(1, f.operations.size)
        }
    }

    @Test
    fun `permission loss while closing prevents final eligible success`() {
        val f = ready()
        f.reserved().receipt(published())
        f.environment = f.environment.copy(permissionsGranted = false)
        f.owner.environmentChanged()
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `cancel after receipt before drain acknowledges stored not rolled back`() {
        val f = ready()
        val flight = f.reserved()
        flight.receipt(published())
        assertEquals(DirectShellPairingStop.ALREADY_STORED_NO_ROLLBACK, f.owner.cancel(flight.prepared.ticket))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `drain may precede a receipt after an explicit stop`() {
        val f = ready()
        val flight = f.reserved()
        f.owner.cancel(flight.prepared.ticket)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        flight.receipt(published())
        assertTrue(f.owner.state.value.drained)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `positive work receipt before deadline permits cleanup after work deadline`() {
        val f = ready()
        val flight = f.reserved()
        f.advance(DirectShellPairingCoordinator.WORK_MS - 1)
        flight.receipt(published())
        f.advance(2)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
    }

    @Test
    fun `cleanup observation timeout cannot release an unknown store operation`() {
        val f = ready()
        val flight = f.reserved()
        f.owner.cancel(flight.prepared.ticket)
        f.advance(DirectShellPairingCoordinator.CLEANUP_MS)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `disposed owner cannot finalize a late reserved publication as success`() {
        val f = ready()
        val flight = f.reserved()
        repeat(3) { f.owner.close() }
        flight.receipt(published())
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertEquals(1, f.operation.closes)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `partial drain keeps sticky physical cleanup backpressure`() {
        val f = Fixture()
        f.owner.cancel(f.start())
        f.operation.drain(
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.NONE_POSSIBLE,
                DirectShellPairingRecovery.NONE,
                storeDrained = false,
            ),
        )
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        f.operation.drain()
        assertNull(f.owner.request(1))
    }

    @Test
    fun `absent prepared callback does not prove absence of daemon trust effect`() {
        val f = Fixture()
        f.start()
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.owner.state.value.externalEffect)
        f.operation.observer.failed(DirectShellPairingReason.BACKEND_FAILURE)
        f.operation.drain(
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.POSSIBLE,
                DirectShellPairingRecovery.RETAINED_UNCONFIRMED,
            ),
        )
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertTrue(f.commits.isEmpty())
    }

    @Test
    fun `PAKE success followed by preparation failure retains recovery identity`() {
        val f = Fixture()
        f.start()
        assertTrue(f.operation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        f.operation.observer.failed(DirectShellPairingReason.COMMIT_FAILED)
        f.operation.drain(
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.AUTHENTICATED,
                DirectShellPairingRecovery.RETAINED_UNCONFIRMED,
            ),
        )
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertEquals(DirectShellPairingExternalEffect.AUTHENTICATED, f.owner.state.value.externalEffect)
    }

    @Test
    fun `unknown recovery alone fences another enrollment`() {
        val f = Fixture()
        f.owner.cancel(f.start())
        f.operation.drain(
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.NONE_POSSIBLE,
                DirectShellPairingRecovery.UNKNOWN,
            ),
        )
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `prepared authorization requires pre-PAKE recovery checkpoint`() {
        val f = Fixture()
        f.start()
        f.operation.autoRecovery = false
        f.prepared()
        assertTrue(f.commits.isEmpty())
        assertEquals(DirectShellPairingReason.RECOVERY_UNCONFIRMED, f.owner.state.value.reason)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `invalid recovery checkpoint denies permission to start pairing I O`() {
        val f = Fixture()
        f.start()
        assertFalse(f.operation.observer.pairingStarted(DirectShellPairingRecovery.NONE))
        assertEquals(1, f.operation.closes)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.FAILED, f.phase)
        assertEquals(0, f.publications)
    }

    @Test
    fun `fresh process cannot erase a modeled namespace reconciliation fence`() {
        repeat(2) {
            val f = Fixture()
            f.environment = f.environment.copy(reconciliationRequired = true)
            assertNull(f.owner.request(1))
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
            assertTrue(f.operations.isEmpty())
        }
    }

    @Test
    fun `successful record also blocks a second enrollment without reconciliation`() {
        val f = ready()
        f.reserved().receipt(published())
        f.operation.drain()
        repeat(10) { assertNull(f.owner.request(1)) }
        assertEquals(1, f.operations.size)
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
    }

    @Test
    fun `new typed metadata and opaque commit handles do not render secrets`() {
        val f = ready()
        val flight = f.reserved()
        listOf(flight.prepared, flight.reservation, published(), unknown(), f.owner.state.value).forEach {
            assertFalse(it.toString().contains(CODE))
            assertFalse(it.toString().contains("test-nonce"))
            assertFalse(it.toString().contains("test-owner"))
        }
    }

    @Test
    fun `fatal VM error is not converted to a clean ordinary outcome`() {
        val f = Fixture()
        f.onStart = { throw OutOfMemoryError(CODE) }
        assertThrows(OutOfMemoryError::class.java) { f.submit(f.request()) }
        assertFalse(f.owner.state.value.toString().contains(CODE))
        assertNull(f.owner.request(1))
    }

    @Test
    fun `old reserved receipt cannot revive a different owner with matching values`() {
        val old = ready()
        val flight = old.reserved()
        old.owner.close()
        old.operation.drain()
        val fresh = Fixture()
        fresh.environment = fresh.environment.copy(reconciliationRequired = true)
        assertNull(fresh.owner.request(1))
        val prior = fresh.owner.state.value
        flight.receipt(published())
        assertSame(prior, fresh.owner.state.value)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, old.phase)
    }

    @Test
    fun `foreign reservation receipt cannot finish current authorization`() {
        val f = ready()
        val own = f.reserved()
        val other = ready().reserved()
        own.control.completed(other.reservation!!, published())
        assertEquals(DirectShellPairingPhase.COMMIT_RESERVED, f.phase)
        assertEquals(0, f.operation.closes)
    }

    @Test
    fun `modeled revision conflict leaves original publication count unchanged`() {
        val f = ready()
        f.reserved().receipt(notPublished())
        assertEquals(0, f.publications)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
    }

    @Test
    fun `reentrant reserve receipt and drain finish once without a worker queue`() {
        val f = Fixture()
        f.onStart = { op ->
            op.autoDrain = true
            op.observer.prepared(Authorization().handle)
        }
        f.onCommit = { flight ->
            assertNotNull(flight.reserve())
            assertTrue(flight.claim())
            repeat(2) { flight.receipt(published()) }
        }
        assertTrue(f.submit(f.request()))
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        assertEquals(1, f.operation.closes)
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun `publication is simulated outside owner monitor after reservation returns`() {
        val f = ready()
        assertTrue(
            f.flight.publish {
                assertFalse(Thread.holdsLock(f.owner))
                f.publications++
                true
            },
        )
        assertEquals(1, f.publications)
    }

    @Test
    fun `preparation failure before R cannot mint a later publication grant`() {
        val f = Fixture()
        f.onCommit = { throw Exception(CODE) }
        f.start()
        f.prepared()
        assertNull(f.flight.reserve())
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `late first receipt refines missing outcome without restoring expired authority`() {
        val f = ready()
        val flight = f.reserved()
        f.advance(DirectShellPairingCoordinator.WORK_MS)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.phase)
        flight.receipt(published())
        assertEquals(DirectShellPairingPublication.PUBLISHED, f.owner.state.value.publication)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `unresolved publication effect is not a definite failure`() {
        val f = Fixture()
        f.start()
        f.prepared()
        assertFalse(
            f.commits.single().publish {
                f.publications++
                throw Exception(CODE)
            },
        )
        assertEquals("COMMIT_OUTCOME_UNKNOWN", f.phase.name)
    }

    @Test
    fun `construction observation and code waiting perform no enrollment`() {
        val f = Fixture()
        repeat(10) { assertEquals(DirectShellPairingPhase.IDLE, f.owner.state.value.phase) }
        val readiness = f.readiness
        f.request()
        repeat(10) { f.owner.environmentChanged() }
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, f.phase)
        assertTrue(f.operations.isEmpty())
        assertTrue(f.commits.isEmpty())
        assertSame(readiness, f.readiness)
        assertEquals(1, f.scheduler.pending)
    }

    @Test
    fun `only current explicit pairing readiness authorizes request`() {
        PrivilegedRuntimeFailureReason.entries.forEach { reason ->
            val f = Fixture()
            f.readiness = pairingRequiredUiState(reason = reason)
            assertEquals(reason == PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED, f.owner.request(1) != null)
            assertTrue(f.operations.isEmpty())
        }
        val f = Fixture()
        assertNull(f.owner.request(2))
        assertEquals(DirectShellPairingReason.READINESS_STALE, f.owner.state.value.reason)
        f.readiness = f.readiness.copy(hostActionVisible = false)
        assertNull(f.owner.request(1))
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun `unavailable backend and occupied runtime do not issue consent`() {
        listOf(
            DirectShellPairingReason.BACKEND_UNAVAILABLE to { e: DirectShellPairingEnvironment ->
                e.copy(backendAvailable = false)
            },
            DirectShellPairingReason.RUNTIME_BUSY to { e: DirectShellPairingEnvironment ->
                e.copy(runtimeInUse = true)
            },
        ).forEach { (reason, change) ->
            val f = Fixture()
            f.environment = change(f.environment)
            assertNull(f.owner.request(1))
            assertEquals(reason, f.owner.state.value.reason)
            assertTrue(f.operations.isEmpty())
            assertEquals(0, f.scheduler.pending)
        }
    }

    @Test
    fun `duplicate request and submit do not create a second operation`() {
        val f = Fixture()
        val ticket = f.request()
        assertNull(f.owner.request(1))
        assertTrue(f.submit(ticket))
        val duplicate = CODE.toCharArray()
        assertFalse(f.owner.submitCode(ticket, duplicate))
        assertWiped(duplicate)
        assertEquals(1, f.operations.size)
        assertEquals(1, f.operation.starts)
        assertEquals(1, f.scheduler.pending)
    }

    @Test
    fun `rotation reattaches without starting or resubmitting`() {
        val f = Fixture()
        val ticket = f.request()
        val viewOne = f.owner.state
        assertTrue(f.submit(ticket))
        val viewTwo = f.owner.state
        assertSame(viewOne, viewTwo)
        assertEquals(DirectShellPairingPhase.DISCOVERING, viewTwo.value.phase)
        assertEquals(1, f.operations.size)
        assertEquals(1, f.operation.starts)
    }

    @Test
    fun `invalid codes are consumed without creating backend work`() {
        listOf("", "12345", "1234567", "12x456", "１２３４５６").forEach { code ->
            val f = Fixture()
            val input = code.toCharArray()
            assertFalse(f.owner.submitCode(f.request(), input))
            assertWiped(input)
            assertEquals(DirectShellPairingReason.INVALID_CODE, f.owner.state.value.reason)
            assertTrue(f.operations.isEmpty())
            assertEquals(0, f.scheduler.pending)
        }
    }

    @Test
    fun `permission denial at code submission creates no operation`() {
        val f = Fixture()
        f.environment = f.environment.copy(permissionsGranted = false)
        assertFalse(f.submit(f.request()))
        assertEquals(DirectShellPairingReason.PERMISSION_DENIED, f.owner.state.value.reason)
        assertTrue(f.operations.isEmpty())
    }

    @Test
    fun `consent expires at inclusive deadline`() {
        val f = Fixture()
        f.request()
        f.advance(DirectShellPairingCoordinator.CONSENT_MS)
        assertEquals(DirectShellPairingPhase.EXPIRED, f.phase)
        assertEquals(DirectShellPairingReason.CONSENT_EXPIRED, f.owner.state.value.reason)
        assertTrue(f.operations.isEmpty())
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun `delayed timer cannot extend consent across suspend`() {
        val f = Fixture()
        val ticket = f.request()
        f.clock.now += DirectShellPairingCoordinator.CONSENT_MS + 1
        assertFalse(f.submit(ticket))
        assertEquals(DirectShellPairingReason.CONSENT_EXPIRED, f.owner.state.value.reason)
        assertTrue(f.operations.isEmpty())
    }

    @Test
    fun `operation timeout keeps slot until physical drain`() {
        val f = Fixture()
        f.start()
        f.advance(DirectShellPairingCoordinator.WORK_MS)
        assertEquals(DirectShellPairingPhase.CANCELLING, f.phase)
        assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, f.owner.state.value.reason)
        assertEquals(1, f.operation.cancels)
        assertEquals(1, f.operation.closes)
        assertNull(f.owner.request(1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.EXPIRED, f.phase)
        assertNotNull(f.owner.request(1))
    }

    @Test
    fun `stage transitions do not restart total work budget`() {
        val f = Fixture()
        f.start()
        f.advance(59_000)
        f.operation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
        assertEquals(DirectShellPairingPhase.PAIRING, f.phase)
        f.advance(1_000)
        assertEquals(DirectShellPairingPhase.CANCELLING, f.phase)
        assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, f.owner.state.value.reason)
    }

    @Test
    fun `deferred commit cannot publish after work deadline even without timer`() {
        val f = Fixture()
        f.start()
        val authorization = f.prepared()
        f.clock.now += DirectShellPairingCoordinator.WORK_MS
        assertFalse(f.publish())
        assertEquals(0, f.publications)
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, f.owner.state.value.reason)
    }

    @Test
    fun `late authorization result rechecks expiry before committer`() {
        val f = Fixture()
        f.start()
        f.clock.now += DirectShellPairingCoordinator.WORK_MS
        val authorization = f.prepared()
        assertTrue(f.commits.isEmpty())
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
    }

    @Test
    fun `cleanup deadline blocks replacement but late actual drain releases slot`() {
        val f = Fixture()
        val ticket = f.start()
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(ticket))
        f.advance(DirectShellPairingCoordinator.CLEANUP_MS)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        repeat(10) { assertNull(f.owner.request(1)) }
        assertEquals(1, f.operations.size)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CANCELLED, f.phase)
        assertNotNull(f.owner.request(1))
    }

    @Test
    fun `cancel at every prepublication stage disposes once`() {
        listOf("consent", "discovery", "pairing", "commit").forEach { stage ->
            val f = Fixture()
            val ticket = f.request()
            if (stage != "consent") f.submit(ticket)
            if (stage == "pairing") f.operation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
            val authorization = if (stage == "commit") f.prepared() else null
            assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(ticket))
            assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, f.owner.cancel(ticket))
            if (stage != "consent") {
                assertEquals(1, f.operation.cancels)
                assertEquals(1, f.operation.closes)
                assertNull(f.owner.request(1))
                f.operation.drain()
                f.operation.drain()
            }
            assertEquals(
                if (stage == "commit") {
                    DirectShellPairingPhase.RECONCILIATION_REQUIRED
                } else {
                    DirectShellPairingPhase.CANCELLED
                },
                f.phase,
            )
            assertEquals(0, f.publications)
            authorization?.let { assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), it.resolutions) }
        }
    }

    @Test
    fun `success requires publication and drain and never changes readiness`() {
        val f = Fixture()
        val readiness = f.readiness
        val ticket = f.start()
        val authorization = f.prepared()
        assertEquals(DirectShellPairingPhase.COMMITTING, f.phase)
        assertTrue(f.publish())
        assertEquals(listOf(DirectShellPairingResolution.RECORD_PUBLISHED), authorization.resolutions)
        assertEquals(DirectShellPairingPhase.CLOSING, f.phase)
        assertEquals(0, f.operation.cancels)
        assertEquals(1, f.operation.closes)
        assertEquals(DirectShellPairingStop.ALREADY_STORED_NO_ROLLBACK, f.owner.cancel(ticket))
        assertNull(f.owner.request(1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.phase)
        assertSame(readiness, f.readiness)
        assertEquals(1, f.publications)
    }

    @Test
    fun `cancel before commit invalidates deferred publication permit`() {
        val f = Fixture()
        val ticket = f.start()
        val authorization = f.prepared()
        f.owner.cancel(ticket)
        assertFalse(f.publish())
        assertEquals(0, f.publications)
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
    }

    @Test
    fun `publication linearizes before cancellation`() {
        val f = Fixture()
        val ticket = f.start()
        f.prepared()
        assertTrue(f.publish())
        assertEquals(DirectShellPairingStop.ALREADY_STORED_NO_ROLLBACK, f.owner.cancel(ticket))
        assertFalse(f.publish())
        assertEquals(1, f.publications)
    }

    @Test
    fun `commit receives the original ticket and expected revisions`() {
        val f = Fixture()
        val ticket = f.start()
        f.prepared()
        assertSame(ticket, f.commitTickets.single())
        assertEquals(1L, f.commitTickets.single().networkEpoch)
        assertEquals(1L, f.commitTickets.single().credentialRevision)
    }

    @Test
    fun `success before deadline cannot be expired by old work timer`() {
        val f = Fixture()
        f.start()
        val timer = f.scheduler.tasks.last()
        f.clock.now += DirectShellPairingCoordinator.WORK_MS - 1
        f.prepared()
        assertTrue(f.publish())
        f.operation.drain()
        f.clock.now += 1
        timer.fire()
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
    }

    @Test
    fun `reentrant cancellation from start retains ownership through close`() {
        val f = Fixture()
        f.onStart = {
            assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancelHostRequest(1))
        }
        assertTrue(f.submit(f.request()))
        assertEquals(DirectShellPairingPhase.CANCELLING, f.phase)
        assertEquals(1, f.operation.closes)
        assertNull(f.owner.request(1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CANCELLED, f.phase)
    }

    @Test
    fun `failed publication retains staged identity without rollback`() {
        val f = Fixture()
        f.start()
        val authorization = f.prepared()
        assertFalse(f.publish(result = false))
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertEquals(DirectShellPairingReason.COMMIT_FAILED, f.owner.state.value.reason)
        assertEquals(1, f.operation.cancels)
    }

    @Test
    fun `late duplicate callbacks cannot affect a newer flight`() {
        val f = Fixture()
        val old = f.start()
        val oldOperation = f.operation
        val authorization = f.prepared()
        val oldPermit = f.commits.single()
        f.owner.cancel(old)
        oldOperation.drain()
        val fresh = Fixture()
        fresh.environment = fresh.environment.copy(reconciliationRequired = true)
        assertNull(fresh.owner.request(1))
        oldOperation.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
        oldOperation.observer.prepared(authorization.handle)
        oldOperation.observer.failed(DirectShellPairingReason.PAIRING_REJECTED)
        oldOperation.drain()
        assertFalse(oldPermit.publish { error("stale publication") })
        assertEquals(DirectShellPairingStop.RECONCILIATION_REQUIRED, f.owner.cancel(old))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, fresh.phase)
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `different late staged handles are discarded exactly once`() {
        val f = Fixture()
        val ticket = f.start()
        f.owner.cancel(ticket)
        val authorization = Authorization()
        repeat(3) { f.operation.observer.prepared(authorization.handle) }
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertTrue(f.commits.isEmpty())
    }

    @Test
    fun `duplicate prepared result permits only one commit`() {
        val f = Fixture()
        f.start()
        val authorization = f.prepared()
        repeat(3) { f.operation.observer.prepared(authorization.handle) }
        val other = f.prepared()
        assertEquals(1, f.commits.size)
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), other.resolutions)
        assertTrue(authorization.resolutions.isEmpty())
        assertTrue(f.publish())
        assertEquals(listOf(DirectShellPairingResolution.RECORD_PUBLISHED), authorization.resolutions)
    }

    @Test
    fun `new process rejects old ticket even with identical epoch nonce and readiness`() {
        val old = Fixture()
        val ticket = old.start()
        val fresh = Fixture()
        fresh.request()
        assertFalse(fresh.submit(ticket))
        assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, fresh.owner.cancel(ticket))
        assertTrue(fresh.operations.isEmpty())
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, fresh.phase)
    }

    @Test
    fun `passive readiness refresh does not grant or revoke enrollment consent`() {
        val f = Fixture()
        val ticket = f.start()
        f.readiness = pairingRequiredUiState(generation = 2)
        f.owner.environmentChanged()
        assertEquals(DirectShellPairingStop.STALE_OR_FINISHED, f.owner.cancelHostRequest(2))
        assertEquals(
            DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START,
            f.owner.cancelHostRequest(ticket.originReadinessGeneration),
        )
        assertEquals(DirectShellPairingPhase.CANCELLING, f.phase)
    }

    @Test
    fun `environment changes invalidate authorization before commit`() {
        environmentChanges().forEach { (reason, change) ->
            val f = Fixture()
            f.start()
            val authorization = f.prepared()
            f.environment = change(f.environment)
            assertFalse(f.publish())
            assertEquals(0, f.publications)
            assertEquals(reason, f.owner.state.value.reason)
            assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        }
    }

    @Test
    fun `environment notification cancels an in progress attempt`() {
        environmentChanges().forEach { (reason, change) ->
            val f = Fixture()
            f.start()
            f.environment = change(f.environment)
            f.owner.environmentChanged()
            assertEquals(reason, f.owner.state.value.reason)
            assertEquals(1, f.operation.cancels)
            assertEquals(1, f.operation.closes)
        }
    }

    @Test
    fun `network change before submission prevents any I O`() {
        val f = Fixture()
        val ticket = f.request()
        f.environment = f.environment.copy(networkEpoch = 2)
        assertFalse(f.submit(ticket))
        assertTrue(f.operations.isEmpty())
        assertEquals(DirectShellPairingReason.NETWORK_CHANGED, f.owner.state.value.reason)
    }

    @Test
    fun `backend rejection is distinct from cleanup`() {
        val f = Fixture()
        f.start()
        f.operation.observer.failed(DirectShellPairingReason.PAIRING_REJECTED)
        assertEquals(DirectShellPairingPhase.CANCELLING, f.phase)
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.FAILED, f.phase)
        assertEquals(DirectShellPairingReason.PAIRING_REJECTED, f.owner.state.value.reason)
        assertTrue(f.commits.isEmpty())
    }

    @Test
    fun `owner close expires consent and never permits automatic restart`() {
        val f = Fixture()
        val ticket = f.request()
        f.owner.close()
        assertEquals(DirectShellPairingPhase.CLOSED, f.phase)
        assertNull(f.owner.request(1))
        assertFalse(f.submit(ticket))
        assertTrue(f.operations.isEmpty())
    }

    @Test
    fun `owner close while pairing waits for physical close exactly once`() {
        val f = Fixture()
        f.start()
        repeat(3) { f.owner.close() }
        assertEquals(1, f.operation.cancels)
        assertEquals(1, f.operation.closes)
        assertNull(f.owner.request(1))
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CLOSED, f.phase)
    }

    @Test
    fun `fully synchronous callbacks are safe and dispose only once`() {
        val f = Fixture()
        val authorization = Authorization()
        f.onStart = { op ->
            op.autoDrain = true
            op.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
            op.observer.prepared(authorization.handle)
        }
        f.onCommit = { permit ->
            assertTrue(
                permit.publish {
                    f.publications++
                    true
                },
            )
        }
        assertTrue(f.submit(f.request()))
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.phase)
        assertEquals(listOf(DirectShellPairingResolution.RECORD_PUBLISHED), authorization.resolutions)
        assertEquals(1, f.operation.closes)
        assertEquals(0, f.scheduler.pending)
    }

    @Test
    fun `early timer delivery rearms without premature expiry`() {
        val f = Fixture()
        f.request()
        f.scheduler.tasks.last().fire()
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, f.phase)
        assertEquals(1, f.scheduler.pending)
        f.advance(DirectShellPairingCoordinator.CONSENT_MS)
        assertEquals(DirectShellPairingPhase.EXPIRED, f.phase)
    }

    @Test
    fun `cancelled timer delivered late cannot expire replacement stage or attempt`() {
        val f = Fixture()
        val ticket = f.request()
        val consentTimer = f.scheduler.tasks.last()
        f.submit(ticket)
        consentTimer.fire()
        assertEquals(DirectShellPairingPhase.DISCOVERING, f.phase)
        val workTimer = f.scheduler.tasks.last()
        f.owner.cancel(ticket)
        f.operation.drain()
        f.request()
        workTimer.fire()
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, f.phase)
        assertEquals(1, f.scheduler.pending)
    }

    @Test
    fun `backend create exception is redacted and releases inert attempt`() {
        val f = Fixture()
        f.failCreate = true
        assertFalse(f.submit(f.request()))
        assertEquals(DirectShellPairingPhase.FAILED, f.phase)
        assertEquals(DirectShellPairingReason.BACKEND_FAILURE, f.owner.state.value.reason)
        assertFalse(f.owner.state.value.toString().contains(CODE))
        assertNotNull(f.owner.request(1))
    }

    @Test
    fun `backend start exception still closes its owned handle`() {
        val f = Fixture()
        f.onStart = { throw IllegalStateException(CODE) }
        assertFalse(f.submit(f.request()))
        assertEquals(1, f.operation.closes)
        assertEquals(DirectShellPairingReason.BACKEND_FAILURE, f.owner.state.value.reason)
        assertFalse(f.owner.state.value.toString().contains(CODE))
    }

    @Test
    fun `committer exception disposes and cancels without rendering cause`() {
        val f = Fixture()
        f.start()
        f.onCommit = { throw IllegalStateException(CODE) }
        val authorization = f.prepared()
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertEquals(DirectShellPairingReason.COMMIT_FAILED, f.owner.state.value.reason)
        assertFalse(f.owner.state.value.toString().contains(CODE))
        assertEquals(1, f.operation.closes)
    }

    @Test
    fun `publication exception fails closed and cannot be retried`() {
        val f = Fixture()
        f.start()
        val authorization = f.prepared()
        assertFalse(f.commits.single().publish { throw IllegalStateException(CODE) })
        assertFalse(f.publish())
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
        assertEquals(0, f.publications)
        assertFalse(f.owner.state.value.toString().contains(CODE))
    }

    @Test
    fun `cancel and close exceptions retain backpressure instead of faking drain`() {
        listOf(true, false).forEach { cancelThrows ->
            val f = Fixture()
            val ticket = f.start()
            f.operation.cancelThrows = cancelThrows
            f.operation.closeThrows = !cancelThrows
            f.owner.cancel(ticket)
            assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
            assertTrue(f.owner.state.value.cleanupFailed)
            assertNull(f.owner.request(1))
            f.operation.drain()
            assertNull(f.owner.request(1))
            assertEquals(1, f.operation.closes)
            assertFalse(f.owner.state.value.toString().contains(CODE))
        }
    }

    @Test
    fun `staged resolution failure is sticky and does not retry disposal`() {
        val f = Fixture()
        val ticket = f.start()
        var calls = 0
        val authorization = DirectShellStagedAuthorization {
            calls++
            throw IllegalStateException(CODE)
        }
        f.operation.observer.prepared(authorization)
        f.owner.cancel(ticket)
        f.operation.drain()
        f.operation.observer.prepared(authorization)
        assertEquals(1, calls)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
    }

    @Test
    fun `successful publication with failed resolution does not pretend clean success`() {
        val f = Fixture()
        f.start()
        f.operation.observer.prepared(DirectShellStagedAuthorization { throw IllegalStateException(CODE) })
        assertTrue(f.publish())
        f.operation.drain()
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        assertEquals(1, f.publications)
    }

    @Test
    fun `secret copies input and borrowed buffers are wiped even on exceptions`() {
        val input = CODE.toCharArray()
        val code = DirectShellPairingCode.take(input)!!
        assertWiped(input)
        var borrowed = charArrayOf()
        assertThrows(IllegalStateException::class.java) {
            code.useCharacters {
                borrowed = it
                assertEquals(CODE, String(it))
                throw IllegalStateException("synthetic")
            }
        }
        assertWiped(borrowed)
        repeat(2) { code.close() }
        assertEquals(
            "pairing_code_closed",
            assertThrows(IllegalStateException::class.java) { code.useCharacters {} }.message,
        )
        assertFalse(code.toString().contains(CODE))
    }

    @Test
    fun `submit wipes borrowed code and all observable renderings are redacted`() {
        val f = Fixture()
        val ticket = f.start()
        assertWiped(f.operation.borrowed!!)
        assertEquals(
            "pairing_code_closed",
            assertThrows(IllegalStateException::class.java) { f.operation.code!!.useCharacters {} }.message,
        )
        val authorization = f.prepared()
        listOf(ticket, f.owner.state.value, authorization.handle, f.operation.code).forEach {
            assertFalse(it.toString().contains(CODE))
        }
    }

    @Test
    fun `only one live timer and operation across bounded repeated enrollments`() {
        val f = Fixture()
        repeat(100) {
            val ticket = f.start()
            assertEquals(1, f.scheduler.pending)
            f.owner.cancel(ticket)
            assertEquals(1, f.scheduler.pending)
            f.operation.drain()
            assertEquals(0, f.scheduler.pending)
        }
        assertEquals(100, f.operations.size)
        assertTrue(f.operations.all { it.starts == 1 && it.cancels == 1 && it.closes == 1 })
        assertTrue(f.scheduler.tasks.all { it.cancelled || it.fired })
    }

    @Test
    fun `checked dispatch and commit exceptions are closed typed outcomes`() {
        listOf("create", "start", "commit", "publish").forEach { stage ->
            val f = Fixture()
            when (stage) {
                "create" -> f.createFailure = Exception(CODE)
                "start" -> f.onStart = { throw Exception(CODE) }
                "commit" -> f.onCommit = { throw Exception(CODE) }
            }
            val accepted = f.submit(f.request())
            if (stage == "create" || stage == "start") {
                assertFalse(accepted)
                assertEquals(DirectShellPairingReason.BACKEND_FAILURE, f.owner.state.value.reason)
            } else {
                assertTrue(accepted)
                val authorization = f.prepared()
                if (stage == "publish") {
                    assertFalse(f.commits.single().publish { throw Exception(CODE) })
                }
                assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), authorization.resolutions)
                assertEquals(DirectShellPairingReason.COMMIT_FAILED, f.owner.state.value.reason)
            }
            if (stage != "create") {
                assertEquals(1, f.operation.closes)
                f.operation.drain()
            }
            val expected = when (stage) {
                "publish" -> DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN
                "commit" -> DirectShellPairingPhase.RECONCILIATION_REQUIRED
                else -> DirectShellPairingPhase.FAILED
            }
            assertEquals(expected, f.phase)
            assertFalse(f.owner.state.value.toString().contains(CODE))
            if (stage == "create" || stage == "start") {
                assertNotNull(f.owner.request(1))
            } else {
                assertNull(f.owner.request(1))
            }
        }
    }

    @Test
    fun `checked cancel or close exceptions keep cleanup backpressure`() {
        listOf(true, false).forEach { cancelThrows ->
            val f = Fixture()
            val ticket = f.start()
            f.operation.failure = Exception(CODE)
            f.operation.cancelThrows = cancelThrows
            f.operation.closeThrows = !cancelThrows
            assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(ticket))
            assertEquals(1, f.operation.closes)
            f.operation.drain()
            assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
            assertNull(f.owner.request(1))
            assertFalse(f.owner.state.value.toString().contains(CODE))
        }
    }

    @Test
    fun `checked staged disposal failure still closes the operation once`() {
        val f = Fixture()
        val ticket = f.start()
        var calls = 0
        f.operation.observer.prepared(
            DirectShellStagedAuthorization {
                calls++
                throw Exception(CODE)
            },
        )
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(ticket))
        assertEquals(1, f.operation.closes)
        f.operation.drain()
        assertEquals(1, calls)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.phase)
        assertNull(f.owner.request(1))
        assertFalse(f.owner.state.value.toString().contains(CODE))
    }

    private class Fixture {
        var readiness = pairingRequiredUiState()
        var environment = DirectShellPairingEnvironment(true, true, 1, 1)
        val clock = Clock()
        val scheduler = Scheduler(clock)
        val operations = mutableListOf<Operation>()
        val commits = mutableListOf<CommitFlight>()
        val commitTickets = mutableListOf<DirectShellPairingTicket>()
        var publications = 0
        var failCreate = false
        var createFailure: Exception? = null
        var onStart: (Operation) -> Unit = {}
        var onCommit: (CommitFlight) -> Unit = {}
        val owner: DirectShellPairingCoordinator = DirectShellPairingCoordinator(
            readiness = { readiness },
            environment = { environment },
            clock = clock,
            scheduler = scheduler,
            backend = { _, observer ->
                if (failCreate) throw IllegalStateException(CODE)
                createFailure?.let { throw it }
                Operation(observer, { owner.state.value.publication }, { onStart(it) }).also { operations += it }
            },
            committer = { ticket, authorization, control ->
                commitTickets += ticket
                val flight = CommitFlight(ticket, authorization, control)
                commits += flight
                onCommit(flight)
            },
            ownerEpoch = "test-owner",
            nextNonce = { "test-nonce" },
        )
        val operation get() = operations.last()
        val phase get() = owner.state.value.phase

        val flight get() = commits.last()

        fun reserved(): CommitFlight = flight.also {
            assertNotNull(it.reserve())
            assertTrue(it.claim())
        }

        fun request(): DirectShellPairingTicket = owner.request(1)!!

        fun submit(ticket: DirectShellPairingTicket): Boolean {
            val input = CODE.toCharArray()
            return owner.submitCode(ticket, input).also { assertWiped(input) }
        }

        fun start(): DirectShellPairingTicket = request().also { assertTrue(submit(it)) }

        fun prepared(): Authorization = Authorization().also { operation.observer.prepared(it.handle) }

        fun publish(result: Boolean = true): Boolean = commits.last().publish {
            publications++
            result
        }

        fun advance(ms: Long) {
            clock.now += ms
            scheduler.runDue()
        }
    }

    private class Clock : DirectShellPairingClock {
        var now = 100L
        override fun elapsedRealtimeMs(): Long = now
    }

    private class Scheduler(private val clock: Clock) : DirectShellPairingScheduler {
        val tasks = mutableListOf<Task>()
        val pending get() = tasks.count { !it.cancelled && !it.fired }
        override fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer =
            Task(deadlineMs, action).also { tasks += it }

        fun runDue() {
            tasks.filter { !it.cancelled && !it.fired && it.deadline <= clock.now }.forEach { it.fire() }
        }

        class Task(val deadline: Long, private val action: () -> Unit) : DirectShellPairingTimer {
            var cancelled = false
            var fired = false
            override fun cancel() {
                cancelled = true
            }
            fun fire() {
                fired = true
                action()
            }
        }
    }

    /** Fake publication only; no filesystem or work is executed by the coordinator. */
    private class CommitFlight(
        ticket: DirectShellPairingTicket,
        authorization: DirectShellStagedAuthorization,
        val control: DirectShellPairingCommitControl,
    ) {
        val prepared = DirectShellPairingPreparedCommit(ticket, authorization)
        var reservation: DirectShellPairingCommitReservation? = null

        fun reserve(): DirectShellPairingCommitReservation? =
            control.reserve(prepared).also { if (it != null) reservation = it }

        fun claim(): Boolean = reservation?.claim(prepared) ?: false

        fun receipt(value: DirectShellPairingStoreReceipt) = control.completed(reservation!!, value)

        fun publish(action: () -> Boolean): Boolean {
            if (reserve() == null || !claim()) return false
            val result = try {
                if (action()) published() else notPublished()
            } catch (_: Exception) {
                unknown()
            }
            receipt(result)
            return result.publication == DirectShellPairingPublication.PUBLISHED
        }
    }

    private class Operation(
        delegate: DirectShellPairingObserver,
        private val publication: () -> DirectShellPairingPublication,
        private val onStart: (Operation) -> Unit,
    ) : DirectShellPairingOperation {
        var starts = 0
        var cancels = 0
        var closes = 0
        var autoDrain = false
        var autoRecovery = true
        var authenticated = false
        var cancelThrows = false
        var closeThrows = false
        var failure: Exception = IllegalStateException(CODE)
        var code: DirectShellPairingCode? = null
        var borrowed: CharArray? = null
        private var drained: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        val observer = object : DirectShellPairingObserver {
            override fun pairingStarted(recovery: DirectShellPairingRecovery): Boolean =
                delegate.pairingStarted(recovery)

            override fun prepared(authorization: DirectShellStagedAuthorization) {
                if (autoRecovery) delegate.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
                authenticated = true
                delegate.prepared(authorization)
            }

            override fun failed(reason: DirectShellPairingReason) = delegate.failed(reason)
        }

        override fun start(code: DirectShellPairingCode) {
            starts++
            this.code = code
            code.useCharacters {
                borrowed = it
                assertEquals(CODE, String(it))
            }
            onStart(this)
        }

        override fun cancel() {
            cancels++
            if (cancelThrows) throw failure
        }

        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
            closes++
            this.drained = drained
            if (closeThrows) throw failure
            if (autoDrain) drain()
        }

        fun drain(receipt: DirectShellPairingDrainReceipt? = null) {
            drained?.invoke(
                receipt ?: DirectShellPairingDrainReceipt(
                    if (authenticated) {
                        DirectShellPairingExternalEffect.AUTHENTICATED
                    } else {
                        DirectShellPairingExternalEffect.NONE_POSSIBLE
                    },
                    when {
                        publication() == DirectShellPairingPublication.PUBLISHED -> {
                            DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT
                        }
                        authenticated -> DirectShellPairingRecovery.RETAINED_UNCONFIRMED
                        else -> DirectShellPairingRecovery.NONE
                    },
                ),
            )
        }
    }

    private class Authorization {
        val resolutions = mutableListOf<DirectShellPairingResolution>()
        val handle = DirectShellStagedAuthorization { resolutions += it }
    }

    companion object {
        private fun ready(): Fixture = Fixture().also {
            it.start()
            it.prepared()
        }

        private fun published() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.PUBLISHED,
            DirectShellPairingDurability.SYNC_CONFIRMED,
            publishedCredentialRevision = 2,
        )
        private fun notPublished() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.NOT_PUBLISHED,
            DirectShellPairingDurability.NOT_APPLICABLE,
        )
        private fun unknown() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.UNKNOWN,
            DirectShellPairingDurability.UNCONFIRMED,
        )

        private const val CODE = "924671"
        private fun assertWiped(input: CharArray) = assertTrue(input.all { it == '\u0000' })
        private fun environmentChanges(): List<Pair<DirectShellPairingReason, EnvironmentChange>> = listOf(
            DirectShellPairingReason.NETWORK_CHANGED to { it.copy(networkEpoch = 2) },
            DirectShellPairingReason.CREDENTIALS_CHANGED to { it.copy(credentialRevision = 2) },
            DirectShellPairingReason.PERMISSION_DENIED to { it.copy(permissionsGranted = false) },
            DirectShellPairingReason.BACKEND_UNAVAILABLE to { it.copy(backendAvailable = false) },
            DirectShellPairingReason.RUNTIME_BUSY to { it.copy(runtimeInUse = true) },
        )
    }
}
