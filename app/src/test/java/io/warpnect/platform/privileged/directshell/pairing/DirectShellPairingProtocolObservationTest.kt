package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.pairingRequiredUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real P1 with controlled ports. Publication and D inputs are models, not filesystem/TLS/OS evidence. */
class DirectShellPairingProtocolObservationTest {
    @Test fun passiveConstructionHasNoOperationOrAuthority() = Fixture().use { f ->
        assertEquals(0, f.starts)
        assertEquals(0, f.factories)
        assertTrue(f.flights.isEmpty())
        assertEquals(DirectShellPairingProtocolVerification.UNCONFIRMED, f.state.protocolVerification)
    }

    @Test fun explicitPreparedRecordsVerificationWithoutGrantingReservation() = Fixture().use { f ->
        f.prepare()
        assertEquals(DirectShellPairingPhase.COMMITTING, f.state.phase)
        f.assertKnowledge()
        assertEquals(1, f.flights.size)
        assertEquals(DirectShellPairingPublication.NOT_PUBLISHED, f.state.publication)
        assertFalse(f.state.drained)
        assertSame(f.ticket, f.work!!.ticket)
        assertEquals(61_000L, f.work!!.deadlineElapsedRealtimeMs)
    }

    @Test fun foreignPreparedTicketCannotCommit() = Fixture().use { f ->
        f.start()
        f.prepare(observation = f.observation().copy(ticket = f.cloneTicket()))
        assertTrue(f.flights.isEmpty())
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), f.resolutions)
        assertNull(f.owner.request(1))
    }

    @Test fun unconfirmedPreparedCannotCommit() = Fixture().use { f ->
        f.prepare(observation = f.observation(verification = DirectShellPairingProtocolVerification.UNCONFIRMED))
        assertTrue(f.flights.isEmpty())
        assertNull(f.owner.request(1))
    }

    @Test fun verifiedWithoutPossibleExposureIsRejected() = Fixture().use { f ->
        f.prepare(observation = f.observation(effect = DirectShellPairingExternalEffect.NONE_POSSIBLE))
        assertTrue(f.flights.isEmpty())
    }

    @Test fun authenticatedEffectCannotBeImportedIntoExplicitPath() = Fixture().use { f ->
        f.prepare(observation = f.observation(effect = DirectShellPairingExternalEffect.AUTHENTICATED))
        assertTrue(f.flights.isEmpty())
        assertFalse(f.state.externalEffect == DirectShellPairingExternalEffect.AUTHENTICATED)
    }

    @Test fun observedPreparedStillRequiresPriorRecovery() = Fixture().use { f ->
        f.prepare(recovery = false)
        assertTrue(f.flights.isEmpty())
        assertEquals(DirectShellPairingReason.RECOVERY_UNCONFIRMED, f.state.reason)
    }

    @Test fun absentOptionalObserverDoesNotFallBackToLegacy() {
        var legacyCalls = 0
        val legacy: DirectShellPairingObserver = object : DirectShellPairingObserver {
            override fun pairingStarted(recovery: DirectShellPairingRecovery) = false
            override fun prepared(authorization: DirectShellStagedAuthorization) {
                legacyCalls++
            }
            override fun failed(reason: DirectShellPairingReason) = Unit
        }
        // Modeled adapter capability admission; no adapter/network implementation is implied.
        assertNull(legacy as? ObservedDirectShellPairingObserver)
        assertEquals(0, legacyCalls)
        Fixture().use { f ->
            f.start()
            assertNotNull(f.observer as? ObservedDirectShellPairingObserver)
        }
    }

    @Test fun equalPreparedObservationAllocationsAreInertDuplicates() = Fixture().use { f ->
        f.prepare()
        repeat(3) { f.observed.prepared(f.authorization, f.observation().copy()) }
        assertEquals(1, f.flights.size)
        assertTrue(f.resolutions.isEmpty())
    }

    @Test fun equalDrainObservationAllocationsAreInertDuplicates() = Fixture().use { f ->
        f.complete()
        val receipt = f.receipt()
        f.deliver(receipt)
        val before = f.state
        repeat(3) { f.deliver(receipt.copy(protocolObservation = receipt.protocolObservation!!.copy())) }
        assertEquals(before, f.state)
        assertEquals(1, f.closes)
    }

    @Test fun observedThenLegacyPreparedIsAConflictNotAnotherCommit() = Fixture().use { f ->
        f.prepare()
        f.observer.prepared(f.authorization)
        assertEquals(1, f.flights.size)
        assertNull(f.flight.control.reserve(f.flight.prepared))
        f.deliver(f.receipt(recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
        f.assertKnowledge()
    }

    @Test fun legacyThenObservedCannotBypassLegacyAuthenticatedDrainGuard() = Fixture().use { f ->
        f.start()
        assertTrue(f.observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        f.observer.prepared(f.authorization)
        f.observed.prepared(f.authorization, f.observation())
        f.deliver(f.receipt())
        assertFalse(f.state.drained)
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.state.phase)
        assertEquals(DirectShellPairingExternalEffect.AUTHENTICATED, f.state.externalEffect)
        assertEquals(1, f.flights.size)
    }

    @Test fun conflictingPreparedObservationRetainsKnownVerification() = Fixture().use { f ->
        f.prepare()
        f.observed.prepared(
            f.authorization,
            f.observation(verification = DirectShellPairingProtocolVerification.UNCONFIRMED),
        )
        f.assertKnowledge()
        assertEquals(1, f.flights.size)
        assertNull(f.flight.control.reserve(f.flight.prepared))
    }

    @Test fun differentStagedHandleIsRetainedAndCannotDispatchAgain() = Fixture().use { f ->
        f.prepare()
        var retained = 0
        f.observed.prepared(DirectShellStagedAuthorization { retained++ }, f.observation())
        assertEquals(1, retained)
        assertEquals(1, f.flights.size)
    }

    @Test fun completeModeledLocalWorkflowKeepsPossibleSeparateFromVerified() = Fixture().use { f ->
        f.complete()
        assertFalse(f.state.drained)
        f.deliver(f.receipt())
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
        assertTrue(f.state.drained)
        f.assertKnowledge()
        assertNull(f.owner.request(1))
        assertEquals(listOf(DirectShellPairingResolution.RECORD_PUBLISHED), f.resolutions)
    }

    @Test fun missingSemanticEvidenceDoesNotTurnKnownPhysicalDrainIntoUnknownPublication() = Fixture().use { f ->
        f.complete()
        f.deliver(f.receipt().copy(protocolObservation = null))
        f.assertSemanticFence()
    }

    @Test fun regressedProtocolKnowledgeCannotEraseVerificationOrCreateSuccess() = Fixture().use { f ->
        f.complete()
        f.deliver(
            f.receipt().copy(protocolObservation = f.observation(DirectShellPairingProtocolVerification.UNCONFIRMED)),
        )
        f.assertSemanticFence()
    }

    @Test fun inconsistentOuterEffectCannotErasePossibleExposure() = Fixture().use { f ->
        f.complete()
        f.deliver(f.receipt().copy(externalEffect = DirectShellPairingExternalEffect.NONE_POSSIBLE))
        f.assertSemanticFence()
    }

    @Test fun authenticatedExplicitDrainIsSemanticConflictNotTrustUpgrade() = Fixture().use { f ->
        f.complete()
        f.deliver(
            f.receipt().copy(
                externalEffect = DirectShellPairingExternalEffect.AUTHENTICATED,
                protocolObservation = f.observation(effect = DirectShellPairingExternalEffect.AUTHENTICATED),
            ),
        )
        f.assertSemanticFence()
    }

    @Test fun foreignDrainCannotProveCurrentResourceRelease() = Fixture().use { f ->
        f.complete()
        f.deliver(f.receipt().copy(protocolObservation = f.observation().copy(ticket = f.cloneTicket())))
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.state.phase)
        assertFalse(f.state.drained)
        assertNull(f.owner.request(1))
    }

    @Test fun eachUnprovenResourceDomainKeepsOwnershipOccupied() {
        repeat(3) { domain ->
            Fixture().use { f ->
                f.complete()
                f.deliver(
                    f.receipt().copy(
                        transportDrained = domain != 0,
                        storeDrained = domain != 1,
                        codeCopiesReleased = domain != 2,
                    ),
                )
                assertFalse(f.state.drained)
                assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.state.phase)
                assertNull(f.owner.request(1))
            }
        }
    }

    @Test fun unknownPublicationIsIndependentOfProvenDrain() = Fixture().use { f ->
        f.prepare()
        f.reserve()
        f.flight.control.completed(
            f.reservation!!,
            DirectShellPairingStoreReceipt(
                DirectShellPairingPublication.UNKNOWN,
                DirectShellPairingDurability.UNCONFIRMED,
            ),
        )
        f.deliver(f.receipt(recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        assertTrue(f.state.drained)
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.state.phase)
        f.assertKnowledge()
    }

    @Test fun unconfirmedSynchronizationDoesNotMakeKnownPublicationUnknown() = Fixture().use { f ->
        f.complete(f.published().copy(durability = DirectShellPairingDurability.UNCONFIRMED))
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun persistentCleanupFailureDoesNotImplyAStillLiveWorker() = Fixture().use { f ->
        f.complete(f.published().copy(cleanupComplete = false))
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun cancelBeforePreparedCannotResurrectPublicationAuthority() = Fixture().use { f ->
        f.start()
        f.owner.cancel(f.ticket)
        f.observed.prepared(f.authorization, f.observation())
        assertTrue(f.flights.isEmpty())
        f.deliver(f.receipt(recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
        f.assertKnowledge()
    }

    @Test fun cancelAfterPreparedPreventsReservation() = Fixture().use { f ->
        f.prepare()
        assertEquals(DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START, f.owner.cancel(f.ticket))
        assertNull(f.flight.control.reserve(f.flight.prepared))
        assertEquals(listOf(DirectShellPairingResolution.RETAIN_UNCONFIRMED), f.resolutions)
    }

    @Test fun cancelAfterReservationPreservesLatePublication() = Fixture().use { f ->
        f.prepare()
        f.reserve()
        assertEquals(DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE, f.owner.cancel(f.ticket))
        f.flight.control.completed(f.reservation!!, f.published())
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun cancelAfterPublicationBeforeDrainRequiresReconciliation() = Fixture().use { f ->
        f.complete()
        f.owner.cancel(f.ticket)
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun partiallyConsumedOriginalDeadlineIsNotRenewedByObservedPrepared() = Fixture().use { f ->
        f.start()
        f.now = 60_500
        f.prepare()
        assertEquals(61_000L, f.work!!.deadlineElapsedRealtimeMs)
        f.now = 61_000
        assertNull(f.flight.control.reserve(f.flight.prepared))
        assertEquals(DirectShellPairingReason.OPERATION_EXPIRED, f.state.reason)
    }

    @Test fun expiryBeforeObservedPreparedPreventsCommitButRetainsKnowledge() = Fixture().use { f ->
        f.start()
        f.now = 61_000
        f.prepare()
        assertTrue(f.flights.isEmpty())
        f.assertKnowledge()
    }

    @Test fun cleanupObservationTimeoutIsNotPhysicalDrain() = Fixture().use { f ->
        f.complete()
        f.now += 3_000
        f.scheduler.fireLast()
        assertEquals(DirectShellPairingPhase.CLEANUP_INCOMPLETE, f.state.phase)
        assertFalse(f.state.drained)
        assertNull(f.owner.request(1))
        f.deliver(f.receipt())
        assertTrue(f.state.drained)
    }

    @Test fun workCompletedInTimeIsNotExpiredOnlyBecauseDrainArrivesLater() = Fixture().use { f ->
        f.start()
        f.now = 60_500
        f.complete()
        f.now = 61_500
        f.deliver(f.receipt())
        assertEquals(DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY, f.state.phase)
    }

    @Test fun environmentInvalidationBeforeReservationBlocksObservedPath() {
        for (change in changes()) Fixture().use { f ->
            f.prepare()
            f.environment = change(f.environment)
            assertNull(f.flight.control.reserve(f.flight.prepared))
            assertNull(f.owner.request(1))
        }
    }

    @Test fun unnotifiedInvalidationAfterPublicationIsCheckedAtDrain() {
        for (change in changes()) Fixture().use { f ->
            f.complete()
            f.environment = change(f.environment)
            f.deliver(f.receipt())
            f.assertSemanticFence()
        }
    }

    @Test fun ownerDisposalAfterPublicationCannotFinishEligible() = Fixture().use { f ->
        f.complete()
        f.owner.close()
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun drainBeforeLateStoreReceiptDoesNotInferPublicationOutcome() = Fixture().use { f ->
        f.prepare()
        f.reserve()
        f.owner.cancel(f.ticket)
        f.deliver(f.receipt())
        assertEquals(DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN, f.state.phase)
        f.flight.control.completed(f.reservation!!, f.published())
        f.assertSemanticFence()
    }

    @Test fun contradictoryLateObservedReceiptFencesSameRetainedAttempt() = Fixture().use { f ->
        f.complete()
        f.deliver(f.receipt())
        f.deliver(
            f.receipt().copy(protocolObservation = f.observation(DirectShellPairingProtocolVerification.UNCONFIRMED)),
        )
        f.assertSemanticFence()
        f.deliver(f.receipt())
        f.assertSemanticFence()
    }

    @Test fun staleObservedCallbacksCannotMutateNewCleanAttempt() = Fixture().use { f ->
        f.start()
        val oldTicket = f.ticket
        val oldObserver = f.observed
        f.owner.cancel(oldTicket)
        val oldDrain = f.drain!!
        oldDrain(
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.NONE_POSSIBLE,
                DirectShellPairingRecovery.NONE,
            ),
        )
        val next = f.owner.request(1)!!
        val before = f.state
        var retained = 0
        oldObserver.prepared(DirectShellStagedAuthorization { retained++ }, f.observation().copy(ticket = oldTicket))
        oldDrain(f.receipt().copy(protocolObservation = f.observation().copy(ticket = oldTicket)))
        assertEquals(before, f.state)
        assertEquals(1, retained)
        assertFalse(next === oldTicket)
        assertTrue(f.flights.isEmpty())
    }

    @Test fun resolvedAuthorizationCannotLeaveALiveObservedAttemptWaitingForCommit() = Fixture().use { f ->
        f.start()
        f.authorization.resolve(DirectShellPairingResolution.RETAIN_UNCONFIRMED)
        f.prepare()
        assertTrue(f.flights.isEmpty())
        assertEquals(1, f.closes)
        f.deliver(f.receipt(recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED))
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
    }

    @Test fun invalidVerifiedNoneObservationCannotLaterErasePossibleExposure() = Fixture().use { f ->
        f.prepare(observation = f.observation(effect = DirectShellPairingExternalEffect.NONE_POSSIBLE))
        f.deliver(
            f.receipt(recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED).copy(
                externalEffect = DirectShellPairingExternalEffect.NONE_POSSIBLE,
                protocolObservation = f.observation(
                    DirectShellPairingProtocolVerification.UNCONFIRMED,
                    DirectShellPairingExternalEffect.NONE_POSSIBLE,
                ),
            ),
        )
        assertEquals(DirectShellPairingExternalEffect.POSSIBLE, f.state.externalEffect)
        assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, f.state.phase)
    }

    @Test fun safeMetadataRenderingDoesNotExposeTicketOrCode() = Fixture().use { f ->
        f.prepare()
        val rendered = listOf(f.observation(), f.receipt(), f.state, f.ticket, f.work).joinToString()
        for (secret in listOf("314159", "private-owner", "private-nonce")) assertFalse(rendered.contains(secret))
    }

    private class Fixture : AutoCloseable {
        var now = 1_000L
        var environment = DirectShellPairingEnvironment(true, true, 1, 0)
        val scheduler = Scheduler()
        var starts = 0
        var factories = 0
        var closes = 0
        lateinit var ticket: DirectShellPairingTicket
        lateinit var observer: DirectShellPairingObserver
        val observed get() = observer as ObservedDirectShellPairingObserver
        var work: DirectShellPairingWorkContext? = null
        var drain: ((DirectShellPairingDrainReceipt) -> Unit)? = null
        val flights = mutableListOf<Flight>()
        val flight get() = flights.single()
        var reservation: DirectShellPairingCommitReservation? = null
        val resolutions = mutableListOf<DirectShellPairingResolution>()
        val authorization = DirectShellStagedAuthorization { resolutions += it }
        val owner = DirectShellPairingCoordinator(
            { pairingRequiredUiState() },
            { environment },
            { now },
            scheduler,
            { _, value ->
                factories++
                observer = value
                object : DeadlineAwarePairingOperation {
                    override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
                        starts++
                        this@Fixture.work = work
                    }
                    override fun cancel() = Unit
                    override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
                        closes++
                        drain = drained
                    }
                }
            },
            { ticket, authorization, control ->
                flights += Flight(DirectShellPairingPreparedCommit(ticket, authorization), control)
            },
            "private-owner",
            { "private-nonce" },
        )
        val state get() = owner.state.value
        fun start() {
            if (::ticket.isInitialized) return
            ticket = owner.request(1)!!
            assertTrue(owner.submitCode(ticket, "314159".toCharArray()))
        }
        fun observation(
            verification: DirectShellPairingProtocolVerification =
                DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED,
            effect: DirectShellPairingExternalEffect = DirectShellPairingExternalEffect.POSSIBLE,
        ): DirectShellPairingProtocolObservation {
            start()
            return DirectShellPairingProtocolObservation(ticket, verification, effect)
        }
        fun cloneTicket() = DirectShellPairingTicket(ticket.ownerEpoch, ticket.nonce, 1, 1, 0)
        fun prepare(observation: DirectShellPairingProtocolObservation = observation(), recovery: Boolean = true) {
            start()
            if (recovery) observer.pairingStarted(DirectShellPairingRecovery.RETAINED_UNCONFIRMED)
            observed.prepared(authorization, observation)
        }
        fun reserve() {
            reservation = flight.control.reserve(flight.prepared)!!
            assertTrue(reservation!!.claim(flight.prepared))
        }
        fun published() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.PUBLISHED,
            DirectShellPairingDurability.SYNC_CONFIRMED,
            publishedCredentialRevision = 1,
        )
        fun complete(receipt: DirectShellPairingStoreReceipt = published()) {
            prepare()
            reserve()
            flight.control.completed(reservation!!, receipt)
        }
        fun receipt(recovery: DirectShellPairingRecovery = DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT) =
            DirectShellPairingDrainReceipt(
                DirectShellPairingExternalEffect.POSSIBLE,
                recovery,
                protocolObservation = observation(),
            )
        fun deliver(receipt: DirectShellPairingDrainReceipt) = requireNotNull(drain)(receipt)
        fun assertKnowledge() {
            assertEquals(DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED, state.protocolVerification)
            assertEquals(DirectShellPairingExternalEffect.POSSIBLE, state.externalEffect)
        }
        fun assertSemanticFence() {
            assertTrue(state.drained)
            assertFalse(state.cleanupFailed)
            assertEquals(DirectShellPairingPublication.PUBLISHED, state.publication)
            assertEquals(DirectShellPairingPhase.RECONCILIATION_REQUIRED, state.phase)
            assertNull(owner.request(1))
            assertKnowledge()
        }
        override fun close() = owner.close()
    }

    private class Flight(val prepared: DirectShellPairingPreparedCommit, val control: DirectShellPairingCommitControl)
    private class Scheduler : DirectShellPairingScheduler {
        private val tasks = mutableListOf<() -> Unit>()
        override fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer {
            tasks += action
            return DirectShellPairingTimer {}
        }
        fun fireLast() = tasks.last().invoke()
    }
    private fun changes(): List<(DirectShellPairingEnvironment) -> DirectShellPairingEnvironment> = listOf(
        { it.copy(networkEpoch = 2) },
        { it.copy(permissionsGranted = false) },
        { it.copy(credentialRevision = 2) },
        { it.copy(backendAvailable = false) },
        { it.copy(runtimeInUse = true) },
    )
}
