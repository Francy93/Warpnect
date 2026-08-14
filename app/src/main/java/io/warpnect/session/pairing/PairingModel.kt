package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityPublicKey
import io.warpnect.session.identity.ImmutableBytes

object PairingBootstrapProtocol {
    const val VERSION: Int = 1
    const val MAX_DATAGRAM_BYTES: Int = 1_199
    const val HEADER_BYTES: Int = 28
    const val NONCE_BYTES: Int = 32
    const val HASH_BYTES: Int = 32
    const val MAX_PUBLIC_KEY_BYTES: Int = 256
    const val MAX_SIGNATURE_BYTES: Int = 128
    const val DEFAULT_PAIRING_WINDOW_MS: Long = 120_000L
    const val DEFAULT_ATTEMPT_TIMEOUT_MS: Long = 90_000L
    const val DEFAULT_USER_CONFIRMATION_TIMEOUT_MS: Long = 60_000L
    const val HARD_MAX_PENDING_ATTEMPTS: Int = 4
    val RETRY_DELAYS_MS: List<Long> = listOf(250L, 500L, 1_000L, 2_000L)
}

@ConsistentCopyVisibility
data class PairingAttemptId private constructor(
    val high: ULong,
    val low: ULong,
) {
    override fun toString(): String = "PairingAttemptId(${high.fixedWidthHex()}${low.fixedWidthHex()})"

    companion object {
        fun fromParts(high: ULong, low: ULong): PairingAttemptId? =
            if (high == 0uL && low == 0uL) null else PairingAttemptId(high, low)

        fun requireValid(high: ULong, low: ULong): PairingAttemptId = requireNotNull(fromParts(high, low)) {
            "PairingAttemptId cannot use the reserved all-zero value"
        }
    }
}

