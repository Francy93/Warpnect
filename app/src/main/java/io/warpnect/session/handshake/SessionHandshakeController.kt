@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.SessionError
import io.warpnect.session.SessionManager
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCryptoProvider
import io.warpnect.session.trust.TrustedPeerStore

data class SessionHandshakeConfig(
    val maxIncomingAttempts: Int = SessionHandshakeProtocol.DEFAULT_MAX_INCOMING_ATTEMPTS,
    val maxOutgoingAttempts: Int = SessionHandshakeProtocol.DEFAULT_MAX_OUTGOING_ATTEMPTS,
    val attemptTimeoutMs: Long = SessionHandshakeProtocol.DEFAULT_ATTEMPT_TIMEOUT_MS,
    val admissionReservationMs: Long = SessionHandshakeProtocol.DEFAULT_RESERVATION_MS,
) {
    init {
        require(
            maxIncomingAttempts in 1..SessionHandshakeProtocol.HARD_MAX_INCOMING_ATTEMPTS &&
                maxOutgoingAttempts in 1..SessionHandshakeProtocol.HARD_MAX_OUTGOING_ATTEMPTS,
        )
        require(attemptTimeoutMs > 0 && admissionReservationMs > 0)
    }
}

enum class SessionHandshakeControllerState { Stopped, Running, Closed }

data class SessionHandshakeSnapshot(
    val state: SessionHandshakeControllerState,
    val activeIncomingAttempts: Int,
    val activeOutgoingAttempts: Int,
    val successfulHandshakes: Long,
    val cookieRetriesIssued: Long,
    val cookieRetriesValidated: Long,
    val cookieRejects: Long,
    val malformedDatagrams: Long,
    val unexpectedMessages: Long,
    val transportRetries: Long,
    val timeouts: Long,
    val serverAuthFailures: Long,
    val clientAuthFailures: Long,
    val finishedFailures: Long,
    val decryptFailures: Long,
    val untrustedPeerFailures: Long,
    val identityMismatchFailures: Long,
    val capacityRejects: Long,
    val recentCompletedCacheSize: Int,
    val lastSuccessfulPeer: io.warpnect.session.DeviceId?,
    val lastSessionId: io.warpnect.session.SessionId?,
    val lastHandshakeDurationMs: Long?,
    val lastError: SessionHandshakeError,
)

fun interface SessionHandshakeEventListener {
    fun onAuthenticated(bootstrap: AuthenticatedSessionBootstrap)
}
fun interface CurrentDiscoveryPresenceProvider {
    fun currentPresenceId(): DiscoveryPresenceId?
}

/**
 * Bounded, externally-clocked runtime owner. Android supplies a serialized HandlerThread and a
 * UDP adapter; this class itself creates no workers or sockets.
 */
