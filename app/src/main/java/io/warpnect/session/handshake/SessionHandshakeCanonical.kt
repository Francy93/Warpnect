@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.pairing.PairingCryptoProvider
import io.warpnect.session.pairing.PairingSignature

/** Canonical, ordered inputs for all RFC-005D hashes, signatures, MACs, and HKDF labels. */
object SessionHandshakeCanonical {
    private val transcriptDomain = "Warpnect Session Transcript v1".encodeToByteArray()
    private val serverAuthContext = "Warpnect Session Server Authentication v1".encodeToByteArray()
    private val clientAuthContext = "Warpnect Session Client Authentication v1".encodeToByteArray()
    private val serverCompleteDomain = "Warpnect Session Server Complete v1".encodeToByteArray()
    val clientHandshakeKeyLabel: ByteArray = "Warpnect Session Client Handshake Key v1".encodeToByteArray()
    val clientHandshakeIvLabel: ByteArray = "Warpnect Session Client Handshake IV v1".encodeToByteArray()
    val serverHandshakeKeyLabel: ByteArray = "Warpnect Session Server Handshake Key v1".encodeToByteArray()
    val serverHandshakeIvLabel: ByteArray = "Warpnect Session Server Handshake IV v1".encodeToByteArray()
    val clientFinishedLabel: ByteArray = "Warpnect Session Client Finished v1".encodeToByteArray()
    val serverFinishedLabel: ByteArray = "Warpnect Session Server Finished v1".encodeToByteArray()
    val serverCompleteLabel: ByteArray = "Warpnect Session Server Complete v1".encodeToByteArray()
    private val rootLabel: ByteArray = "Warpnect Authenticated Session Root v1".encodeToByteArray()

    fun transcriptHash(crypto: PairingCryptoProvider, logicalDatagrams: List<ByteArray>): ByteArray = crypto.sha256(
        Writer().apply {
            bounded(transcriptDomain)
            logicalDatagrams.forEach(::bounded)
        }.toByteArray(),
    )

    fun serverAuthTbs(
        deviceId: DeviceId,
        fingerprint: IdentityFingerprint,
        sessionId: SessionId,
        generation: SessionGeneration,
    ): ByteArray = Writer().apply {
        deviceId(deviceId)
        bytes(fingerprint.sha256())
        sessionId(sessionId)
        generation(generation)
        role(SessionRole.Host)
        role(SessionRole.Client)
    }.toByteArray()

    fun clientAuthTbs(
        deviceId: DeviceId,
        fingerprint: IdentityFingerprint,
        sessionId: SessionId,
        generation: SessionGeneration,
    ): ByteArray = Writer().apply {
        deviceId(deviceId)
        bytes(fingerprint.sha256())
        sessionId(sessionId)
        generation(generation)
        role(SessionRole.Client)
        role(SessionRole.Host)
    }.toByteArray()

    fun authSignatureInput(context: ByteArray, transcriptHash: ByteArray, tbs: ByteArray): ByteArray = Writer().apply {
        bytes(ByteArray(64) { 0x20 })
        bounded(context)
        u8(0)
        bytes(transcriptHash)
        bounded(tbs)
    }.toByteArray()

    fun serverSignatureInput(transcriptHash: ByteArray, tbs: ByteArray): ByteArray =
        authSignatureInput(serverAuthContext, transcriptHash, tbs)

    fun clientSignatureInput(transcriptHash: ByteArray, tbs: ByteArray): ByteArray =
        authSignatureInput(clientAuthContext, transcriptHash, tbs)

    fun authFinishedHash(
        crypto: PairingCryptoProvider,
        previousTranscriptHash: ByteArray,
        tbs: ByteArray,
        signature: PairingSignature,
    ): ByteArray = crypto.sha256(
        Writer().apply {
            bounded("Warpnect Session Authentication Finished v1".encodeToByteArray())
            bytes(previousTranscriptHash)
            bounded(tbs)
            bounded(signature.der())
        }.toByteArray(),
    )

