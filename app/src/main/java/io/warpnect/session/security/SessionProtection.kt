package io.warpnect.session.security

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionBounds
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SessionControlProtectionRuntime
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.HandshakeTransportEndpoint

/** Session Packet Protection V1 is an outer WNSD envelope, never a change to SCL packet formats. */
object SessionProtectionProtocol {
    const val VERSION = 1
    const val ENVELOPE_HEADER_BYTES = 28
    const val GCM_TAG_BYTES = 16
    const val OVERHEAD_BYTES = ENVELOPE_HEADER_BYTES + GCM_TAG_BYTES
    const val ROOT_SECRET_BYTES = 32
    const val TRANSCRIPT_HASH_BYTES = 32
    const val DEFAULT_UDP_BUDGET = 1_200
    const val DEFAULT_REPLAY_WINDOW = 4_096
    const val MIN_REPLAY_WINDOW = 64
    const val MAX_REPLAY_WINDOW = 16_384
    const val MAX_CONTEXTS = 64
    const val DEFAULT_MAX_PACKETS_PER_EPOCH = 1L shl 20
    const val DEFAULT_PREVIOUS_EPOCH_RETENTION_US = 2_000_000L
}

enum class SessionProtectionError(val nativeCode: Int) {
    None(0),
    InvalidConfig(1),
    InvalidRootSecret(2),
    RootSecretAlreadyConsumed(3),
    ContextCapacityExceeded(4),
    ContextIdCollision(5),
    UnknownContext(6),
    InvalidEnvelope(7),
    UnsupportedProtectionVersion(8),
    DatagramTooSmall(9),
    DatagramTooLarge(10),
    EndpointMismatch(11),
    ReplayDuplicate(12),
    ReplayTooOld(13),
    InvalidEpoch(14),
    FutureEpoch(15),
    AuthFailure(16),
    CryptoFailure(17),
    PacketNumberExhausted(18),
    EpochExhausted(19),
    SecureDatagramBudgetTooSmall(20),
    Busy(21),
    Closed(22),
    SecretDestroyed(23),
    ;

    companion object {
        fun fromNative(code: Int): SessionProtectionError = entries.firstOrNull { it.nativeCode == code }
            ?: CryptoFailure
    }
}

sealed interface ProtectionScope {
    data object SessionControl : ProtectionScope
    data class Channel(val channelId: ChannelId) : ProtectionScope
}

data class ProtectionContextIds(
    val sendContextId: Long,
    val receiveContextId: Long,
)

data class SessionProtectionConfig(
    val maxSecureDatagramSize: Int = SessionProtectionProtocol.DEFAULT_UDP_BUDGET,
    val replayWindowSize: Int = SessionProtectionProtocol.DEFAULT_REPLAY_WINDOW,
    val maxContexts: Int = SessionProtectionProtocol.MAX_CONTEXTS,
    val maxPacketsPerEpoch: Long = SessionProtectionProtocol.DEFAULT_MAX_PACKETS_PER_EPOCH,
    val previousEpochRetentionUs: Long = SessionProtectionProtocol.DEFAULT_PREVIOUS_EPOCH_RETENTION_US,
    val maxProtectedRetransmissionAgeUs: Long = 0L,
    val maxRuntimes: Int = SessionBounds.HARD_MAX_SESSIONS,
) {
    val maxInnerSclDatagramSize: Int get() = maxSecureDatagramSize - SessionProtectionProtocol.OVERHEAD_BYTES

    fun validate(): SessionProtectionError = when {
        maxSecureDatagramSize <= SessionProtectionProtocol.OVERHEAD_BYTES + 21 ->
            SessionProtectionError.SecureDatagramBudgetTooSmall
        replayWindowSize !in SessionProtectionProtocol.MIN_REPLAY_WINDOW..SessionProtectionProtocol.MAX_REPLAY_WINDOW ->
            SessionProtectionError.InvalidConfig
        maxContexts !in 1..SessionProtectionProtocol.MAX_CONTEXTS -> SessionProtectionError.InvalidConfig
        maxPacketsPerEpoch <= 0L || previousEpochRetentionUs <= 0L -> SessionProtectionError.InvalidConfig
        maxProtectedRetransmissionAgeUs !in 0L..previousEpochRetentionUs -> SessionProtectionError.InvalidConfig
        maxRuntimes !in 1..SessionBounds.HARD_MAX_SESSIONS -> SessionProtectionError.InvalidConfig
        else -> SessionProtectionError.None
    }
}

data class SessionProtectionSnapshot(
    val activeContexts: Long,
    val protectedPackets: Long,
    val decryptedPackets: Long,
    val replayDrops: Long,
    val tooOldDrops: Long,
    val unknownContextDrops: Long,
    val endpointFilterDrops: Long,
    val authFailures: Long,
    val keyUpdatesSent: Long,
    val keyUpdatesAccepted: Long,
    val currentSendEpoch: Long,
    val currentReceiveEpoch: Long,
    val lastError: SessionProtectionError,
)