class SessionHandshakeController(
    private val transport: SessionHandshakeTransport,
    private val localSigner: LocalDeviceIdentitySigner,
    private val trustedPeers: TrustedPeerStore,
    private val sessionManager: SessionManager,
    private val crypto: PairingCryptoProvider,
    private val config: SessionHandshakeConfig = SessionHandshakeConfig(),
    private val clock: SessionHandshakeMonotonicClock = SystemSessionHandshakeMonotonicClock,
    private val presenceProvider: CurrentDiscoveryPresenceProvider = CurrentDiscoveryPresenceProvider { null },
    private val eventListener: SessionHandshakeEventListener? = null,
) : AutoCloseable {
    private val lock = Any()
    private val cookieManager = SessionHandshakeCookieManager(crypto, clock)
    private val active = LinkedHashMap<SessionHandshakeAttemptId, ManagedAttempt>()
    private val completed = LinkedHashMap<SessionHandshakeAttemptId, CompletedAttempt>()
    private var controllerState = SessionHandshakeControllerState.Running
    private var counters = Counters()
    private var lastPeer: io.warpnect.session.DeviceId? = null
    private var lastSession: io.warpnect.session.SessionId? = null
    private var lastDurationMs: Long? = null
    private var lastError = SessionHandshakeError.None

    init {
        transport.setDatagramListener(::receive)
    }

    fun startInitiator(
        endpoint: HandshakeTransportEndpoint,
        sessionId: io.warpnect.session.SessionId,
        attemptId: SessionHandshakeAttemptId = nextAttemptId(),
        targetPresence: DiscoveryPresenceBinding = DiscoveryPresenceBinding.None,
        expectedPeer: ExpectedPeerConstraint = ExpectedPeerConstraint.AnyTrustedPeer,
    ): SessionHandshakeEngineResult = synchronized(lock) {
        if (controllerState != SessionHandshakeControllerState.Running) {
            return@synchronized SessionHandshakeEngineResult(
                SessionHandshakeError.Closed,
            )
        }
        expireCompletedLocked()
        if (active.values.count { !it.incoming } >= config.maxOutgoingAttempts) {
            return@synchronized record(SessionHandshakeEngineResult(SessionHandshakeError.AtCapacity))
        }
        val started = SessionHandshakeEngine.initiate(
            attemptId,
            endpoint,
            sessionId,
            targetPresence = targetPresence,
            localSigner = localSigner,
            trustedPeers = trustedPeers,
            crypto = crypto,
            expectedPeer = expectedPeer,
        )
        val engine = started.engine ?: return@synchronized record(started.result)
        val managed = ManagedAttempt(engine, endpoint, incoming = false, startedAtMs = now())
        active[attemptId] = managed
        processLocked(managed, started.result)
    }

    fun receive(endpoint: HandshakeTransportEndpoint, datagram: ByteArray) = synchronized(lock) {
        if (controllerState != SessionHandshakeControllerState.Running || datagram.size > SessionHandshakeProtocol.MAX_DATAGRAM_BYTES) return@synchronized
        expireCompletedLocked()
        val (packet, parseError) = SessionHandshakeCodec.decode(datagram)
        if (packet == null) {
            counters.malformedDatagrams += 1
            lastError = parseError
            return@synchronized
        }
        completed[packet.header.attemptId]?.let { done ->
            if (done.endpoint == endpoint && packet.message is SessionHandshakeMessage.ClientAuth) {
                transport.send(
                    endpoint,
                    done.serverComplete,
                )
            }
            return@synchronized
        }
        val existing = active[packet.header.attemptId]
        if (existing != null) {
            processLocked(existing, existing.engine.receive(endpoint, datagram))
            return@synchronized
        }
        val hello = packet.message as? SessionHandshakeMessage.ClientHello ?: run {
            counters.unexpectedMessages += 1
            return@synchronized
        }
        when (packet.header.messageSequence) {
            0 -> issueCookieLocked(endpoint, packet, hello)
            2 -> acceptCookieLocked(endpoint, packet, hello)
            else -> {
                counters.unexpectedMessages += 1
                lastError = SessionHandshakeError.UnexpectedSequence
            }
        }
    }

    fun advance() = synchronized(lock) {
        if (controllerState != SessionHandshakeControllerState.Running) return@synchronized
        val now = now()
        expireCompletedLocked()
        active.values.toList().forEach { managed ->
            if (now - managed.startedAtMs >= config.attemptTimeoutMs) {
                managed.engine.close()
                active.remove(managed.engine.attemptId)
                counters.timeouts += 1
                lastError = SessionHandshakeError.Timeout
            } else if (now >= managed.nextRetryAtMs) {
                if (managed.retryIndex >= SessionHandshakeProtocol.RETRY_DELAYS_MS.size) {
                    managed.engine.close()
                    active.remove(managed.engine.attemptId)
                    counters.timeouts += 1
                    lastError = SessionHandshakeError.Timeout
                } else {
                    managed.outbound.forEach { transport.send(managed.endpoint, it) }
                    counters.transportRetries += 1
                    managed.nextRetryAtMs = now + SessionHandshakeProtocol.RETRY_DELAYS_MS[managed.retryIndex++]
                }
            }
        }
    }

    fun snapshot(): SessionHandshakeSnapshot = synchronized(lock) {
        expireCompletedLocked()
        SessionHandshakeSnapshot(
            controllerState,
            active.values.count { it.incoming }, active.values.count { !it.incoming }, counters.successful, counters.cookieIssued,
            counters.cookieValidated, counters.cookieRejects, counters.malformedDatagrams, counters.unexpectedMessages, counters.transportRetries,
            counters.timeouts, counters.serverAuthFailures, counters.clientAuthFailures, counters.finishedFailures, counters.decryptFailures,
            counters.untrustedPeerFailures, counters.identityMismatchFailures, counters.capacityRejects, completed.size, lastPeer, lastSession,
            lastDurationMs, lastError,
        )
    }

    fun nextWakeAtMonotonicMs(): Long? = synchronized(lock) {
        if (controllerState != SessionHandshakeControllerState.Running) return@synchronized null
        val deadlines = active.values.flatMap {
            listOf(it.nextRetryAtMs.takeIf { value -> value > 0L }, it.startedAtMs + config.attemptTimeoutMs)
        }
        deadlines.filterNotNull().minOrNull()
    }

    private fun issueCookieLocked(
        endpoint: HandshakeTransportEndpoint,
        packet: SessionHandshakePacket,
        hello: SessionHandshakeMessage.ClientHello,
    ) {
        if (!validHello(hello) || !presenceMatches(hello.targetPresence)) {
            sendReject(
                endpoint,
                packet.header.attemptId,
                if (!validHello(
                        hello,
                    )
                ) {
                    SessionHandshakeRejectReason.InvalidRole
                } else {
                    SessionHandshakeRejectReason.StalePresence
                },
            )
            return
        }
        val cookie = cookieManager.issue(endpoint, packet) ?: return
        val retry = SessionHandshakeCodec.encode(packet.header.attemptId, 1, SessionHandshakeMessage.HelloRetry(cookie)) ?: return
        transport.send(endpoint, retry)
        counters.cookieIssued += 1
    }

    private fun acceptCookieLocked(
        endpoint: HandshakeTransportEndpoint,
        packet: SessionHandshakePacket,
        hello: SessionHandshakeMessage.ClientHello,
    ) {
        if (!validHello(hello) || !presenceMatches(hello.targetPresence)) {
            counters.cookieRejects += 1
            return
        }
        if (active.values.count { it.incoming } >= config.maxIncomingAttempts) {
            sendReject(endpoint, packet.header.attemptId, SessionHandshakeRejectReason.Busy)
            counters.capacityRejects += 1
            return
        }
        val initial = SessionHandshakeCodec.encode(packet.header.attemptId, 0, hello.copy(retryCookie = null))?.let(SessionHandshakeCodec::decode)?.first
            ?: run {
                counters.malformedDatagrams += 1
                return
            }
        val retry = SessionHandshakeCodec.encode(packet.header.attemptId, 1, SessionHandshakeMessage.HelloRetry(checkNotNull(hello.retryCookie)))?.let(SessionHandshakeCodec::decode)?.first
            ?: run {
                counters.malformedDatagrams += 1
                return
            }
        val cookieError = cookieManager.validate(endpoint, initial, packet)
        if (cookieError != SessionHandshakeError.None) {
            counters.cookieRejects += 1
            lastError = cookieError
            return
        }
        counters.cookieValidated += 1
        val admission = SessionManagerHandshakeAdmission(sessionManager, clock, config.admissionReservationMs)
        val started = SessionHandshakeEngine.respond(
            endpoint,
            initial,
            retry,
            packet,
            localSigner,
            trustedPeers,
            crypto,
            admission,
        )
        val engine = started.engine ?: run {
            record(started.result)
            return
        }
        val managed = ManagedAttempt(engine, endpoint, incoming = true, startedAtMs = now())
        active[engine.attemptId] = managed
        processLocked(managed, started.result)
    }

    private fun processLocked(
        managed: ManagedAttempt,
        result: SessionHandshakeEngineResult,
    ): SessionHandshakeEngineResult {
        record(result)
        val sends = result.actions.filterIsInstance<SessionHandshakeEngineAction.Send>().map { it.datagram.copyOf() }
        if (sends.isNotEmpty()) {
            managed.outbound = sends
            managed.retryIndex = 0
            managed.nextRetryAtMs = now() + SessionHandshakeProtocol.RETRY_DELAYS_MS.first()
            sends.forEach {
                if (!transport.send(
                        managed.endpoint,
                        it,
                    )
                ) {
                    lastError = SessionHandshakeError.TransportFailure
                }
            }
        }
        result.actions.filterIsInstance<SessionHandshakeEngineAction.Completed>().forEach { completedAction ->
            val bootstrap = completedAction.bootstrap
            counters.successful += 1
            lastPeer = bootstrap.remoteDeviceId
            lastSession = bootstrap.sessionId
            lastDurationMs = now() - managed.startedAtMs
            if (managed.incoming && managed.outbound.isNotEmpty()) {
                trimCompletedToCapacityLocked()
                completed[managed.engine.attemptId] = CompletedAttempt(
                    managed.endpoint,
                    bootstrap.sessionId,
                    managed.outbound.last().copyOf(),
                    now() + SessionHandshakeProtocol.RECENT_COMPLETED_RETENTION_MS,
                )
            }
            active.remove(managed.engine.attemptId)
            eventListener?.onAuthenticated(bootstrap)
        }
        if (result.error != SessionHandshakeError.None || managed.engine.state in setOf(SessionHandshakeState.Failed, SessionHandshakeState.Rejected, SessionHandshakeState.Closed)) {
            active.remove(managed.engine.attemptId)
        }
        return result
    }

    private fun record(result: SessionHandshakeEngineResult): SessionHandshakeEngineResult {
        if (result.error == SessionHandshakeError.None) return result
        lastError = result.error
        when (result.error) {
            SessionHandshakeError.SignatureFailure -> counters.serverAuthFailures += 1
            SessionHandshakeError.FinishedFailure -> counters.finishedFailures += 1
            SessionHandshakeError.DecryptFailure -> counters.decryptFailures += 1
            SessionHandshakeError.PeerNotTrusted -> counters.untrustedPeerFailures += 1
            SessionHandshakeError.TrustedIdentityMismatch -> counters.identityMismatchFailures += 1
            SessionHandshakeError.AtCapacity, SessionHandshakeError.DuplicatePeerSessionNotAllowed -> counters.capacityRejects += 1
            else -> Unit
        }
        return result
    }

    private fun validHello(hello: SessionHandshakeMessage.ClientHello): Boolean =
        hello.suite == 1 && hello.generation == io.warpnect.session.SessionGeneration.Initial && hello.initiatorRole == io.warpnect.session.SessionRole.Client && hello.responderRole == io.warpnect.session.SessionRole.Host
    private fun presenceMatches(binding: DiscoveryPresenceBinding): Boolean {
        if (binding.isAbsent) return true
        val id = presenceProvider.currentPresenceId() ?: return false
        val current = ByteArray(16).also { bytes ->
            repeat(8) { index ->
                bytes[index] = (id.high shr (56 - index * 8)).toByte()
                bytes[index + 8] = (id.low shr (56 - index * 8)).toByte()
            }
        }
        return binding.bytes().contentEquals(current)
    }
    private fun sendReject(
        endpoint: HandshakeTransportEndpoint,
        attemptId: SessionHandshakeAttemptId,
        reason: SessionHandshakeRejectReason,
    ) {
        SessionHandshakeCodec.encode(
            attemptId,
            0,
            SessionHandshakeMessage.Reject(reason),
        )?.let { transport.send(endpoint, it) }
    }
    private fun expireCompletedLocked() {
        val now = now()
        completed.entries.removeIf { it.value.expiresAtMs <= now }
    }
    private fun trimCompletedToCapacityLocked() {
        while (completed.size >= SessionHandshakeProtocol.RECENT_COMPLETED_CAPACITY) {
            val eldest = completed.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }
    private fun now(): Long = clock.nowMs().coerceAtLeast(0L)
    private fun nextAttemptId(): SessionHandshakeAttemptId {
        while (true) {
            val value = crypto.randomBytes(16)
            SessionHandshakeAttemptId.fromBytes(value)?.let { return it }
        }
    }
    override fun close() {
        synchronized(lock) {
            if (controllerState == SessionHandshakeControllerState.Closed) return
            active.values.forEach { it.engine.close() }
            active.clear()
            completed.clear()
            cookieManager.close()
            transport.setDatagramListener(null)
            transport.close()
            controllerState = SessionHandshakeControllerState.Closed
        }
    }

    private data class ManagedAttempt(val engine: SessionHandshakeEngine, val endpoint: HandshakeTransportEndpoint, val incoming: Boolean, val startedAtMs: Long, var outbound: List<ByteArray> = emptyList(), var retryIndex: Int = 0, var nextRetryAtMs: Long = 0L)
    private data class CompletedAttempt(
        val endpoint: HandshakeTransportEndpoint,
        val sessionId: io.warpnect.session.SessionId,
        val serverComplete: ByteArray,
        val expiresAtMs: Long,
    )
    private data class Counters(
        var successful: Long = 0,
        var cookieIssued: Long = 0,
        var cookieValidated: Long = 0,
        var cookieRejects: Long = 0,
        var malformedDatagrams: Long = 0,
        var unexpectedMessages: Long = 0,
        var transportRetries: Long = 0,
        var timeouts: Long = 0,
        var serverAuthFailures: Long = 0,
        var clientAuthFailures: Long = 0,
        var finishedFailures: Long = 0,
        var decryptFailures: Long = 0,
        var untrustedPeerFailures: Long = 0,
        var identityMismatchFailures: Long = 0,
        var capacityRejects: Long = 0,
    )
}

private class SessionManagerHandshakeAdmission(
    private val manager: SessionManager,
    private val clock: SessionHandshakeMonotonicClock,
    private val lifetimeMs: Long,
) : SessionHandshakeAdmission {
    override fun reserve(
        sessionId: io.warpnect.session.SessionId,
        peerDeviceId: io.warpnect.session.DeviceId,
        generation: io.warpnect.session.SessionGeneration,
    ): SessionHandshakeAdmissionResult {
        val result = manager.reserveAuthenticatedAdmission(sessionId, peerDeviceId, generation, lifetimeMs * 1_000L)
        val reservation = result.reservation ?: return SessionHandshakeAdmissionResult(result.error.toHandshakeError())
        return SessionHandshakeAdmissionResult(
            SessionHandshakeError.None,
            object : AuthenticatedSessionAdmissionReservation {
                override val sessionId = reservation.sessionId
                override val peerDeviceId = reservation.peerDeviceId
                private var expiresAtMs: Long = clock.nowMs().coerceAtLeast(0L) + lifetimeMs
                override val expiresAtMonotonicMs: Long get() = expiresAtMs

                override fun renew(lifetimeMs: Long): Boolean {
                    if (lifetimeMs <= 0L) return false
                    val renewed = manager.renewAuthenticatedAdmission(sessionId, lifetimeMs * 1_000L)
                    if (!renewed.isSuccess) return false
                    expiresAtMs = clock.nowMs().coerceAtLeast(0L) + lifetimeMs
                    return true
                }

                override fun close() {
                    manager.releaseAuthenticatedAdmission(sessionId)
                }
            },
        )
    }
}

private fun SessionError.toHandshakeError(): SessionHandshakeError = when (this) {
    SessionError.SessionCapacityExceeded -> SessionHandshakeError.AtCapacity
    SessionError.DuplicatePeerSessionNotAllowed -> SessionHandshakeError.DuplicatePeerSessionNotAllowed
    SessionError.Closed -> SessionHandshakeError.Closed
    else -> SessionHandshakeError.AdmissionFailure
}