    fun authRecord(tbs: ByteArray, signature: PairingSignature, finished: ByteArray): ByteArray = Writer().apply {
        bounded(tbs)
        bounded(signature.der())
        bytes(finished)
    }.toByteArray()

    fun parseAuthRecord(bytes: ByteArray): ParsedAuthRecord? {
        val reader = Reader(bytes)
        val tbs = reader.bounded(128) ?: return null
        val signature = reader.bounded(SessionHandshakeProtocol.MAX_SIGNATURE_BYTES)?.let(PairingSignature::fromDer) ?: return null
        val finished = reader.bytes(SessionHandshakeProtocol.HASH_BYTES) ?: return null
        return if (reader.atEnd) ParsedAuthRecord(tbs, signature, finished) else null
    }

    fun parseAuthTbs(bytes: ByteArray, expectedLocalRole: SessionRole): ParsedAuthTbs? {
        val reader = Reader(bytes)
        val device = reader.deviceId() ?: return null
        val fingerprint = reader.bytes(SessionHandshakeProtocol.HASH_BYTES)?.let(IdentityFingerprint::fromSha256) ?: return null
        val session = reader.sessionId() ?: return null
        val generation = reader.generation() ?: return null
        val sender = reader.role() ?: return null
        val receiver = reader.role() ?: return null
        if (!reader.atEnd || receiver != expectedLocalRole || sender == receiver) return null
        return ParsedAuthTbs(device, fingerprint, session, generation, sender, receiver)
    }

    fun completeRecord(transcriptHash: ByteArray, mac: ByteArray): ByteArray = Writer().apply {
        bytes(transcriptHash)
        bytes(mac)
    }.toByteArray()
    fun parseCompleteRecord(bytes: ByteArray): Pair<ByteArray, ByteArray>? =
        if (bytes.size == SessionHandshakeProtocol.HASH_BYTES * 2) bytes.copyOfRange(0, 32) to bytes.copyOfRange(32, 64) else null

    fun completeMacInput(
        attemptId: SessionHandshakeAttemptId,
        sessionId: SessionId,
        generation: SessionGeneration,
        authenticatedTranscriptHash: ByteArray,
    ): ByteArray = Writer().apply {
        bounded(serverCompleteDomain)
        bytes(attemptId.bytes())
        sessionId(sessionId)
        generation(generation)
        bytes(authenticatedTranscriptHash)
    }.toByteArray()

