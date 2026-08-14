@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.handshake

import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.pairing.EphemeralPublicKey

/** Strict, bounded binary codec for the standalone WNSH bootstrap protocol. */
object SessionHandshakeCodec {
    fun encode(
        attemptId: SessionHandshakeAttemptId,
        sequence: Int,
        message: SessionHandshakeMessage,
        flags: Int = flagsFor(message),
    ): ByteArray? {
        val payload = encodePayload(message) ?: return null
        val header = SessionHandshakeHeader(message.type, flags, attemptId, sequence, payload.size)
        if (!isHeaderValid(header) || payload.size + SessionHandshakeProtocol.HEADER_BYTES > SessionHandshakeProtocol.MAX_DATAGRAM_BYTES) {
            return null
        }
        return encodeHeader(header) + payload
    }

    fun decode(datagram: ByteArray): Pair<SessionHandshakePacket?, SessionHandshakeError> {
        if (datagram.size !in SessionHandshakeProtocol.HEADER_BYTES..SessionHandshakeProtocol.MAX_DATAGRAM_BYTES) {
            return null to SessionHandshakeError.MalformedDatagram
        }
        val reader = Reader(datagram)
        if (!reader.readMagic(SessionHandshakeProtocol.MAGIC) || reader.u8() != SessionHandshakeProtocol.VERSION) {
            return null to SessionHandshakeError.UnsupportedVersion
        }
        val type = reader.u8()?.let(SessionHandshakeMessageType::fromWireId) ?: return null to SessionHandshakeError.MalformedDatagram
        val flags = reader.u16() ?: return null to SessionHandshakeError.MalformedDatagram
        val attempt = SessionHandshakeAttemptId.fromBytes(reader.bytes(16)) ?: return null to SessionHandshakeError.MalformedDatagram
        val sequence = reader.u16() ?: return null to SessionHandshakeError.MalformedDatagram
        val length = reader.u16() ?: return null to SessionHandshakeError.MalformedDatagram
        if (reader.u32() != 0L || reader.remaining != length || !reader.complete) return null to SessionHandshakeError.MalformedDatagram
        val header = SessionHandshakeHeader(type, flags, attempt, sequence, length)
        if (!isHeaderValid(header)) return null to SessionHandshakeError.MalformedDatagram
        val payload = reader.bytes(length)
        val message = decodePayload(type, payload, header) ?: return null to SessionHandshakeError.MalformedDatagram
        if (!reader.complete) return null to SessionHandshakeError.MalformedDatagram
        return SessionHandshakePacket(header, message, datagram.copyOf()) to SessionHandshakeError.None
    }

    fun encodeHeader(header: SessionHandshakeHeader): ByteArray = Writer().apply {
        bytes(SessionHandshakeProtocol.MAGIC)
        u8(SessionHandshakeProtocol.VERSION)
        u8(header.messageType.wireId)
        u16(header.flags)
        bytes(header.attemptId.bytes())
        u16(header.messageSequence)
        u16(header.payloadLength)
        u32(0)
    }.toByteArray()

    fun flagsFor(message: SessionHandshakeMessage): Int = when (message) {
        is SessionHandshakeMessage.ClientHello -> if (message.retryCookie == null) 0 else 0x0001
        is SessionHandshakeMessage.ServerAuth,
        is SessionHandshakeMessage.ClientAuth,
        is SessionHandshakeMessage.ServerComplete,
        -> 0x0002
        else -> 0
    }

    fun isHeaderValid(header: SessionHandshakeHeader): Boolean {
        if (header.flags and 0xfffc != 0 || header.messageSequence !in 0..0xffff || header.payloadLength !in 0..(SessionHandshakeProtocol.MAX_DATAGRAM_BYTES - SessionHandshakeProtocol.HEADER_BYTES)) return false
        val cookie = header.hasRetryCookie
        val encrypted = header.encryptedPayload
        return when (header.messageType) {
            SessionHandshakeMessageType.ClientHello -> (header.messageSequence == 0 && !cookie && !encrypted) ||
                (header.messageSequence == 2 && cookie && !encrypted)
            SessionHandshakeMessageType.HelloRetry -> header.messageSequence == 1 && !cookie && !encrypted
            SessionHandshakeMessageType.ServerHello -> header.messageSequence == 3 && !cookie && !encrypted
            SessionHandshakeMessageType.ServerAuth -> header.messageSequence == 4 && !cookie && encrypted
            SessionHandshakeMessageType.ClientAuth -> header.messageSequence == 5 && !cookie && encrypted
            SessionHandshakeMessageType.ServerComplete -> header.messageSequence == 6 && !cookie && encrypted
            SessionHandshakeMessageType.HandshakeReject -> !cookie && !encrypted
        }
    }

