@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.pairing.EphemeralPublicKey

/** Session Handshake Protocol V1 is pre-session control traffic, not SCL. */
object SessionHandshakeProtocol {
    const val VERSION: Int = 1
    const val HEADER_BYTES: Int = 32
    const val MAX_DATAGRAM_BYTES: Int = 1_200
    const val NONCE_BYTES: Int = 32
    const val HASH_BYTES: Int = 32
    const val GCM_TAG_BYTES: Int = 16
    const val MAX_PUBLIC_KEY_BYTES: Int = 256
    const val MAX_SIGNATURE_BYTES: Int = 128
    const val DEFAULT_ATTEMPT_TIMEOUT_MS: Long = 8_000L
    const val DEFAULT_RESERVATION_MS: Long = 30_000L
    const val DEFAULT_COOKIE_VALIDITY_MS: Long = 30_000L
    const val DEFAULT_COOKIE_ROTATION_MS: Long = 600_000L
    const val HARD_MAX_INCOMING_ATTEMPTS: Int = 8
    const val DEFAULT_MAX_INCOMING_ATTEMPTS: Int = 4
    const val HARD_MAX_OUTGOING_ATTEMPTS: Int = 8
    const val DEFAULT_MAX_OUTGOING_ATTEMPTS: Int = 4
    const val RECENT_COMPLETED_CAPACITY: Int = 64
    const val RECENT_COMPLETED_RETENTION_MS: Long = 30_000L
    val RETRY_DELAYS_MS: LongArray = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L)
    val MAGIC: ByteArray = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'S'.code.toByte(), 'H'.code.toByte())
}

@ConsistentCopyVisibility
data class SessionHandshakeAttemptId private constructor(val high: ULong, val low: ULong) {
    fun bytes(): ByteArray = idBytes(high, low)
    override fun toString(): String = "SessionHandshakeAttemptId(redacted)"

    companion object {
        fun fromParts(high: ULong, low: ULong): SessionHandshakeAttemptId? =
            if (high == 0uL && low == 0uL) null else SessionHandshakeAttemptId(high, low)
        fun requireValid(high: ULong, low: ULong): SessionHandshakeAttemptId =
            requireNotNull(fromParts(high, low)) { "SessionHandshakeAttemptId cannot be zero" }
        fun fromBytes(bytes: ByteArray): SessionHandshakeAttemptId? =
            idFromBytes(bytes)?.let { fromParts(it.first, it.second) }
    }
}

class HandshakeNonce private constructor(private val value: ImmutableBytes) {
    fun bytes(): ByteArray = value.toByteArray()
    companion object {
        fun from(bytes: ByteArray): HandshakeNonce? = bytes.takeIf { it.size == SessionHandshakeProtocol.NONCE_BYTES }
            ?.let { HandshakeNonce(ImmutableBytes.copyOf(it)) }
        fun requireValid(bytes: ByteArray): HandshakeNonce =
            requireNotNull(from(bytes)) { "Handshake nonce must be 32 bytes" }
    }
}

/** Zero is legal only here and means an intentional no-discovery-binding route. */
data class DiscoveryPresenceBinding private constructor(private val bytes: ImmutableBytes) {
    fun bytes(): ByteArray = bytes.toByteArray()
    val isAbsent: Boolean get() = bytes().all { it == 0.toByte() }
    companion object {
        val None: DiscoveryPresenceBinding = DiscoveryPresenceBinding(ImmutableBytes.copyOf(ByteArray(16)))
        fun fromPresenceId(id: DiscoveryPresenceId): DiscoveryPresenceBinding =
            DiscoveryPresenceBinding(ImmutableBytes.copyOf(idBytes(id.high, id.low)))
        fun fromBytes(value: ByteArray): DiscoveryPresenceBinding? =
            value.takeIf { it.size == 16 }?.let { DiscoveryPresenceBinding(ImmutableBytes.copyOf(it)) }
    }
}