enum class PairingMessageType(
    val wireId: Int,
) {
    Commit(1),
    Response(2),
    Reveal(3),
    Confirm(4),
    Reject(5),
    Abort(6),
    ;

    companion object {
        fun fromWireId(value: Int): PairingMessageType? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PairingCryptoSuite(
    val wireId: Int,
) {
    P256Sha256(1),
    ;

    companion object {
        fun fromWireId(value: Int): PairingCryptoSuite? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PairingRole {
    Initiator,
    Responder,
}

data class PairingHash private constructor(
    private val value: ImmutableBytes,
) {
    fun bytes(): ByteArray = value.toByteArray()

    companion object {
        fun fromSha256(bytes: ByteArray): PairingHash? =
            bytes.takeIf { it.size == PairingBootstrapProtocol.HASH_BYTES }?.let {
                PairingHash(ImmutableBytes.copyOf(it))
            }

        fun requireSha256(bytes: ByteArray): PairingHash = requireNotNull(fromSha256(bytes)) {
            "Pairing hash must be exactly ${PairingBootstrapProtocol.HASH_BYTES} bytes"
        }
    }
}

data class PairingNonce private constructor(
    private val value: ImmutableBytes,
) {
    fun bytes(): ByteArray = value.toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): PairingNonce? =
            bytes.takeIf { it.size == PairingBootstrapProtocol.NONCE_BYTES }?.let {
                PairingNonce(ImmutableBytes.copyOf(it))
            }

        fun requireBytes(bytes: ByteArray): PairingNonce = requireNotNull(fromBytes(bytes)) {
            "Pairing nonce must be exactly ${PairingBootstrapProtocol.NONCE_BYTES} bytes"
        }
    }
}

data class EphemeralPublicKey private constructor(
    private val value: ImmutableBytes,
) {
    fun encodedSpki(): ByteArray = value.toByteArray()

    companion object {
        fun fromSpki(bytes: ByteArray): EphemeralPublicKey? =
            bytes.takeIf { it.isNotEmpty() && it.size <= PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES }?.let {
                EphemeralPublicKey(ImmutableBytes.copyOf(it))
            }

        fun requireSpki(bytes: ByteArray): EphemeralPublicKey = requireNotNull(fromSpki(bytes)) {
            "Ephemeral public key must be non-empty and at most ${PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES} bytes"
        }
    }
}

data class PairingSignature private constructor(
    private val value: ImmutableBytes,
) {
    fun der(): ByteArray = value.toByteArray()

    companion object {
        fun fromDer(bytes: ByteArray): PairingSignature? =
            bytes.takeIf { it.isNotEmpty() && it.size <= PairingBootstrapProtocol.MAX_SIGNATURE_BYTES }?.let {
                PairingSignature(ImmutableBytes.copyOf(it))
            }

        fun requireDer(bytes: ByteArray): PairingSignature = requireNotNull(fromDer(bytes)) {
            "Pairing signature must be non-empty and at most ${PairingBootstrapProtocol.MAX_SIGNATURE_BYTES} bytes"
        }
    }
}

data class PairingConfirmationMac private constructor(
    private val value: ImmutableBytes,
) {
    fun bytes(): ByteArray = value.toByteArray()

    companion object {
        fun fromSha256(bytes: ByteArray): PairingConfirmationMac? =
            bytes.takeIf { it.size == PairingBootstrapProtocol.HASH_BYTES }?.let {
                PairingConfirmationMac(ImmutableBytes.copyOf(it))
            }

        fun requireSha256(bytes: ByteArray): PairingConfirmationMac = requireNotNull(fromSha256(bytes)) {
            "Pairing confirmation MAC must be exactly ${PairingBootstrapProtocol.HASH_BYTES} bytes"
        }
    }
}

data class InitiatorRevealMaterial(
    val pairingAttemptId: PairingAttemptId,
    val initiatorDeviceId: DeviceId,
    val suite: PairingCryptoSuite,
    val initiatorIdentityPublicKey: IdentityPublicKey,
    val initiatorEphemeralPublicKey: EphemeralPublicKey,
    val initiatorNonce: PairingNonce,
)

sealed interface PairingBootstrapMessage {
    val type: PairingMessageType

    data class Commit(
        val suite: PairingCryptoSuite,
        val commitHash: PairingHash,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Commit
    }

    data class Response(
        val suite: PairingCryptoSuite,
        val responderDeviceId: DeviceId,
        val responderIdentityPublicKey: IdentityPublicKey,
        val responderEphemeralPublicKey: EphemeralPublicKey,
        val responderNonce: PairingNonce,
        val commitHash: PairingHash,
        val signature: PairingSignature,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Response
    }

    data class Reveal(
        val material: InitiatorRevealMaterial,
        val signature: PairingSignature,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Reveal
    }

    data class Confirm(
        val transcriptHash: PairingHash,
        val confirmationMac: PairingConfirmationMac,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Confirm
    }

    data class Reject(
        val reason: PairingRejectReason,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Reject
    }

    data class Abort(
        val reason: PairingAbortReason,
    ) : PairingBootstrapMessage {
        override val type: PairingMessageType = PairingMessageType.Abort
    }
}

data class PairingBootstrapPacket(
    val attemptId: PairingAttemptId,
    val message: PairingBootstrapMessage,
)

enum class PairingRejectReason(
    val wireId: Int,
) {
    UserRejected(1),
    VerificationMismatch(2),
    AlreadyTrusted(3),
    IdentityConflict(4),
    Busy(5),
    NotPairable(6),
    ;

    companion object {
        fun fromWireId(value: Int): PairingRejectReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PairingAbortReason(
    val wireId: Int,
) {
    ProtocolFailure(1),
    Timeout(2),
    TransportFailure(3),
    EndpointChanged(4),
    Closed(5),
    ;

    companion object {
        fun fromWireId(value: Int): PairingAbortReason? = entries.firstOrNull { it.wireId == value }
    }
}

enum class PairingEngineState {
    Idle,
    CommitSent,
    CommitReceived,
    ResponseSent,
    RevealSent,
    AwaitingUserConfirmation,
    LocalConfirmed,
    PeerConfirmed,
    Paired,
    Rejected,
    TimedOut,
    Failed,
    Closed,
}

enum class PairingError {
    None,
    InvalidPacket,
    UnexpectedMessage,
    CommitmentMismatch,
    SignatureInvalid,
    InvalidPeerIdentity,
    SelfPairing,
    AlreadyTrusted,
    PeerIdentityKeyChanged,
    IdentityBindingConflict,
    TrustStoreCapacityExceeded,
    ConfirmationMacInvalid,
    UserRejected,
    VerificationMismatch,
    RejectedByPeer,
    AbortedByPeer,
    PairingTransportTimeout,
    UserConfirmationTimeout,
    PairingWindowExpired,
    AttemptCapacityExceeded,
    EndpointMismatch,
    PairingTransportUnavailable,
    DiscoveryRouteUnavailable,
    TrustPersistenceFailure,
    Closed,
}

data class PairingVerificationPrompt(
    val attemptId: PairingAttemptId,
    val remoteUntrustedAlias: String?,
    val remoteDeviceId: DeviceId,
    val remoteIdentityFingerprint: io.warpnect.session.identity.IdentityFingerprint,
    val shortAuthenticationString: String,
    val state: PairingEngineState,
    val expiresAtMonotonicMs: Long,
)

data class PairingConfig(
    val maxActiveAttempts: Int = 1,
    val pairingWindowMs: Long = PairingBootstrapProtocol.DEFAULT_PAIRING_WINDOW_MS,
    val attemptTimeoutMs: Long = PairingBootstrapProtocol.DEFAULT_ATTEMPT_TIMEOUT_MS,
    val userConfirmationTimeoutMs: Long = PairingBootstrapProtocol.DEFAULT_USER_CONFIRMATION_TIMEOUT_MS,
) {
    init {
        require(maxActiveAttempts in 1..PairingBootstrapProtocol.HARD_MAX_PENDING_ATTEMPTS) {
            "maxActiveAttempts must be bounded by ${PairingBootstrapProtocol.HARD_MAX_PENDING_ATTEMPTS}"
        }
        require(pairingWindowMs > 0L)
        require(attemptTimeoutMs > 0L)
        require(userConfirmationTimeoutMs > 0L)
    }
}

private fun ULong.fixedWidthHex(): String = toString(16).padStart(16, '0')