    private fun encodePayload(message: SessionHandshakeMessage): ByteArray? = when (message) {
        is SessionHandshakeMessage.ClientHello -> Writer().apply {
            u8(message.suite)
            sessionId(message.sessionId)
            u32(message.generation.value.toLong())
            role(message.initiatorRole)
            role(message.responderRole)
            bytes(message.targetPresence.bytes())
            bytes(message.nonce.bytes())
            bounded(message.ephemeralPublicKey.encodedSpki(), SessionHandshakeProtocol.MAX_PUBLIC_KEY_BYTES)
            bounded(message.retryCookie?.toByteArray() ?: ByteArray(0), 96)
        }.toByteArray()
        is SessionHandshakeMessage.HelloRetry -> Writer().apply {
            bounded(
                message.cookie.toByteArray(),
                96,
            )
        }.toByteArray()
        is SessionHandshakeMessage.ServerHello -> Writer().apply {
            u8(message.suite)
            sessionId(message.sessionId)
            u32(message.generation.value.toLong())
            role(message.initiatorRole)
            role(message.responderRole)
            bytes(message.nonce.bytes())
            bounded(message.ephemeralPublicKey.encodedSpki(), SessionHandshakeProtocol.MAX_PUBLIC_KEY_BYTES)
        }.toByteArray()
        is SessionHandshakeMessage.ServerAuth -> message.encryptedRecord.toByteArray()
        is SessionHandshakeMessage.ClientAuth -> message.encryptedRecord.toByteArray()
        is SessionHandshakeMessage.ServerComplete -> message.encryptedRecord.toByteArray()
        is SessionHandshakeMessage.Reject -> byteArrayOf(message.reason.wireId.toByte())
    }.takeIf { it.size <= SessionHandshakeProtocol.MAX_DATAGRAM_BYTES - SessionHandshakeProtocol.HEADER_BYTES }

    private fun decodePayload(
        type: SessionHandshakeMessageType,
        bytes: ByteArray,
        header: SessionHandshakeHeader,
    ): SessionHandshakeMessage? {
        if (header.encryptedPayload) {
            return when (type) {
                SessionHandshakeMessageType.ServerAuth -> encrypted(bytes)?.let(SessionHandshakeMessage::ServerAuth)
                SessionHandshakeMessageType.ClientAuth -> encrypted(bytes)?.let(SessionHandshakeMessage::ClientAuth)
                SessionHandshakeMessageType.ServerComplete -> encrypted(
                    bytes,
                )?.let(SessionHandshakeMessage::ServerComplete)
                else -> null
            }
        }
        val r = Reader(bytes)
        return when (type) {
            SessionHandshakeMessageType.ClientHello -> {
                val suite = r.u8()
                val session = sessionId(r.bytes(16))
                val generationValue = r.u32()
                val generation = generationValue?.toUInt()?.let(SessionGeneration::from)
                val initiator = role(r.u8())
                val responder = role(r.u8())
                val presence = DiscoveryPresenceBinding.fromBytes(r.bytes(16))
                val nonce = HandshakeNonce.from(r.bytes(SessionHandshakeProtocol.NONCE_BYTES))
                val key = ephemeral(r.bounded(SessionHandshakeProtocol.MAX_PUBLIC_KEY_BYTES))
                val cookie = r.bounded(96)
                if (suite == null || session == null || generation == null || initiator == null || responder == null || presence == null || nonce == null || key == null || !r.complete ||
                    (header.hasRetryCookie != cookie.isNotEmpty()) || (header.hasRetryCookie && cookie.isEmpty())
                ) {
                    null
                } else {
                    SessionHandshakeMessage.ClientHello(
                        suite, session, generation, initiator, responder, presence, nonce, key,
                        cookie.takeIf {
                            it.isNotEmpty()
                        }?.let(ImmutableBytes::copyOf),
                    )
                }
            }
            SessionHandshakeMessageType.HelloRetry -> r.bounded(96).takeIf {
                it.isNotEmpty() && r.complete
            }?.let { SessionHandshakeMessage.HelloRetry(ImmutableBytes.copyOf(it)) }
            SessionHandshakeMessageType.ServerHello -> {
                val suite = r.u8()
                val session = sessionId(r.bytes(16))
                val generationValue = r.u32()
                val generation = generationValue?.toUInt()?.let(SessionGeneration::from)
                val initiator = role(r.u8())
                val responder = role(r.u8())
                val nonce = HandshakeNonce.from(r.bytes(SessionHandshakeProtocol.NONCE_BYTES))
                val key = ephemeral(r.bounded(SessionHandshakeProtocol.MAX_PUBLIC_KEY_BYTES))
                if (suite == null || session == null || generation == null || initiator == null || responder == null || nonce == null || key == null || !r.complete) {
                    null
                } else {
                    SessionHandshakeMessage.ServerHello(suite, session, generation, initiator, responder, nonce, key)
                }
            }
            SessionHandshakeMessageType.HandshakeReject -> r.u8()?.let(
                SessionHandshakeRejectReason::fromWireId,
            )?.takeIf {
                r.complete
            }?.let(SessionHandshakeMessage::Reject)
            else -> null
        }
    }