    fun deriveKeys(
        crypto: PairingCryptoProvider,
        earlyHash: ByteArray,
        sharedSecret: ByteArray,
    ): SessionHandshakeDerivedKeys {
        val secret = crypto.hkdfExtract(earlyHash, sharedSecret)
        return SessionHandshakeDerivedKeys(
            handshakeSecret = ImmutableBytes.copyOf(secret),
            clientKey = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, clientHandshakeKeyLabel, 16)),
            clientIv = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, clientHandshakeIvLabel, 12)),
            serverKey = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, serverHandshakeKeyLabel, 16)),
            serverIv = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, serverHandshakeIvLabel, 12)),
            clientFinishedKey = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, clientFinishedLabel, 32)),
            serverFinishedKey = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, serverFinishedLabel, 32)),
            serverCompleteKey = ImmutableBytes.copyOf(crypto.hkdfExpand(secret, serverCompleteLabel, 32)),
        ).also { secret.fill(0) }
    }

    fun deriveRoot(
        crypto: PairingCryptoProvider,
        handshakeSecret: ByteArray,
        authenticatedTranscriptHash: ByteArray,
    ): AuthenticatedSessionRootSecret {
        val info = rootLabel + authenticatedTranscriptHash
        return AuthenticatedSessionRootSecret(crypto.hkdfExpand(handshakeSecret, info, 32))
    }

    fun aeadNonce(baseIv: ByteArray, sequence: Int): ByteArray {
        require(baseIv.size == 12 && sequence in 0..0xffff)
        return baseIv.copyOf().also { nonce ->
            nonce[10] = (nonce[10].toInt() xor (sequence ushr 8)).toByte()
            nonce[11] = (nonce[11].toInt() xor sequence).toByte()
        }
    }

    data class ParsedAuthRecord(val tbs: ByteArray, val signature: PairingSignature, val finished: ByteArray)
    data class ParsedAuthTbs(
        val deviceId: DeviceId,
        val fingerprint: IdentityFingerprint,
        val sessionId: SessionId,
        val generation: SessionGeneration,
        val senderRole: SessionRole,
        val receiverRole: SessionRole,
    )

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(value: Int) {
            out.write(value)
        }
        fun u32(value: Long) {
            repeat(4) { index -> out.write((value ushr (24 - index * 8)).toInt()) }
        }
        fun bytes(value: ByteArray) {
            out.write(value)
        }
        fun bounded(value: ByteArray) {
            require(value.size <= 0xffff)
            out.write(value.size ushr 8)
            out.write(value.size)
            out.write(value)
        }
        fun deviceId(value: DeviceId) = bytes(idBytes(value.high, value.low))
        fun sessionId(value: SessionId) = bytes(idBytes(value.high, value.low))
        fun generation(value: SessionGeneration) = u32(value.value.toLong())
        fun role(value: SessionRole) = u8(if (value == SessionRole.Client) 1 else 2)
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        val atEnd: Boolean get() = position == data.size
        fun bytes(size: Int): ByteArray? = if (size < 0 || data.size - position < size) {
            null
        } else {
            data.copyOfRange(
                position,
                position + size,
            ).also {
                position += size
            }
        }
        fun bounded(max: Int): ByteArray? {
            val size = u16() ?: return null
            return if (size > max) null else bytes(size)
        }
        fun u16(): Int? = bytes(2)?.let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) }
        fun u32(): Long? = bytes(4)?.fold(0L) { current, byte -> (current shl 8) or (byte.toInt() and 0xff).toLong() }
        fun deviceId(): DeviceId? {
            val value = bytes(16) ?: return null
            return idPair(value)?.let { DeviceId.fromParts(it.first, it.second) }
        }
        fun sessionId(): SessionId? {
            val value = bytes(16) ?: return null
            return idPair(value)?.let { SessionId.fromParts(it.first, it.second) }
        }
        fun generation(): SessionGeneration? = u32()?.toUInt()?.let(SessionGeneration::from)
        fun role(): SessionRole? = bytes(1)?.firstOrNull()?.toInt()?.and(0xff)?.let {
            if (it == 1) SessionRole.Client else if (it == 2) SessionRole.Host else null
        }
    }

    private fun idBytes(high: ULong, low: ULong): ByteArray = ByteArray(16).also { output ->
        repeat(8) { index ->
            output[index] = (high shr (56 - index * 8)).toByte()
            output[index + 8] = (low shr (56 - index * 8)).toByte()
        }
    }
    private fun idPair(bytes: ByteArray): Pair<ULong, ULong>? {
        if (bytes.size != 16) return null
        var high = 0uL
        var low = 0uL
        repeat(8) { index ->
            high = (high shl 8) or (bytes[index].toInt() and 0xff).toULong()
            low = (low shl 8) or (bytes[index + 8].toInt() and 0xff).toULong()
        }
        return high to low
    }
}

class SessionHandshakeDerivedKeys(
    val handshakeSecret: ImmutableBytes,
    val clientKey: ImmutableBytes,
    val clientIv: ImmutableBytes,
    val serverKey: ImmutableBytes,
    val serverIv: ImmutableBytes,
    val clientFinishedKey: ImmutableBytes,
    val serverFinishedKey: ImmutableBytes,
    val serverCompleteKey: ImmutableBytes,
) {
    fun destroy() {
        listOf(
            handshakeSecret,
            clientKey,
            clientIv,
            serverKey,
            serverIv,
            clientFinishedKey,
            serverFinishedKey,
            serverCompleteKey,
        ).forEach(ImmutableBytes::wipe)
    }
}