enum class HandshakeSide { Initiator, Responder }

enum class SessionHandshakeMessageType(val wireId: Int) {
    ClientHello(1),
    HelloRetry(2),
    ServerHello(3),
    ServerAuth(4),
    ClientAuth(5),
    ServerComplete(6),
    HandshakeReject(7),
    ;

    companion object {
        fun fromWireId(value: Int): SessionHandshakeMessageType? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SessionHandshakeRejectReason(val wireId: Int) {
    UnsupportedVersion(1),
    UnsupportedSuite(2),
    InvalidRole(3),
    AtCapacity(4),
    StalePresence(5),
    Busy(6),
    AuthenticationRejected(7),
    ;

    companion object {
        fun fromWireId(value: Int): SessionHandshakeRejectReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class SessionHandshakeState {
    Idle,
    ClientHelloSent,
    WaitingForRetryOrServerHello,
    CookieClientHelloSent,
    WaitingForServerAuth,
    ServerAuthenticated,
    ClientAuthSent,
    WaitingForServerComplete,
    Authenticated,
    Rejected,
    TimedOut,
    Failed,
    Closed,
}

enum class SessionHandshakeError {
    None,
    InvalidConfig,
    TransportUnavailable,
    TransportFailure,
    Timeout,
    UnsupportedVersion,
    UnsupportedSuite,
    InvalidRole,
    InvalidSessionId,
    SessionConflict,
    StaleDiscoveryPresence,
    CookieInvalid,
    CookieExpired,
    MalformedDatagram,
    UnexpectedMessage,
    UnexpectedSequence,
    InvalidEphemeralKey,
    KeyAgreementFailure,
    DecryptFailure,
    PeerNotTrusted,
    UnexpectedTrustedPeer,
    TrustedIdentityMismatch,
    SignatureFailure,
    FinishedFailure,
    CompletionFailure,
    AtCapacity,
    DuplicatePeerSessionNotAllowed,
    AdmissionFailure,
    EndpointMismatch,
    LocalIdentityInconsistent,
    SecretDestroyed,
    Closed,
}

sealed interface ExpectedPeerConstraint {
    data class ExactTrustedPeer(val deviceId: DeviceId) : ExpectedPeerConstraint
    data object AnyTrustedPeer : ExpectedPeerConstraint
}

data class HandshakeTransportEndpoint private constructor(
    private val address: ImmutableBytes,
    val port: Int,
) {
    fun addressBytes(): ByteArray = address.toByteArray()
    companion object {
        fun from(addressBytes: ByteArray, port: Int): HandshakeTransportEndpoint? =
            addressBytes.takeIf { it.size == 4 || it.size == 16 }?.takeIf { port in 1..65_535 }?.let {
                HandshakeTransportEndpoint(ImmutableBytes.copyOf(it), port)
            }
        fun requireValid(addressBytes: ByteArray, port: Int): HandshakeTransportEndpoint =
            requireNotNull(from(addressBytes, port)) { "Handshake endpoint requires IPv4/IPv6 bytes and a valid port" }
    }
}

data class SessionHandshakeHeader(
    val messageType: SessionHandshakeMessageType,
    val flags: Int,
    val attemptId: SessionHandshakeAttemptId,
    val messageSequence: Int,
    val payloadLength: Int,
) {
    val hasRetryCookie: Boolean get() = flags and 0x0001 != 0
    val encryptedPayload: Boolean get() = flags and 0x0002 != 0
}

sealed interface SessionHandshakeMessage {
    val type: SessionHandshakeMessageType
    data class ClientHello(
        val suite: Int,
        val sessionId: SessionId,
        val generation: SessionGeneration,
        val initiatorRole: SessionRole,
        val responderRole: SessionRole,
        val targetPresence: DiscoveryPresenceBinding,
        val nonce: HandshakeNonce,
        val ephemeralPublicKey: EphemeralPublicKey,
        val retryCookie: ImmutableBytes? = null,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.ClientHello
    }
    data class HelloRetry(
        val cookie: ImmutableBytes,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.HelloRetry
    }
    data class ServerHello(
        val suite: Int,
        val sessionId: SessionId,
        val generation: SessionGeneration,
        val initiatorRole: SessionRole,
        val responderRole: SessionRole,
        val nonce: HandshakeNonce,
        val ephemeralPublicKey: EphemeralPublicKey,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.ServerHello
    }
    data class ServerAuth(
        val encryptedRecord: ImmutableBytes,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.ServerAuth
    }
    data class ClientAuth(
        val encryptedRecord: ImmutableBytes,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.ClientAuth
    }
    data class ServerComplete(
        val encryptedRecord: ImmutableBytes,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.ServerComplete
    }
    data class Reject(
        val reason: SessionHandshakeRejectReason,
    ) : SessionHandshakeMessage {
        override val type = SessionHandshakeMessageType.HandshakeReject
    }
}

data class SessionHandshakePacket(val header: SessionHandshakeHeader, val message: SessionHandshakeMessage, val datagram: ByteArray)

class AuthenticatedSessionRootSecret internal constructor(private var bytes: ByteArray?) : AutoCloseable {
    fun <T> withSecretBytes(block: (ByteArray) -> T): T {
        val value = bytes ?: throw IllegalStateException(SessionHandshakeError.SecretDestroyed.name)
        val temporary = value.copyOf()
        return try {
            block(temporary)
        } finally {
            temporary.fill(0)
        }
    }
    val isDestroyed: Boolean get() = bytes == null
    override fun close() {
        bytes?.fill(0)
        bytes = null
    }
    override fun toString(): String = "AuthenticatedSessionRootSecret(redacted)"
}

data class AuthenticatedSessionBootstrap(
    val sessionId: SessionId,
    val generation: SessionGeneration,
    val localDeviceId: DeviceId,
    val remoteDeviceId: DeviceId,
    val localRole: SessionRole,
    val remoteRole: SessionRole,
    val attemptId: SessionHandshakeAttemptId,
    val authenticatedTranscriptHash: ImmutableBytes,
    val endpoint: HandshakeTransportEndpoint,
    val rootSecret: AuthenticatedSessionRootSecret,
    val admissionReservation: AuthenticatedSessionAdmissionReservation? = null,
)

/** Bounded reservation only; it neither starts channels nor changes a session to Running. */
interface AuthenticatedSessionAdmissionReservation : AutoCloseable {
    val sessionId: SessionId
    val peerDeviceId: DeviceId
    val expiresAtMonotonicMs: Long
    override fun close()
}

data class SessionHandshakeAdmissionResult(
    val error: SessionHandshakeError,
    val reservation: AuthenticatedSessionAdmissionReservation? = null,
)

fun interface SessionHandshakeAdmission {
    fun reserve(
        sessionId: SessionId,
        peerDeviceId: DeviceId,
        generation: SessionGeneration,
    ): SessionHandshakeAdmissionResult
}

interface SessionHandshakeTransport : AutoCloseable {
    fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?)
    fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean
    override fun close()
}

private fun idBytes(high: ULong, low: ULong): ByteArray = ByteArray(16).also { bytes ->
    repeat(8) { index -> bytes[index] = (high shr (56 - index * 8)).toByte() }
    repeat(8) { index -> bytes[index + 8] = (low shr (56 - index * 8)).toByte() }
}

private fun idFromBytes(bytes: ByteArray): Pair<ULong, ULong>? {
    if (bytes.size != 16) return null
    var high = 0uL
    var low = 0uL
    repeat(8) { index -> high = (high shl 8) or (bytes[index].toInt() and 0xff).toULong() }
    repeat(8) { index -> low = (low shl 8) or (bytes[index + 8].toInt() and 0xff).toULong() }
    return high to low
}
