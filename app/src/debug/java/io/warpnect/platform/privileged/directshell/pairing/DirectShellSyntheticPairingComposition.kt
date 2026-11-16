package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.PairingConnectionCtx
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Unregistered localhost qualification adapter. The fixture owner must explicitly observe final drain. */
internal class DirectShellSyntheticPairingComposition(
    private val clock: DirectShellPairingClock,
    private val scheduler: DirectShellPairingScheduler,
    private val storage: () -> DirectShellCredentialStorage,
    private val identity: () -> IdentityBorrow,
    private val endpoint: () -> InetSocketAddress,
    private val socket: () -> Socket = { Socket() },
    private val checkpoint: (Stage) -> Unit = {},
) : DirectShellPairingBackend, DirectShellPairingCommitter {
    enum class Stage {
        BEFORE_RECOVERY,
        RECOVERY_READY,
        PEER_BOUND,
        PREPARED,
        BEFORE_R,
        RESERVED,
        PUBLISHED,
        BEFORE_EXIT,
    }

    /** Release is worker-only and concerns the borrow, not destruction of the fixture's original key. */
    class IdentityBorrow(val material: DirectShellCredentialIdentity, val release: () -> Boolean) {
        override fun toString(): String = "SyntheticIdentityBorrow(redacted)"
    }

    private val current = AtomicReference<Operation?>()

    override fun create(ticket: DirectShellPairingTicket, observer: DirectShellPairingObserver): Operation {
        require(observer is ObservedDirectShellPairingObserver) { "pairing_observed_port_required" }
        val operation = Operation(ticket, observer)
        check(current.compareAndSet(null, operation)) { "pairing_operation_occupied" }
        return operation
    }

    override fun commit(
        ticket: DirectShellPairingTicket,
        authorization: DirectShellStagedAuthorization,
        control: DirectShellPairingCommitControl,
    ) {
        val operation = current.get()
        check(operation != null && operation.ticket === ticket) { "pairing_commit_owner_invalid" }
        operation.enqueue(authorization, control)
    }

    internal inner class Operation(
        val ticket: DirectShellPairingTicket,
        private val observer: ObservedDirectShellPairingObserver,
    ) : DeadlineAwarePairingOperation {
        private val started = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val delivery = AtomicBoolean()
        private val command = AtomicReference<Commit?>()
        private val callback = AtomicReference<((DirectShellPairingDrainReceipt) -> Unit)?>()
        private val staged = DirectShellStagedAuthorization { resolution.set(it) }
        private val resolution = AtomicReference<DirectShellPairingResolution?>()

        @Volatile private var worker: Thread? = null

        @Volatile private var transport: DirectShellPairingTransport? = null

        @Volatile private var lastTransport: DirectShellPairingTransport.Receipt? = null

        @Volatile private var storeReleased = true

        @Volatile private var borrowReleased = true

        @Volatile private var codeReleased = true

        @Volatile private var recovery = DirectShellPairingRecovery.NONE

        @Volatile private var observation = DirectShellPairingProtocolObservation(
            ticket,
            DirectShellPairingProtocolVerification.UNCONFIRMED,
            DirectShellPairingExternalEffect.NONE_POSSIBLE,
        )

        @Volatile var storeReceipt: DirectShellPairingStoreReceipt? = null
            private set

        @Volatile var workContext: DirectShellPairingWorkContext? = null
            private set

        override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
            check(work.ticket === ticket && work.clock === clock && work.scheduler === scheduler) {
                "pairing_work_owner_invalid"
            }
            check(started.compareAndSet(false, true) && !stopped.get()) { "pairing_work_already_started" }
            check(
                work.deadlineElapsedRealtimeMs > clock.elapsedRealtimeMs() &&
                    work.deadlineElapsedRealtimeMs - clock.elapsedRealtimeMs() <= DirectShellPairingCoordinator.WORK_MS,
            ) { "pairing_work_expired" }
            workContext = work
            val copy = code.useCharacters { requireNotNull(DirectShellPairingCode.take(it)) }
            codeReleased = false
            val ownedWorker = Thread({ run(copy, work) }, "pairing-composition-owned")
            // Publish ownership before dispatch; a live worker must never look absent to the drain observer.
            worker = ownedWorker
            ownedWorker.start()
        }

        fun enqueue(authorization: DirectShellStagedAuthorization, control: DirectShellPairingCommitControl) {
            check(authorization === staged && command.compareAndSet(null, Commit(control))) {
                "pairing_commit_command_invalid"
            }
        }

        override fun cancel() {
            stopped.set(true)
            transport?.requestStop(ticket)
        }

        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
            check(callback.compareAndSet(null, drained)) { "pairing_close_already_requested" }
            cancel()
        }

        private fun active(work: DirectShellPairingWorkContext): Boolean =
            current.get() === this && workContext === work && !stopped.get() &&
                clock.elapsedRealtimeMs() < work.deadlineElapsedRealtimeMs

        private fun requireActive(work: DirectShellPairingWorkContext) {
            check(active(work)) { "pairing_operation_stopped" }
        }

        private fun run(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
            var ownedStore: DirectShellCredentialStorage? = null
            var borrow: IdentityBorrow? = null
            var protocol: DirectShellOwnedPairingProtocol? = null
            var ownedSocket: Socket? = null
            var borrowAttempted = false
            var storageAttempted = false
            try {
                checkpoint(Stage.BEFORE_RECOVERY)
                requireActive(work)
                borrowAttempted = true
                borrowReleased = false
                borrow = identity()
                storageAttempted = true
                storeReleased = false
                ownedStore = storage()
                recovery = DirectShellPairingRecovery.UNKNOWN
                val status = ownedStore.begin(ticket, requireNotNull(borrow).material)
                if (status != DirectShellCredentialStorage.Status.RECOVERY_READY) {
                    observer.failed(DirectShellPairingReason.RECOVERY_UNCONFIRMED)
                    return
                }
                recovery = DirectShellPairingRecovery.RETAINED_UNCONFIRMED
                checkpoint(Stage.RECOVERY_READY)
                requireActive(work)
                val admittedStore = ownedStore
                if (admittedStore.recoveryAdmission(ticket) != DirectShellCredentialStorage.Status.RECOVERY_READY) {
                    observer.failed(DirectShellPairingReason.RECOVERY_UNCONFIRMED)
                    return
                }
                if (!observer.pairingStarted(recovery)) return
                val address = endpoint()
                protocol = DirectShellOwnedPairingProtocol(address) {
                    val material = requireNotNull(borrow).material
                    DirectShellOwnedPairingProtocol.Identity(material.privateKey, material.certificate)
                }
                val io = requireNotNull(
                    DirectShellOwnedPairingProtocol.transport(
                        ticket,
                        work,
                        address,
                        {
                            active(work) &&
                                admittedStore.recoveryAdmission(ticket) ==
                                DirectShellCredentialStorage.Status.RECOVERY_READY
                        },
                        protocol,
                        { socket().also { ownedSocket = it } },
                    ),
                )
                transport = io
                if (!active(work)) io.requestStop(ticket)
                if (!io.start(code)) {
                    observer.failed(DirectShellPairingReason.BACKEND_FAILURE)
                    return
                }
                io.awaitWork()
                val result = io.awaitDrain()
                lastTransport = result
                observation = DirectShellPairingProtocolObservation(
                    ticket, DirectShellPairingProtocolVerification.UNCONFIRMED, result.externalEffect,
                )
                val peer = protocol.verifiedPeer()
                if (peer == null) {
                    observer.failed(DirectShellPairingReason.PAIRING_REJECTED)
                    return
                }
                val evidence =
                    bindEvidence(ticket, work, workContext, ownedSocket, address, requireNotNull(borrow).material, peer)
                observation = DirectShellPairingProtocolObservation(
                    ticket, DirectShellPairingProtocolVerification.PEER_INFO_VERIFIED,
                    DirectShellPairingExternalEffect.POSSIBLE,
                )
                checkpoint(Stage.PEER_BOUND)
                requireActive(work)
                check(result.drained) { "pairing_transport_not_drained" }
                val preparation = ownedStore.prepare(evidence, staged)
                val candidate = requireNotNull(preparation.candidate) { "pairing_candidate_unconfirmed" }
                check(preparation.status == DirectShellCredentialStorage.Status.PREPARED)
                checkpoint(Stage.PREPARED)
                requireActive(work)
                observer.prepared(staged, observation)
                val commit = command.get() ?: return
                checkpoint(Stage.BEFORE_R)
                val reservation = commit.control.reserve(candidate.prepared) ?: return
                checkpoint(Stage.RESERVED)
                val receipt = ownedStore.publish(candidate, reservation)
                storeReceipt = receipt
                if (receipt.publication == DirectShellPairingPublication.PUBLISHED) {
                    recovery = DirectShellPairingRecovery.COMMITTED_RECORD_PRESENT
                }
                checkpoint(Stage.PUBLISHED)
                commit.control.completed(reservation, receipt)
            } catch (_: Exception) {
                observer.failed(DirectShellPairingReason.BACKEND_FAILURE)
            } finally {
                transport?.let {
                    it.requestStop(ticket)
                    lastTransport = it.awaitDrain()
                    if (lastTransport!!.externalEffect == DirectShellPairingExternalEffect.POSSIBLE) {
                        observation = observation.copy(externalEffect = DirectShellPairingExternalEffect.POSSIBLE)
                    }
                    if (lastTransport!!.drained) transport = null
                }
                code.close()
                codeReleased = true
                storeReleased = try {
                    ownedStore?.close() ?: !storageAttempted
                } catch (_: Exception) {
                    false
                }
                if (lastTransport?.drained != false) {
                    borrowReleased = try {
                        borrow?.release?.invoke() ?: !borrowAttempted
                    } catch (_: Exception) {
                        false
                    }
                    borrow = null
                }
                checkpoint(Stage.BEFORE_EXIT)
            }
        }

        /** Explicit owner-side qualification driver, never called by P1 or the operation's own worker. */
        fun awaitDrain(observationMs: Long = 3_000): Boolean {
            require(observationMs in 0..3_000 && Thread.currentThread() !== worker)
            val ownedWorker = worker
            if (ownedWorker != null && observationMs > 0) TimeUnit.MILLISECONDS.timedJoin(ownedWorker, observationMs)
            if (ownedWorker?.isAlive == true) return false
            val io = transport?.observe() ?: lastTransport
            if (io?.drained == false || !storeReleased || !borrowReleased || !codeReleased) return false
            val receipt = DirectShellPairingDrainReceipt(
                observation.externalEffect,
                recovery,
                transportDrained = true,
                storeDrained = storeReleased && borrowReleased,
                codeCopiesReleased = codeReleased && io?.codeReleased != false,
                protocolObservation = observation,
            )
            val notify = callback.get() ?: return false
            if (delivery.compareAndSet(false, true)) {
                current.compareAndSet(this, null)
                notify(receipt)
            }
            return true
        }

        fun workerAlive(): Boolean = worker?.isAlive == true
    }

    private class Commit(val control: DirectShellPairingCommitControl)

    companion object {
        /** Worker-only validation of the actual same-instance protocol result, not a discovery digest. */
        fun bindEvidence(
            ticket: DirectShellPairingTicket,
            work: DirectShellPairingWorkContext,
            expectedWork: DirectShellPairingWorkContext?,
            socket: Socket?,
            endpoint: InetSocketAddress,
            identity: DirectShellCredentialIdentity,
            peer: PairingConnectionCtx.AuthenticatedPeerInfo,
        ): DirectShellAuthenticatedPairingEvidence {
            check(
                work === expectedWork && work.ticket === ticket && socket?.isConnected == true &&
                    socket.remoteSocketAddress == endpoint && peer.endpoint == endpoint,
            ) { "pairing_evidence_owner_invalid" }
            val local = DirectShellCredentialCodec.digest(identity.certificate.encoded)
            check(
                MessageDigest.isEqual(local, peer.localCertificateSha256) && peer.remoteCertificateSha256.size == 32,
            ) {
                "pairing_evidence_identity_invalid"
            }
            return DirectShellAuthenticatedPairingEvidence(
                ticket,
                peer.localCertificateSha256,
                peer.remoteCertificateSha256,
            )
        }
    }
}