    private fun encrypted(bytes: ByteArray): ImmutableBytes? = bytes.takeIf {
        it.size >= SessionHandshakeProtocol.GCM_TAG_BYTES
    }?.let(ImmutableBytes::copyOf)
    private fun ephemeral(value: ByteArray): EphemeralPublicKey? = EphemeralPublicKey.fromSpki(value)
    private fun sessionId(bytes: ByteArray): SessionId? =
        fromIdBytes(bytes)?.let { SessionId.fromParts(it.first, it.second) }
    private fun role(value: Int?): SessionRole? = when (value) {
        1 -> SessionRole.Client
        2 -> SessionRole.Host
        else -> null
    }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(value: Int) {
            if (value !in 0..255) throw IllegalArgumentException()
            out.write(value)
        }
        fun u16(value: Int) {
            if (value !in 0..0xffff) throw IllegalArgumentException()
            out.write(value ushr 8)
            out.write(value)
        }
        fun u32(value: Long) {
            if (value !in 0..0xffffffffL) throw IllegalArgumentException()
            repeat(4) { shift -> out.write((value ushr (24 - shift * 8)).toInt()) }
        }
        fun bytes(value: ByteArray) {
            out.write(value)
        }
        fun bounded(value: ByteArray, max: Int) {
            if (value.size > max) throw IllegalArgumentException()
            u16(value.size)
            bytes(value)
        }
        fun sessionId(value: SessionId) {
            bytes(idBytes(value.high, value.low))
        }
        fun role(value: SessionRole) = u8(if (value == SessionRole.Client) 1 else 2)
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        var complete: Boolean = true
            private set
        val remaining: Int get() = data.size - position
        fun readMagic(expected: ByteArray): Boolean = bytes(expected.size).contentEquals(expected)
        fun u8(): Int? = if (remaining < 1) {
            complete = false
            null
        } else {
            data[position++].toInt() and 0xff
        }
        fun u16(): Int? = if (remaining < 2) {
            complete = false
            null
        } else {
            ((data[position++].toInt() and 0xff) shl 8) or (data[position++].toInt() and 0xff)
        }
        fun u32(): Long? = if (remaining < 4) {
            complete = false
            null
        } else {
            (0 until 4).fold(0L) { value, _ -> (value shl 8) or (data[position++].toInt() and 0xff).toLong() }
        }
        fun bytes(size: Int): ByteArray {
            if (size < 0 || remaining < size) {
                complete = false
                return ByteArray(0)
            }
            return data.copyOfRange(position, position + size).also { position += size }
        }
        fun bounded(max: Int): ByteArray {
            val size = u16() ?: return ByteArray(0)
            return if (size > max) {
                complete = false
                ByteArray(0)
            } else {
                bytes(size)
            }
        }
    }

    private fun idBytes(high: ULong, low: ULong): ByteArray = ByteArray(16).also { output ->
        repeat(8) { index ->
            output[index] = (high shr (56 - index * 8)).toByte()
            output[index + 8] = (low shr (56 - index * 8)).toByte()
        }
    }
    private fun fromIdBytes(bytes: ByteArray): Pair<ULong, ULong>? {
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
