package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.hostPairingSetupGeneration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure, unwired enrollment owner. Ports must not block; no Android, transport or Host authority. */
internal class DirectShellPairingCoordinator(
    private val readiness: () -> HostReadinessUiState,
    private val environment: () -> DirectShellPairingEnvironment,
    private val clock: DirectShellPairingClock,
    private val scheduler: DirectShellPairingScheduler,
    private val backend: DirectShellPairingBackend,
    private val committer: DirectShellPairingCommitter,
    private val ownerEpoch: String,
    private val nextNonce: () -> String,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(DirectShellPairingState())
    val state: StateFlow<DirectShellPairingState> = mutableState.asStateFlow()
    private var active: Attempt? = null
    private var retained: Attempt? = null
    private var closed = false

    init {
        require(ownerEpoch.isNotEmpty() && ownerEpoch.length <= 64) { "pairing_owner_epoch_invalid" }
    }

    private class Attempt(val ticket: DirectShellPairingTicket, var deadline: Long) {
        var operation: DirectShellPairingOperation? = null
        var authorization: DirectShellStagedAuthorization? = null
        var timer: DirectShellPairingTimer? = null
        var timerVersion = 0L
        var closing = false
        var workDeadline: Long? = null
        var submitted = false
        var recoveryPrepared = false
        var authenticated = false
        var observed = false
        var preparedObservation: DirectShellPairingProtocolObservation? = null
        var protocolVerification = DirectShellPairingProtocolVerification.UNCONFIRMED
        var observedEffect = DirectShellPairingExternalEffect.NONE_POSSIBLE
        var reservation: DirectShellPairingCommitReservation? = null
        var receipt: DirectShellPairingStoreReceipt? = null
        var drain: DirectShellPairingDrainReceipt? = null
        var receiptInvalid = false
        var reconcile = false
        var workComplete = false
        var cleanupFailed = false
        var target = DirectShellPairingPhase.FAILED
        var reason: DirectShellPairingReason? = null
    }

    /** Explicit intent only. Polling readiness/state must never call this method. */
    @Synchronized
    fun request(expectedReadinessGeneration: Long): DirectShellPairingTicket? {
        if (closed || active != null || retained != null) return null
        val env = environment()
        val failure = when {
            hostPairingSetupGeneration(readiness()) != expectedReadinessGeneration -> {
                DirectShellPairingReason.READINESS_STALE
            }
            !env.backendAvailable -> DirectShellPairingReason.BACKEND_UNAVAILABLE
            env.runtimeInUse -> DirectShellPairingReason.RUNTIME_BUSY
            env.reconciliationRequired -> DirectShellPairingReason.RECONCILIATION_REQUIRED
            else -> null
        }
        if (failure != null) {
            val phase = if (failure == DirectShellPairingReason.RECONCILIATION_REQUIRED) {
                DirectShellPairingPhase.RECONCILIATION_REQUIRED
            } else {
                DirectShellPairingPhase.UNAVAILABLE
            }
            mutableState.value = DirectShellPairingState(phase, reason = failure)
            return null
        }
        val nonce = nextNonce()
        require(nonce.isNotEmpty() && nonce.length <= 64) { "pairing_nonce_invalid" }
        val ticket =
            DirectShellPairingTicket(
                ownerEpoch,
                nonce,
                expectedReadinessGeneration,
                env.networkEpoch,
                env.credentialRevision,
            )
        val attempt = Attempt(ticket, after(CONSENT_MS))
        active = attempt
        show(attempt, DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE)
        arm(attempt)
        return ticket
    }

    /** Input is consumed/wiped on every path; no secret is placed in Attempt or StateFlow. */
    @Synchronized
    fun submitCode(
        ticket: DirectShellPairingTicket,
        input: CharArray,
        requireCurrentReadiness: Boolean = false,
    ): Boolean {
        val code = DirectShellPairingCode.take(input)
        try {
            val attempt = active?.takeIf { it.ticket === ticket } ?: return false
            if (attempt.closing ||
                mutableState.value.phase != DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE
            ) {
                return false
            }
            if (requireCurrentReadiness &&
                hostPairingSetupGeneration(readiness()) != ticket.originReadinessGeneration
            ) {
                return false
            }
            if (!checkCurrent(attempt)) return false
            if (code == null) {
                end(attempt, DirectShellPairingPhase.FAILED, DirectShellPairingReason.INVALID_CODE)
                return false
            }
            attempt.deadline = after(WORK_MS)
            attempt.workDeadline = attempt.deadline
            show(attempt, DirectShellPairingPhase.DISCOVERING)
            arm(attempt)
            try {
                val operation = backend.create(ticket, observer(attempt))
                attempt.operation = operation
                if (attempt.closing || active !== attempt) {
                    // create() is inert; if a port reentered cancellation, still dispose its returned handle.
                    operation.close {}
                    return false
                }
                attempt.submitted = true
                show(attempt, DirectShellPairingPhase.DISCOVERING)
                if (operation is DeadlineAwarePairingOperation) {
                    operation.start(
                        code,
                        DirectShellPairingWorkContext(ticket, attempt.workDeadline!!, clock, scheduler) {
                            synchronized(this) {
                                active === attempt && !attempt.closing && attempt.submitted &&
                                    mutableState.value.phase == DirectShellPairingPhase.DISCOVERING &&
                                    hostPairingSetupGeneration(readiness()) == ticket.originReadinessGeneration &&
                                    currentFailure(attempt) == null
                            }
                        },
                    )
                } else {
                    operation.start(code)
                }
            } catch (_: Exception) {
                if (active === attempt && !attempt.closing) {
                    end(
                        attempt,
                        DirectShellPairingPhase.FAILED,
                        DirectShellPairingReason.BACKEND_FAILURE,
                    )
                }
                return false
            }
            return true
        } finally {
            code?.close()
        }
    }

    @Synchronized
    fun cancel(ticket: DirectShellPairingTicket): DirectShellPairingStop {
        val attempt = (active ?: retained)?.takeIf { it.ticket === ticket }
            ?: return DirectShellPairingStop.STALE_OR_FINISHED
        if (active !== attempt) return stopDisposition(attempt)
        if (attempt.closing) {
            if (attempt.reservation == null) return DirectShellPairingStop.STALE_OR_FINISHED
            attempt.reconcile = true
            if (attempt.reason == null) attempt.reason = DirectShellPairingReason.USER_CANCELLED
            finishIfDrained(attempt)
            if (active === attempt && attempt.drain == null) showClosing(attempt)
            return stopDisposition(attempt)
        }
        end(attempt, DirectShellPairingPhase.CANCELLED, DirectShellPairingReason.USER_CANCELLED)
        return if (attempt.reservation == null) {
            DirectShellPairingStop.NO_LOCAL_PUBLICATION_WILL_START
        } else {
            stopDisposition(attempt)
        }
    }

    @Synchronized
    fun cancelHostRequest(originReadinessGeneration: Long): DirectShellPairingStop =
        (active ?: retained)?.ticket?.takeIf { it.originReadinessGeneration == originReadinessGeneration }
            ?.let(::cancel) ?: DirectShellPairingStop.STALE_OR_FINISHED

    /** Network/permission/key changes are explicit notifications; readiness refresh is not consent. */
    @Synchronized
    fun environmentChanged() {
        active?.let { attempt ->
            if (!attempt.closing) {
                checkCurrent(attempt)
            } else {
                val reason = currentFailure(attempt)
                if (reason != null) {
                    attempt.reconcile = true
                    if (attempt.reason == null) attempt.reason = reason
                    finishIfDrained(attempt)
                    if (active === attempt && attempt.drain == null) showClosing(attempt)
                }
            }
        }
    }

    /** Foreground wake hint; a queued timer may be late or already dequeued. */
    @Synchronized
    fun reevaluateDeadline() {
        val attempt = active ?: return
        if (clock.elapsedRealtimeMs() < attempt.deadline) return
        disarm(attempt)
        expireCurrentStage(attempt)
    }

    @Synchronized
    override fun close() {
        closed = true
        val attempt = active
        if (attempt == null) {
            if (retained == null) mutableState.value = DirectShellPairingState(DirectShellPairingPhase.CLOSED)
        } else if (!attempt.closing) {
            end(attempt, DirectShellPairingPhase.CANCELLED, DirectShellPairingReason.OWNER_CLOSED)
        } else if (attempt.reservation != null) {
            attempt.reconcile = true
            if (attempt.reason == null) attempt.reason = DirectShellPairingReason.OWNER_CLOSED
            finishIfDrained(attempt)
            if (active === attempt && attempt.drain == null) showClosing(attempt)
        }
    }

    private fun observer(attempt: Attempt) = object : ObservedDirectShellPairingObserver {
        override fun pairingStarted(recovery: DirectShellPairingRecovery): Boolean = synchronized(
            this@DirectShellPairingCoordinator,
        ) {
            if (active === attempt && !attempt.closing && checkCurrent(attempt) &&
                mutableState.value.phase == DirectShellPairingPhase.DISCOVERING
            ) {
                if (recovery != DirectShellPairingRecovery.RETAINED_UNCONFIRMED) {
                    end(attempt, DirectShellPairingPhase.FAILED, DirectShellPairingReason.RECOVERY_UNCONFIRMED)
                    return@synchronized false
                }
                attempt.recoveryPrepared = true
                show(attempt, DirectShellPairingPhase.PAIRING)
                true
            } else {
                false
            }
        }

        override fun prepared(authorization: DirectShellStagedAuthorization) = synchronized(
            this@DirectShellPairingCoordinator,
        ) {
            if (attempt.observed && (active === attempt || retained === attempt)) {
                retainRejected(attempt, authorization)
                semanticConflict(attempt)
                return@synchronized
            }
            if (attempt.authorization === authorization) return@synchronized
            if (active !== attempt || attempt.closing || attempt.authorization != null || authorization.isResolved) {
                if (!authorization.resolve(DirectShellPairingResolution.RETAIN_UNCONFIRMED) && active === attempt) {
                    attempt.cleanupFailed = true
                    if (attempt.closing) show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
                }
                return@synchronized
            }
            attempt.authorization = authorization
            attempt.authenticated = true
            if (!checkCurrent(attempt)) return@synchronized
            if (!attempt.recoveryPrepared) {
                end(attempt, DirectShellPairingPhase.FAILED, DirectShellPairingReason.RECOVERY_UNCONFIRMED)
                return@synchronized
            }
            show(attempt, DirectShellPairingPhase.COMMITTING)
            if (active !== attempt || attempt.closing) return@synchronized
            try {
                committer.commit(attempt.ticket, authorization, commitControl(attempt))
            } catch (_: Exception) {
                if (active === attempt && !attempt.closing) {
                    end(
                        attempt,
                        DirectShellPairingPhase.FAILED,
                        DirectShellPairingReason.COMMIT_FAILED,
                    )
                }
            }
        }

        override fun prepared(
            authorization: DirectShellStagedAuthorization,
            observation: DirectShellPairingProtocolObservation,
        ) = synchronized(this@DirectShellPairingCoordinator) {
            if (active !== attempt && retained !== attempt) {
                authorization.resolve(DirectShellPairingResolution.RETAIN_UNCONFIRMED)
                return@synchronized
            }
            if (attempt.authenticated) {
                retainRejected(attempt, authorization)
                semanticConflict(attempt)
                return@synchronized
            }
            attempt.observed = true
            if (attempt.authorization === authorization) {
                if (attempt.preparedObservation != observation) semanticConflict(attempt)
                return@synchronized
            }
            if (attempt.closing || attempt.authorization != null || authorization.isResolved) {
                retainRejected(attempt, authorization)
                val valid = recordObservation(attempt, observation)
                if (!valid || !attempt.closing || attempt.authorization != null) semanticConflict(attempt)
                if (attempt.drain != null) finishIfDrained(attempt) else showClosing(attempt)
                return@synchronized
            }
            if (!recordObservation(attempt, observation) ||
                observation.verification != DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED
            ) {
                retainRejected(attempt, authorization)
                semanticConflict(attempt)
                return@synchronized
            }
            attempt.authorization = authorization
            attempt.preparedObservation = observation
            if (!checkCurrent(attempt)) return@synchronized
            if (!attempt.recoveryPrepared) {
                end(attempt, DirectShellPairingPhase.FAILED, DirectShellPairingReason.RECOVERY_UNCONFIRMED)
                return@synchronized
            }
            show(attempt, DirectShellPairingPhase.COMMITTING)
            if (active !== attempt || attempt.closing) return@synchronized
            try {
                committer.commit(attempt.ticket, authorization, commitControl(attempt))
            } catch (_: Exception) {
                if (active === attempt && !attempt.closing) {
                    end(attempt, DirectShellPairingPhase.FAILED, DirectShellPairingReason.COMMIT_FAILED)
                }
            }
        }

        override fun failed(reason: DirectShellPairingReason) = synchronized(this@DirectShellPairingCoordinator) {
            if (active === attempt && !attempt.closing && checkCurrent(attempt)) {
                end(attempt, DirectShellPairingPhase.FAILED, reason)
            }
        }
    }

    private fun retainRejected(attempt: Attempt, authorization: DirectShellStagedAuthorization) {
        if (attempt.authorization !== authorization &&
            !authorization.resolve(DirectShellPairingResolution.RETAIN_UNCONFIRMED)
        ) {
            attempt.cleanupFailed = true
        }
    }

    private fun semanticConflict(attempt: Attempt) {
        attempt.reconcile = true
        if (attempt.reason == null) attempt.reason = DirectShellPairingReason.RECEIPT_INVALID
        if (active === attempt && !attempt.closing) {
            end(attempt, DirectShellPairingPhase.FAILED, attempt.reason)
        } else {
            finishIfDrained(attempt)
            if (active === attempt && attempt.drain == null) showClosing(attempt)
        }
    }

    /** CPU-only knowledge merge. The trusted worker, not this monitor, validates cryptographic evidence. */
    private fun recordObservation(attempt: Attempt, observation: DirectShellPairingProtocolObservation): Boolean {
        if (observation.ticket !== attempt.ticket) return false
        if (observation.externalEffect != DirectShellPairingExternalEffect.NONE_POSSIBLE ||
            observation.verification == DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED
        ) {
            attempt.observedEffect = DirectShellPairingExternalEffect.POSSIBLE
        }
        if (observation.externalEffect == DirectShellPairingExternalEffect.AUTHENTICATED ||
            (
                observation.verification == DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED &&
                    observation.externalEffect != DirectShellPairingExternalEffect.POSSIBLE
                )
        ) {
            return false
        }
        val consistent = !(
            attempt.protocolVerification == DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED &&
                observation.verification != DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED
            ) &&
            !(
                attempt.observedEffect == DirectShellPairingExternalEffect.POSSIBLE &&
                    observation.externalEffect != DirectShellPairingExternalEffect.POSSIBLE
                )
        if (observation.verification == DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED) {
            attempt.protocolVerification = observation.verification
        }
        if (observation.externalEffect == DirectShellPairingExternalEffect.POSSIBLE) {
            attempt.observedEffect = observation.externalEffect
        }
        return consistent
    }

    private fun commitControl(attempt: Attempt) = object : DirectShellPairingCommitControl {
        override fun reserve(prepared: DirectShellPairingPreparedCommit): DirectShellPairingCommitReservation? =
            synchronized(this@DirectShellPairingCoordinator) {
                if (active !== attempt || closed || attempt.closing || attempt.reservation != null ||
                    prepared.ticket !== attempt.ticket || prepared.authorization !== attempt.authorization ||
                    prepared.authorization.isResolved ||
                    prepared.expectedCredentialRevision == Long.MAX_VALUE ||
                    prepared.expectedCredentialRevision != attempt.ticket.credentialRevision || !checkCurrent(attempt)
                ) {
                    return@synchronized null
                }
                // R grants one specified attempt. It is NOT a filesystem/publication callback.
                DirectShellPairingCommitReservation.issue(prepared).also {
                    attempt.reservation = it
                    show(attempt, DirectShellPairingPhase.COMMIT_RESERVED)
                }
            }

        override fun completed(
            reservation: DirectShellPairingCommitReservation,
            receipt: DirectShellPairingStoreReceipt,
        ) = synchronized(this@DirectShellPairingCoordinator) {
            if ((active !== attempt && retained !== attempt) || attempt.reservation !== reservation) {
                return@synchronized
            }
            if (attempt.receipt == receipt) return@synchronized
            val executionProven = reservation.wasClaimed ||
                (reservation.wasAbstained && receipt.publication == DirectShellPairingPublication.NOT_PUBLISHED)
            val revisionValid = receipt.publication != DirectShellPairingPublication.PUBLISHED ||
                receipt.publishedCredentialRevision == attempt.ticket.credentialRevision + 1
            if (attempt.receipt != null || !executionProven || !receipt.isConsistent() || !revisionValid) {
                attempt.receiptInvalid = true
                attempt.reconcile = true
                attempt.reason = DirectShellPairingReason.RECEIPT_INVALID
            } else {
                attempt.receipt = receipt
                if (!receipt.cleanupComplete || receipt.publication != DirectShellPairingPublication.PUBLISHED ||
                    receipt.durability != DirectShellPairingDurability.SYNC_CONFIRMED ||
                    currentFailure(attempt) != null || closed
                ) {
                    attempt.reconcile = true
                }
                attempt.workComplete = !attempt.reconcile
            }
            if (!attempt.closing) {
                val success = !attempt.reconcile && !attempt.receiptInvalid
                end(
                    attempt,
                    if (success) {
                        DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY
                    } else {
                        DirectShellPairingPhase.FAILED
                    },
                    if (success) null else attempt.reason ?: DirectShellPairingReason.COMMIT_FAILED,
                )
            } else {
                finishIfDrained(attempt)
                if (active === attempt && attempt.drain == null) showClosing(attempt)
            }
        }
    }

    private fun checkCurrent(attempt: Attempt): Boolean {
        val reason = currentFailure(attempt) ?: return true
        val expired = reason == DirectShellPairingReason.CONSENT_EXPIRED ||
            reason == DirectShellPairingReason.OPERATION_EXPIRED
        end(
            attempt,
            if (expired) DirectShellPairingPhase.EXPIRED else DirectShellPairingPhase.FAILED,
            reason,
        )
        return false
    }

    private fun currentFailure(attempt: Attempt): DirectShellPairingReason? {
        val env = environment()
        val ownRevision = attempt.receipt?.takeIf {
            !attempt.receiptInvalid && it.publication == DirectShellPairingPublication.PUBLISHED
        }?.publishedCredentialRevision
        return when {
            !env.backendAvailable -> DirectShellPairingReason.BACKEND_UNAVAILABLE
            !env.permissionsGranted -> DirectShellPairingReason.PERMISSION_DENIED
            env.runtimeInUse -> DirectShellPairingReason.RUNTIME_BUSY
            env.networkEpoch != attempt.ticket.networkEpoch -> DirectShellPairingReason.NETWORK_CHANGED
            env.credentialRevision != attempt.ticket.credentialRevision && env.credentialRevision != ownRevision -> {
                DirectShellPairingReason.CREDENTIALS_CHANGED
            }
            env.reconciliationRequired -> DirectShellPairingReason.RECONCILIATION_REQUIRED
            !attempt.workComplete && clock.elapsedRealtimeMs() >= (attempt.workDeadline ?: attempt.deadline) -> if (
                attempt.workDeadline == null
            ) {
                DirectShellPairingReason.CONSENT_EXPIRED
            } else {
                DirectShellPairingReason.OPERATION_EXPIRED
            }
            else -> null
        }
    }

    private fun end(attempt: Attempt, target: DirectShellPairingPhase, reason: DirectShellPairingReason?) {
        if (active !== attempt || attempt.closing) return
        attempt.closing = true
        attempt.reservation?.abstainUnclaimed()
        attempt.target = target
        attempt.reason = reason
        if (attempt.reservation != null && target != DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY) {
            attempt.reconcile = true
        }
        disarm(attempt)
        val resolution = if (attempt.receipt?.publication == DirectShellPairingPublication.PUBLISHED) {
            DirectShellPairingResolution.RECORD_PUBLISHED
        } else {
            DirectShellPairingResolution.RETAIN_UNCONFIRMED
        }
        if (attempt.authorization?.resolve(resolution) == false) attempt.cleanupFailed = true
        val operation = attempt.operation
        if (operation == null) {
            drained(
                attempt,
                DirectShellPairingDrainReceipt(
                    DirectShellPairingExternalEffect.NONE_POSSIBLE,
                    DirectShellPairingRecovery.NONE,
                ),
            )
            return
        }
        showClosing(attempt)
        attempt.deadline = after(CLEANUP_MS)
        arm(attempt)
        if (target != DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY) {
            try {
                operation.cancel()
            } catch (_: Exception) {
                attempt.cleanupFailed = true
            }
        }
        try {
            operation.close { receipt -> synchronized(this) { drained(attempt, receipt) } }
        } catch (_: Exception) {
            attempt.cleanupFailed = true
        }
        if (active === attempt && attempt.cleanupFailed) show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
    }

    private fun drained(attempt: Attempt, receipt: DirectShellPairingDrainReceipt) {
        if (active !== attempt && !(retained === attempt && attempt.observed)) return
        attempt.reservation?.abstainUnclaimed()
        if (attempt.drain == receipt) return
        if (!attempt.authenticated && receipt.protocolObservation != null) attempt.observed = true
        if (attempt.observed) {
            observedDrain(attempt, receipt)
            return
        }
        if (receipt.protocolObservation != null) {
            attempt.reconcile = true
            if (attempt.reason == null) attempt.reason = DirectShellPairingReason.RECEIPT_INVALID
        }
        if (!receipt.transportDrained || !receipt.storeDrained || !receipt.codeCopiesReleased ||
            attempt.drain != null ||
            (attempt.authenticated && receipt.externalEffect != DirectShellPairingExternalEffect.AUTHENTICATED)
        ) {
            attempt.cleanupFailed = true
            show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
            return
        }
        attempt.drain = receipt
        attempt.operation = null
        finishIfDrained(attempt)
    }

    private fun observedDrain(attempt: Attempt, receipt: DirectShellPairingDrainReceipt) {
        val observation = receipt.protocolObservation
        if (!receipt.transportDrained || !receipt.storeDrained || !receipt.codeCopiesReleased ||
            (observation != null && observation.ticket !== attempt.ticket)
        ) {
            attempt.cleanupFailed = true
            show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
            return
        }
        val valid = observation != null && recordObservation(attempt, observation) &&
            receipt.externalEffect == observation.externalEffect &&
            (attempt.preparedObservation == null || observation == attempt.preparedObservation)
        if (receipt.externalEffect != DirectShellPairingExternalEffect.NONE_POSSIBLE) {
            attempt.observedEffect = DirectShellPairingExternalEffect.POSSIBLE
        }
        if (!valid || attempt.drain != null) {
            attempt.reconcile = true
            if (attempt.reason == null) attempt.reason = DirectShellPairingReason.RECEIPT_INVALID
        }
        // A same-operation resource receipt can prove D without proving semantic eligibility for F.
        if (attempt.drain == null) {
            attempt.drain = receipt
            attempt.operation = null
        }
        finishIfDrained(attempt)
    }

    private fun finishIfDrained(attempt: Attempt) {
        if ((active !== attempt && retained !== attempt) || attempt.drain == null) return
        if (attempt.workComplete) {
            currentFailure(attempt)?.let {
                attempt.reconcile = true
                if (attempt.reason == null) attempt.reason = it
            }
        }
        disarm(attempt)
        if (attempt.cleanupFailed) {
            show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
            return
        }
        val drain = attempt.drain!!
        val unknown = attempt.receiptInvalid || (
            attempt.reservation != null &&
                (attempt.receipt == null || attempt.receipt?.publication == DirectShellPairingPublication.UNKNOWN)
            )
        val stored = attempt.receipt?.publication == DirectShellPairingPublication.PUBLISHED
        val success = stored && attempt.workComplete && !attempt.reconcile && !unknown &&
            attempt.receipt?.durability == DirectShellPairingDurability.SYNC_CONFIRMED &&
            drain.recovery == DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT && !closed &&
            (
                !attempt.observed || (
                    attempt.preparedObservation != null &&
                        drain.protocolObservation == attempt.preparedObservation &&
                        drain.externalEffect == DirectShellPairingExternalEffect.POSSIBLE
                    )
                )
        val fenced = unknown || stored || attempt.reconcile ||
            drain.externalEffect != DirectShellPairingExternalEffect.NONE_POSSIBLE ||
            drain.recovery != DirectShellPairingRecovery.NONE
        val phase = when {
            unknown -> DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN
            success -> DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY
            fenced -> DirectShellPairingPhase.RECONCILIATION_REQUIRED
            closed -> DirectShellPairingPhase.CLOSED
            else -> attempt.target
        }
        active = null
        if (fenced) retained = attempt
        show(attempt, phase)
    }

    private fun showClosing(attempt: Attempt) = show(
        attempt,
        when {
            attempt.cleanupFailed -> DirectShellPairingPhase.CLEANUP_INCOMPLETE
            attempt.receiptInvalid || (
                attempt.reservation != null &&
                    (attempt.receipt == null || attempt.receipt?.publication == DirectShellPairingPublication.UNKNOWN)
                ) -> {
                DirectShellPairingPhase.COMMIT_OUTCOME_UNKNOWN
            }
            attempt.receipt?.publication == DirectShellPairingPublication.PUBLISHED -> DirectShellPairingPhase.CLOSING
            else -> DirectShellPairingPhase.CANCELLING
        },
    )

    private fun stopDisposition(attempt: Attempt): DirectShellPairingStop = when {
        attempt.receiptInvalid -> DirectShellPairingStop.RECONCILIATION_REQUIRED
        attempt.receipt?.publication == DirectShellPairingPublication.PUBLISHED -> {
            DirectShellPairingStop.ALREADY_STORED_NO_ROLLBACK
        }
        retained === attempt -> DirectShellPairingStop.RECONCILIATION_REQUIRED
        attempt.reservation != null -> DirectShellPairingStop.COMMIT_PENDING_NO_ROLLBACK_GUARANTEE
        else -> DirectShellPairingStop.STALE_OR_FINISHED
    }

    private fun show(attempt: Attempt, phase: DirectShellPairingPhase) {
        mutableState.value = DirectShellPairingState(
            phase,
            attempt.ticket.originReadinessGeneration,
            attempt.reason,
            attempt.cleanupFailed,
            attempt.receipt?.publication ?: if (attempt.reservation == null) {
                DirectShellPairingPublication.NOT_PUBLISHED
            } else {
                DirectShellPairingPublication.UNKNOWN
            },
            attempt.receipt?.durability ?: if (attempt.reservation == null) {
                DirectShellPairingDurability.NOT_APPLICABLE
            } else {
                DirectShellPairingDurability.UNCONFIRMED
            },
            if (attempt.observed) {
                if (attempt.drain == null && attempt.submitted) {
                    DirectShellPairingExternalEffect.POSSIBLE
                } else {
                    attempt.observedEffect
                }
            } else if (attempt.authenticated) {
                DirectShellPairingExternalEffect.AUTHENTICATED
            } else {
                attempt.drain?.externalEffect ?: if (attempt.submitted) {
                    DirectShellPairingExternalEffect.POSSIBLE
                } else {
                    DirectShellPairingExternalEffect.NONE_POSSIBLE
                }
            },
            attempt.drain?.recovery ?: if (attempt.recoveryPrepared || attempt.authenticated) {
                DirectShellPairingRecovery.RETAINED_UNCONFIRMED
            } else if (attempt.submitted) {
                DirectShellPairingRecovery.UNKNOWN
            } else {
                DirectShellPairingRecovery.NONE
            },
            attempt.drain != null,
            attempt.protocolVerification,
        )
    }

    private fun after(durationMs: Long): Long {
        val now = clock.elapsedRealtimeMs()
        require(now >= 0) { "pairing_clock_invalid" }
        return if (now > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else now + durationMs
    }

    private fun disarm(attempt: Attempt) {
        attempt.timerVersion++
        val timer = attempt.timer
        attempt.timer = null
        timer?.cancel()
    }

    private fun arm(attempt: Attempt) {
        disarm(attempt)
        val version = attempt.timerVersion
        attempt.timer = scheduler.at(attempt.deadline) {
            synchronized(this) {
                if (active !== attempt || attempt.timerVersion != version) return@synchronized
                attempt.timer = null
                attempt.timerVersion++
                if (clock.elapsedRealtimeMs() < attempt.deadline) {
                    arm(attempt)
                } else {
                    expireCurrentStage(attempt)
                }
            }
        }
    }

    private fun expireCurrentStage(attempt: Attempt) {
        if (attempt.closing) {
            show(attempt, DirectShellPairingPhase.CLEANUP_INCOMPLETE)
        } else {
            checkCurrent(attempt)
        }
    }

    companion object {
        const val CONSENT_MS = 120_000L
        const val WORK_MS = 60_000L
        const val CLEANUP_MS = 3_000L
    }
}
