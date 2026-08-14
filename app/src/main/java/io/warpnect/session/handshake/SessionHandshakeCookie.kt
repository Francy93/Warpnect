@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.pairing.PairingCryptoProvider

fun interface SessionHandshakeMonotonicClock {
    fun nowMs(): Long
}
object SystemSessionHandshakeMonotonicClock : SessionHandshakeMonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

/** Stateless return-routability cookies. They protect allocation only and carry no identity claim. */
class SessionHandshakeCookieManager(
    private val crypto: PairingCryptoProvider,
    private val clock: SessionHandshakeMonotonicClock = SystemSessionHandshakeMonotonicClock,
    private val keyRotationMs: Long = SessionHandshakeProtocol.DEFAULT_COOKIE_ROTATION_MS,
    private val validityMs: Long = SessionHandshakeProtocol.DEFAULT_COOKIE_VALIDITY_MS,
) {
    private var current = Key(ImmutableBytes.copyOf(crypto.randomBytes(32)), clock.nowMs().coerceAtLeast(0L))
    private var previous: Key? = null

    init {
        require(keyRotationMs > 0 && validityMs > 0)
    }

    fun issue(endpoint: HandshakeTransportEndpoint, packet: SessionHandshakePacket): ImmutableBytes? {
        val hello = packet.message as? SessionHandshakeMessage.ClientHello ?: return null
        if (packet.header.messageSequence != 0 || hello.retryCookie != null) return null
        rotateIfNeeded()
        val issuedAt = clock.nowMs().coerceAtLeast(0L)
        val material = macInput(endpoint, packet.header.attemptId, initialHelloHash(packet), hello, issuedAt)
        val mac = crypto.hmacSha256(current.key.toByteArray(), material)
        return ImmutableBytes.copyOf(u64(issuedAt) + mac)
    }

    fun validate(
        endpoint: HandshakeTransportEndpoint,
        initialPacket: SessionHandshakePacket,
        cookiePacket: SessionHandshakePacket,
    ): SessionHandshakeError {
        val initial = initialPacket.message as? SessionHandshakeMessage.ClientHello ?: return SessionHandshakeError.CookieInvalid
        val retry = cookiePacket.message as? SessionHandshakeMessage.ClientHello ?: return SessionHandshakeError.CookieInvalid
        val cookie = retry.retryCookie?.toByteArray() ?: return SessionHandshakeError.CookieInvalid
        if (cookie.size != 40 || cookiePacket.header.messageSequence != 2 || !cookiePacket.header.hasRetryCookie || !sameHello(initial, retry)) return SessionHandshakeError.CookieInvalid
        val issuedAt = readU64(cookie.copyOfRange(0, 8)) ?: return SessionHandshakeError.CookieInvalid
        val now = clock.nowMs().coerceAtLeast(0L)
        if (issuedAt > now || now - issuedAt > validityMs) return SessionHandshakeError.CookieExpired
        rotateIfNeeded()
        val material =
            macInput(endpoint, initialPacket.header.attemptId, initialHelloHash(initialPacket), initial, issuedAt)
        val received = cookie.copyOfRange(8, 40)
        val keys = listOfNotNull(current, previous)
        return if (keys.any {
                crypto.constantTimeEquals(
                    crypto.hmacSha256(it.key.toByteArray(), material),
                    received,
                )
            }
        ) {
            SessionHandshakeError.None
        } else {
            SessionHandshakeError.CookieInvalid
        }
    }

    private fun rotateIfNeeded() {
        val now = clock.nowMs().coerceAtLeast(0L)
        if (now - current.createdAtMs < keyRotationMs) return
        previous?.key?.wipe()
        previous = current
        current = Key(ImmutableBytes.copyOf(crypto.randomBytes(32)), now)
    }

    private fun initialHelloHash(packet: SessionHandshakePacket): ByteArray = crypto.sha256(
        packet.datagram.copyOfRange(SessionHandshakeProtocol.HEADER_BYTES, packet.datagram.size),
    )

    private fun macInput(
        endpoint: HandshakeTransportEndpoint,
        attemptId: SessionHandshakeAttemptId,
        initialHelloHash: ByteArray,
        hello: SessionHandshakeMessage.ClientHello,
        issuedAt: Long,
    ): ByteArray = java.io.ByteArrayOutputStream().apply {
        write("Warpnect Session Cookie v1".encodeToByteArray())
        write(endpoint.addressBytes().size)
        write(endpoint.addressBytes())
        write(endpoint.port ushr 8)
        write(endpoint.port)
        write(attemptId.bytes())
        write(initialHelloHash)
        write(idBytes(hello.sessionId.high, hello.sessionId.low))
        writeU32(this, hello.generation.value.toLong())
        write(hello.targetPresence.bytes())
        write(u64(issuedAt))
    }.toByteArray()

    private fun sameHello(
        left: SessionHandshakeMessage.ClientHello,
        right: SessionHandshakeMessage.ClientHello,
    ): Boolean =
        left.suite == right.suite && left.sessionId == right.sessionId && left.generation == right.generation &&
            left.initiatorRole == right.initiatorRole && left.responderRole == right.responderRole &&
            left.targetPresence.bytes().contentEquals(right.targetPresence.bytes()) && left.nonce.bytes().contentEquals(right.nonce.bytes()) &&
            left.ephemeralPublicKey.encodedSpki().contentEquals(right.ephemeralPublicKey.encodedSpki())

    private data class Key(val key: ImmutableBytes, val createdAtMs: Long)
}

private fun u64(value: Long): ByteArray = ByteArray(
    8,
).also { output -> repeat(8) { index -> output[index] = (value ushr (56 - index * 8)).toByte() } }
private fun readU64(bytes: ByteArray): Long? = if (bytes.size != 8) {
    null
} else {
    bytes.fold(
        0L,
    ) { result, value -> (result shl 8) or (value.toInt() and 0xff).toLong() }
}
private fun idBytes(high: ULong, low: ULong): ByteArray = ByteArray(16).also { output ->
    repeat(8) { index ->
        output[index] = (high shr (56 - index * 8)).toByte()
        output[index + 8] = (low shr (56 - index * 8)).toByte()
    }
}
private fun writeU32(out: java.io.ByteArrayOutputStream, value: Long) {
    repeat(4) { index -> out.write((value ushr (24 - index * 8)).toInt()) }
}