interface SessionProtectionRuntime : SessionControlProtectionRuntime {
    val sessionId: SessionId
    val sessionControlContext: ProtectionContextIds
    val maxInnerSclDatagramSize: Int

    fun createChannelContext(channelId: ChannelId): SessionProtectionContextResult
    fun createChannelContext(
        channelId: ChannelId,
        expectedRemoteEndpoint: HandshakeTransportEndpoint,
    ): SessionProtectionContextResult = createChannelContext(channelId)
    fun destroyChannelContext(channelId: ChannelId): SessionProtectionError

    /** RFC-005H control-plane rebind: only the endpoint filter changes, never channel key state. */
    fun rebindChannelEndpoint(channelId: ChannelId, endpoint: HandshakeTransportEndpoint): SessionProtectionError =
        SessionProtectionError.InvalidConfig
    override fun unprotectCandidateSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult = unprotectSessionControl(sourceEndpoint, protectedDatagram, nowUs)

    override fun rebindSessionControlEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
        SessionProtectionError.InvalidConfig

    /** Zero means no authenticated datagram has been received by this generation yet. */
    fun lastAuthenticatedReceiveMonotonicUs(): Long = 0L
    fun snapshot(): SessionProtectionSnapshot
    override fun protectSessionControl(
        sequenceNumber: Long,
        timestampUs: Long,
        payload: ByteArray,
    ): SecureSessionControlSendResult

    override fun unprotectSessionControl(
        sourceEndpoint: HandshakeTransportEndpoint,
        protectedDatagram: ByteArray,
        nowUs: Long,
    ): SessionControlUnprotectResult

    override val maxPayloadBytes: Int
        get() = maxInnerSclDatagramSize - 21
    override fun close()
}

data class SessionProtectionContextResult(
    val error: SessionProtectionError,
    val contextIds: ProtectionContextIds? = null,
) {
    val isSuccess: Boolean get() = error == SessionProtectionError.None && contextIds != null
}

data class SessionProtectionCreationResult(
    val error: SessionProtectionError,
    val runtime: SessionProtectionRuntime? = null,
) {
    val isSuccess: Boolean get() = error == SessionProtectionError.None && runtime != null
}

/** Cold-path factory. Packet protect/unprotect calls never cross this Kotlin boundary. */
fun interface SessionProtectionRuntimeFactory {
    fun create(
        rootSecret: ByteArray,
        bootstrap: AuthenticatedSessionBootstrap,
        config: SessionProtectionConfig,
    ): SessionProtectionCreationResult
}

/**
 * Owns a bounded set of native session-protection runtimes. It consumes an authenticated root
 * exactly once and leaves the RFC-005D admission reservation intact on success for RFC-005F.
 */
class SessionProtectionController(
    private val factory: SessionProtectionRuntimeFactory,
    private val config: SessionProtectionConfig = SessionProtectionConfig(),
) : AutoCloseable {
    private val lock = Any()
    private val runtimes = LinkedHashMap<SessionId, SessionProtectionRuntime>()
    private var closed = false

    fun createSessionProtection(bootstrap: AuthenticatedSessionBootstrap): SessionProtectionCreationResult {
        return synchronized(lock) {
            if (closed) return@synchronized fail(bootstrap, SessionProtectionError.Closed)
            config.validate().takeIf { it != SessionProtectionError.None }?.let {
                return@synchronized fail(bootstrap, it)
            }
            if (runtimes.containsKey(bootstrap.sessionId) || runtimes.size >= config.maxRuntimes) {
                return@synchronized fail(bootstrap, SessionProtectionError.Busy)
            }
            val result = try {
                bootstrap.rootSecret.withSecretBytes { root -> factory.create(root, bootstrap, config) }
            } catch (_: IllegalStateException) {
                SessionProtectionCreationResult(SessionProtectionError.SecretDestroyed)
            } finally {
                bootstrap.rootSecret.close()
            }
            if (!result.isSuccess) return@synchronized fail(bootstrap, result.error)
            val runtime = requireNotNull(result.runtime)
            runtimes[bootstrap.sessionId] = runtime
            result
        }
    }

    fun snapshotCount(): Int = synchronized(lock) { runtimes.size }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            runtimes.values.forEach { it.close() }
            runtimes.clear()
            closed = true
        }
    }

    private fun fail(
        bootstrap: AuthenticatedSessionBootstrap,
        error: SessionProtectionError,
    ): SessionProtectionCreationResult {
        bootstrap.rootSecret.close()
        bootstrap.admissionReservation?.close()
        return SessionProtectionCreationResult(error)
    }
}

internal fun SessionRole.toNativeProtectionRole(): Int = when (this) {
    SessionRole.Client -> 1
    SessionRole.Host -> 2
}

internal fun SessionId.toCanonicalBytes(): ByteArray = ByteArray(16).also { bytes ->
    repeat(8) { index -> bytes[index] = (high shr (56 - index * 8)).toByte() }
    repeat(8) { index -> bytes[index + 8] = (low shr (56 - index * 8)).toByte() }
}
