package io.warpnect.session.pairing

import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.trust.TrustStoreError
import io.warpnect.session.trust.TrustedPeerRecord
import io.warpnect.session.trust.TrustedPeerStore

/** A temporary bootstrap locator. It is not a DeviceId, trusted peer, SessionId, or PathId. */
data class PairingTransportEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank() && host.length <= MAX_HOST_LENGTH)
        require(port in 1..65_535)
    }

    companion object {
        const val MAX_HOST_LENGTH: Int = 255
    }
}

enum class PairingTransportSendResult {
    Sent,
    WouldBlock,
    Failed,
}

interface PairingTransport : AutoCloseable {
    fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?)

    fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult

    override fun close()
}

fun interface PairingMonotonicClock {
    fun nowMs(): Long
}

fun interface PairingWallClock {
    fun nowMs(): Long
}

object SystemPairingMonotonicClock : PairingMonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

object SystemPairingWallClock : PairingWallClock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

fun interface PairingEventListener {
    fun onVerificationPrompt(prompt: PairingVerificationPrompt)
}

/**
 * Cold-path notification emitted only after the trusted-peer store has accepted the completed
 * SAS exchange. It lets RFC-005I begin a new WNSH attempt without treating a prompt as trust.
 */
fun interface PairingCompletedListener {
    fun onPairingCompleted(record: TrustedPeerRecord)
}

enum class PairingControllerState {
    NotPairable,
    Pairable,
    Negotiating,
    AwaitingUserConfirmation,
    Closed,
}

data class PairingSnapshot(
    val state: PairingControllerState,
    val pairable: Boolean,
    val pairingWindowRemainingMs: Long,
    val activeAttemptCount: Int,
    val initiatedAttempts: Long,
    val acceptedIncomingAttempts: Long,
    val successfulPairings: Long,
    val userRejects: Long,
    val verificationMismatches: Long,
    val timeouts: Long,
    val transportFailures: Long,
    val malformedPackets: Long,
    val signatureFailures: Long,
    val commitmentFailures: Long,
    val confirmationMacFailures: Long,
    val alreadyTrusted: Long,
    val identityKeyMismatches: Long,
    val trustedPeerCount: Int,
    val identityKeySecurityLevel: io.warpnect.session.identity.IdentityKeySecurityLevel,
    val lastError: PairingError,
)

data class PairingControllerResult(
    val error: PairingError,
    val snapshot: PairingSnapshot,
    val verificationPrompt: PairingVerificationPrompt? = null,
) {
    val isSuccess: Boolean
        get() = error == PairingError.None
}

/**
 * Bounded pairing runtime owner. Its timers are externally driven through [advance] so JVM tests
 * never sleep; Android wraps it on one cold-path HandlerThread. It owns no SessionManager state.
 */
class PairingController(
    private val localSigner: LocalDeviceIdentitySigner,
    private val trustedPeerStore: TrustedPeerStore,
    private val transport: PairingTransport,
    private val crypto: PairingCryptoProvider = JcaPairingCryptoProvider(),
    private val config: PairingConfig = PairingConfig(),
    private val monotonicClock: PairingMonotonicClock = SystemPairingMonotonicClock,
    private val wallClock: PairingWallClock = SystemPairingWallClock,
    private val eventListener: PairingEventListener? = null,
    private val completedListener: PairingCompletedListener? = null,
) : AutoCloseable {
    private val attempts = LinkedHashMap<PairingAttemptId, ManagedAttempt>()
    private val prompts = HashMap<PairingAttemptId, PairingVerificationPrompt>()
    private var pairableUntilMs: Long? = null
    private var closed = false
    private var lastError = PairingError.None
    private val counters = PairingCounters()

    fun openPairingWindow(windowMs: Long = config.pairingWindowMs): PairingControllerResult = synchronized(this) {
        if (closed) return@synchronized result(PairingError.Closed)
        require(windowMs > 0L)
        pairableUntilMs = monotonicClock.nowMs() + windowMs
        ensureListening()
        result(PairingError.None)
    }

    fun closePairingWindow(): PairingControllerResult = synchronized(this) {
        pairableUntilMs = null
        stopListeningIfIdle()
        result(PairingError.None)
    }

    /** Explicit initiator action. Discovery must call this only after selecting a usable LAN route. */
    fun beginPairing(
        endpoint: PairingTransportEndpoint,
        remoteUntrustedAlias: String? = null,
    ): PairingControllerResult = synchronized(this) {
        if (closed) return@synchronized result(PairingError.Closed)
        if (attempts.size >= config.maxActiveAttempts) return@synchronized result(PairingError.AttemptCapacityExceeded)
        ensureListening()
        val attemptId = nextAttemptId() ?: return@synchronized result(PairingError.AttemptCapacityExceeded)
        val started = PairingEngine.initiate(
            attemptId = attemptId,
            localSigner = localSigner,
            crypto = crypto,
            trustInspector = trustInspector(),
            remoteUntrustedAlias = remoteUntrustedAlias,
        )
        val engine = started.engine ?: return@synchronized result(started.result.error)
        val nowMs = monotonicClock.nowMs()
        val managed = ManagedAttempt(engine, endpoint, remoteUntrustedAlias, nowMs)
        attempts[attemptId] = managed
        counters.initiatedAttempts += 1
        processEngineResult(managed, started.result, nowMs)
    }

    fun acceptVerification(attemptId: PairingAttemptId): PairingControllerResult = synchronized(this) {
        val managed = attempts[attemptId] ?: return@synchronized result(PairingError.InvalidPacket)
        val nowMs = monotonicClock.nowMs()
        processEngineResult(managed, managed.engine.acceptVerification(), nowMs)
    }

    fun rejectVerification(attemptId: PairingAttemptId, mismatch: Boolean = false): PairingControllerResult =
        synchronized(this) {
            val managed = attempts[attemptId] ?: return@synchronized result(PairingError.InvalidPacket)
            val nowMs = monotonicClock.nowMs()
            processEngineResult(managed, managed.engine.rejectVerification(mismatch), nowMs)
        }

    fun verificationPrompt(attemptId: PairingAttemptId): PairingVerificationPrompt? = synchronized(this) {
        prompts[attemptId]
    }

    /** Advances bounded retry, pairing-window, and confirmation timers without a busy poll. */
    fun advance(): PairingControllerResult = synchronized(this) {
        if (closed) return@synchronized result(PairingError.Closed)
        val nowMs = monotonicClock.nowMs()
        if (pairableUntilMs?.let { nowMs >= it } == true) pairableUntilMs = null
        val timedOut = mutableListOf<ManagedAttempt>()
        attempts.values.toList().forEach { managed ->
            val prompt = prompts[managed.engine.attemptId]
            when {
                managed.engine.state == PairingEngineState.Paired -> {
                    resendDue(managed, nowMs)
                    if (managed.pendingOutbound.isEmpty()) removeManagedAttempt(managed)
                }
                nowMs - managed.createdAtMs >= config.attemptTimeoutMs -> timedOut += managed
                prompt != null && nowMs >= prompt.expiresAtMonotonicMs -> timedOut += managed
                resendDue(managed, nowMs) -> timedOut += managed
            }
        }
        timedOut.forEach { managed ->
            val userConfirmation = prompts.containsKey(managed.engine.attemptId)
            processEngineResult(managed, managed.engine.timeout(userConfirmation), nowMs)
        }
        stopListeningIfIdle()
        result(PairingError.None)
    }

    fun snapshot(): PairingSnapshot = synchronized(this) { snapshotLocked() }

    /** Next bounded control-plane deadline; null means no listener/timer work is needed. */
    fun nextWakeAtMonotonicMs(): Long? = synchronized(this) {
        if (closed) return@synchronized null
        buildList {
            pairableUntilMs?.let(::add)
            attempts.values.forEach { managed ->
                if (managed.engine.state != PairingEngineState.Paired) {
                    add(
                        managed.createdAtMs + config.attemptTimeoutMs,
                    )
                }
                prompts[managed.engine.attemptId]?.expiresAtMonotonicMs?.let(::add)
                managed.pendingOutbound.values.forEach { pending -> add(pending.nextRetryAtMs) }
            }
        }.minOrNull()
    }

    override fun close() = synchronized(this) {
        if (closed) return@synchronized
        closed = true
        attempts.values.forEach { it.engine.close() }
        attempts.clear()
        prompts.clear()
        pairableUntilMs = null
        transport.setDatagramListener(null)
        transport.close()
    }

    private fun receiveDatagram(endpoint: PairingTransportEndpoint, datagram: ByteArray) {
        synchronized(this) {
            if (closed) return
            val decoded = PairingBootstrapCodec.decode(datagram)
            val packet = decoded.packet
            if (packet == null) {
                counters.malformedPackets += 1
                lastError = PairingError.InvalidPacket
                return
            }
            val nowMs = monotonicClock.nowMs()
            val existing = attempts[packet.attemptId]
            if (packet.message is PairingBootstrapMessage.Commit && existing == null) {
                receiveNewCommit(endpoint, packet, nowMs)
                return
            }
            val managed = existing ?: return
            if (managed.endpoint != endpoint) {
                lastError = PairingError.EndpointMismatch
                return
            }
            val engineResult = managed.engine.receive(packet)
            if (engineResult.error == PairingError.None) removeExpectedRetry(managed, packet.message.type)
            processEngineResult(managed, engineResult, nowMs)
        }
    }

    private fun receiveNewCommit(endpoint: PairingTransportEndpoint, packet: PairingBootstrapPacket, nowMs: Long) {
        if (!isPairable(nowMs)) {
            sendStateless(
                endpoint,
                PairingBootstrapPacket(
                    packet.attemptId,
                    PairingBootstrapMessage.Reject(PairingRejectReason.NotPairable),
                ),
            )
            lastError = PairingError.PairingWindowExpired
            return
        }
        if (attempts.size >= config.maxActiveAttempts) {
            sendStateless(
                endpoint,
                PairingBootstrapPacket(packet.attemptId, PairingBootstrapMessage.Reject(PairingRejectReason.Busy)),
            )
            lastError = PairingError.AttemptCapacityExceeded
            return
        }
        val started = PairingEngine.respond(
            attemptId = packet.attemptId,
            localSigner = localSigner,
            crypto = crypto,
            trustInspector = trustInspector(),
        )
        val engine = started.engine ?: run {
            lastError = started.result.error
            return
        }
        val managed = ManagedAttempt(engine, endpoint, null, nowMs)
        attempts[packet.attemptId] = managed
        counters.acceptedIncomingAttempts += 1
        processEngineResult(managed, engine.receive(packet), nowMs)
    }

    private fun processEngineResult(
        managed: ManagedAttempt,
        engineResult: PairingEngineResult,
        nowMs: Long,
    ): PairingControllerResult {
        var prompt: PairingVerificationPrompt? = null
        if (engineResult.error != PairingError.None) {
            recordEngineError(engineResult.error)
            lastError = engineResult.error
        }
        engineResult.actions.forEach { action ->
            when (action) {
                is PairingEngineAction.Send -> sendAction(managed, action.packet, nowMs)
                is PairingEngineAction.Prompt -> {
                    prompt = PairingVerificationPrompt(
                        attemptId = managed.engine.attemptId,
                        remoteUntrustedAlias = managed.remoteUntrustedAlias,
                        remoteDeviceId = action.remotePeer.deviceId,
                        remoteIdentityFingerprint = action.remotePeer.fingerprint,
                        shortAuthenticationString = action.shortAuthenticationString,
                        state = managed.engine.state,
                        expiresAtMonotonicMs = nowMs + config.userConfirmationTimeoutMs,
                    )
                    prompts[managed.engine.attemptId] = prompt
                    eventListener?.onVerificationPrompt(prompt)
                }
                is PairingEngineAction.Completed -> persistTrust(managed, action)
            }
        }
        if (managed.engine.state.isTerminal()) {
            prompts.remove(managed.engine.attemptId)
            if (managed.engine.state != PairingEngineState.Paired ||
                !managed.pendingOutbound.containsKey(PairingMessageType.Confirm)
            ) {
                removeManagedAttempt(managed)
            }
        }
        return result(engineResult.error, prompt)
    }

    private fun persistTrust(managed: ManagedAttempt, completion: PairingEngineAction.Completed) {
        val nowWallMs = wallClock.nowMs().coerceAtLeast(0L)
        val stored = trustedPeerStore.bind(
            TrustedPeerRecord(
                peerDeviceId = completion.remotePeer.deviceId,
                identityKeyAlgorithm = io.warpnect.session.identity.IdentityKeyAlgorithm.EcdsaP256Sha256,
                identityPublicKey = completion.remotePeer.publicKey,
                identityFingerprint = completion.remotePeer.fingerprint,
                pairedAtWallClockMs = nowWallMs,
                lastVerifiedAtWallClockMs = nowWallMs,
                remoteAliasAtPairing = managed.remoteUntrustedAlias?.take(64),
            ),
        )
        when (stored.error) {
            TrustStoreError.None -> {
                counters.successfulPairings += 1
                completedListener?.onPairingCompleted(requireNotNull(stored.record))
            }
            TrustStoreError.AlreadyTrusted -> {
                counters.alreadyTrusted += 1
                completedListener?.onPairingCompleted(requireNotNull(stored.record))
            }
            TrustStoreError.PeerIdentityKeyChanged,
            TrustStoreError.IdentityBindingConflict,
            -> counters.identityKeyMismatches += 1
            else -> Unit
        }
        if (stored.error != TrustStoreError.None && stored.error != TrustStoreError.AlreadyTrusted) {
            lastError = PairingEngine.trustError(stored.error)
        }
    }

    private fun sendAction(managed: ManagedAttempt, packet: PairingBootstrapPacket, nowMs: Long) {
        val datagram = PairingBootstrapCodec.encode(packet)
        val sendResult = transport.send(managed.endpoint, datagram)
        if (sendResult == PairingTransportSendResult.Failed) counters.transportFailures += 1
        if (!packet.message.type.isRetryable()) return
        val existing = managed.pendingOutbound[packet.message.type]
        if (existing?.datagram?.contentEquals(datagram) == true) return
        managed.pendingOutbound[packet.message.type] = PendingOutbound(
            datagram = datagram,
            retryIndex = 0,
            nextRetryAtMs = nowMs + PairingBootstrapProtocol.RETRY_DELAYS_MS.first(),
        )
    }

    private fun sendStateless(endpoint: PairingTransportEndpoint, packet: PairingBootstrapPacket) {
        if (transport.send(endpoint, PairingBootstrapCodec.encode(packet)) == PairingTransportSendResult.Failed) {
            counters.transportFailures += 1
        }
    }

    private fun resendDue(managed: ManagedAttempt, nowMs: Long): Boolean {
        var retriesExhausted = false
        managed.pendingOutbound.entries.toList().forEach { (type, pending) ->
            if (nowMs < pending.nextRetryAtMs) return@forEach
            if (pending.retryIndex >= PairingBootstrapProtocol.RETRY_DELAYS_MS.size) {
                managed.pendingOutbound.remove(type)
                retriesExhausted = true
                return@forEach
            }
            if (transport.send(managed.endpoint, pending.datagram.copyOf()) == PairingTransportSendResult.Failed) {
                counters.transportFailures += 1
            }
            pending.retryIndex += 1
            pending.nextRetryAtMs = nowMs + PairingBootstrapProtocol.RETRY_DELAYS_MS.getOrElse(pending.retryIndex) {
                PairingBootstrapProtocol.RETRY_DELAYS_MS.last()
            }
        }
        return retriesExhausted
    }

    private fun removeExpectedRetry(managed: ManagedAttempt, receivedType: PairingMessageType) {
        when (receivedType) {
            PairingMessageType.Response -> managed.pendingOutbound.remove(PairingMessageType.Commit)
            PairingMessageType.Reveal -> managed.pendingOutbound.remove(PairingMessageType.Response)
            else -> Unit
        }
    }

    private fun trustInspector(): PairingPeerTrustInspector = PairingPeerTrustInspector { deviceId, publicKey ->
        val validation = trustedPeerStore.validateBinding(deviceId, publicKey)
        when (validation) {
            // An exact existing binding may complete an explicit re-pairing attempt idempotently.
            // This repairs the harmless final-confirm-loss case without replacing any trust data.
            TrustStoreError.None,
            TrustStoreError.AlreadyTrusted,
            -> PairingError.None
            else -> PairingEngine.trustError(validation)
        }
    }

    private fun isPairable(nowMs: Long): Boolean = pairableUntilMs?.let { nowMs < it } == true

    private fun nextAttemptId(): PairingAttemptId? {
        repeat(16) {
            val bytes = crypto.randomBytes(16)
            val high = bytes.toULong(0)
            val low = bytes.toULong(8)
            val candidate = PairingAttemptId.fromParts(high, low)
            if (candidate != null && !attempts.containsKey(candidate)) return candidate
        }
        return null
    }

    private fun ByteArray.toULong(offset: Int): ULong {
        var value = 0uL
        repeat(8) { index -> value = (value shl 8) or (this[offset + index].toInt() and 0xff).toULong() }
        return value
    }

    private fun recordEngineError(error: PairingError) {
        when (error) {
            PairingError.SignatureInvalid -> counters.signatureFailures += 1
            PairingError.CommitmentMismatch -> counters.commitmentFailures += 1
            PairingError.ConfirmationMacInvalid -> counters.confirmationMacFailures += 1
            PairingError.AlreadyTrusted -> counters.alreadyTrusted += 1
            PairingError.PeerIdentityKeyChanged,
            PairingError.IdentityBindingConflict,
            -> counters.identityKeyMismatches += 1
            PairingError.UserRejected -> counters.userRejects += 1
            PairingError.VerificationMismatch -> counters.verificationMismatches += 1
            PairingError.PairingTransportTimeout,
            PairingError.UserConfirmationTimeout,
            -> counters.timeouts += 1
            else -> Unit
        }
    }

    private fun result(error: PairingError, prompt: PairingVerificationPrompt? = null): PairingControllerResult =
        PairingControllerResult(error, snapshotLocked(), prompt)

    private fun removeManagedAttempt(managed: ManagedAttempt) {
        managed.pendingOutbound.clear()
        prompts.remove(managed.engine.attemptId)
        attempts.remove(managed.engine.attemptId)
        stopListeningIfIdle()
    }

    private fun ensureListening() {
        transport.setDatagramListener(::receiveDatagram)
    }

    private fun stopListeningIfIdle() {
        if (!closed && attempts.isEmpty() && !isPairable(monotonicClock.nowMs())) {
            transport.setDatagramListener(null)
        }
    }

    private fun snapshotLocked(): PairingSnapshot {
        val nowMs = monotonicClock.nowMs()
        val hasPrompt = prompts.isNotEmpty()
        val state = when {
            closed -> PairingControllerState.Closed
            hasPrompt -> PairingControllerState.AwaitingUserConfirmation
            attempts.isNotEmpty() -> PairingControllerState.Negotiating
            isPairable(nowMs) -> PairingControllerState.Pairable
            else -> PairingControllerState.NotPairable
        }
        return PairingSnapshot(
            state = state,
            pairable = isPairable(nowMs),
            pairingWindowRemainingMs = pairableUntilMs?.let { (it - nowMs).coerceAtLeast(0L) } ?: 0L,
            activeAttemptCount = attempts.size,
            initiatedAttempts = counters.initiatedAttempts,
            acceptedIncomingAttempts = counters.acceptedIncomingAttempts,
            successfulPairings = counters.successfulPairings,
            userRejects = counters.userRejects,
            verificationMismatches = counters.verificationMismatches,
            timeouts = counters.timeouts,
            transportFailures = counters.transportFailures,
            malformedPackets = counters.malformedPackets,
            signatureFailures = counters.signatureFailures,
            commitmentFailures = counters.commitmentFailures,
            confirmationMacFailures = counters.confirmationMacFailures,
            alreadyTrusted = counters.alreadyTrusted,
            identityKeyMismatches = counters.identityKeyMismatches,
            trustedPeerCount = trustedPeerStore.count(),
            identityKeySecurityLevel = localSigner.identity.securityLevel,
            lastError = lastError,
        )
    }

    private data class ManagedAttempt(
        val engine: PairingEngine,
        val endpoint: PairingTransportEndpoint,
        val remoteUntrustedAlias: String?,
        val createdAtMs: Long,
        val pendingOutbound: MutableMap<PairingMessageType, PendingOutbound> = LinkedHashMap(),
    )

    private data class PendingOutbound(
        val datagram: ByteArray,
        var retryIndex: Int,
        var nextRetryAtMs: Long,
    )

    private data class PairingCounters(
        var initiatedAttempts: Long = 0L,
        var acceptedIncomingAttempts: Long = 0L,
        var successfulPairings: Long = 0L,
        var userRejects: Long = 0L,
        var verificationMismatches: Long = 0L,
        var timeouts: Long = 0L,
        var transportFailures: Long = 0L,
        var malformedPackets: Long = 0L,
        var signatureFailures: Long = 0L,
        var commitmentFailures: Long = 0L,
        var confirmationMacFailures: Long = 0L,
        var alreadyTrusted: Long = 0L,
        var identityKeyMismatches: Long = 0L,
    )
}

private fun PairingMessageType.isRetryable(): Boolean = this in setOf(
    PairingMessageType.Commit,
    PairingMessageType.Response,
    PairingMessageType.Reveal,
    PairingMessageType.Confirm,
)
