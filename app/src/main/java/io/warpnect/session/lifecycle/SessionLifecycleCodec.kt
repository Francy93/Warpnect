@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.lifecycle

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionGeneration
import java.security.MessageDigest

/** Strict canonical WNSL V1 codec. Raw UDP records must never be passed to this parser. */
object SessionLifecycleCodec {
    fun encode(message: SessionLifecycleMessage): ByteArray? {
        return try {
            val body = encodeBody(message) ?: return null
            if (body.size > SessionLifecycleProtocol.MAX_PAYLOAD_BYTES - SessionLifecycleProtocol.HEADER_BYTES) return null
            val header = message.header.copy(bodyLength = body.size)
            if (!header.isValidFor(message)) return null
            LifecycleWriter(SessionLifecycleProtocol.HEADER_BYTES + body.size).apply {
                writeBytes(SessionLifecycleProtocol.MAGIC)
                writeU8(SessionLifecycleProtocol.VERSION)
                writeU8(header.messageType.wireId)
                writeU16(0)
                writeU64(header.lifecycleMessageId.value)
                writeU16(body.size)
                writeU16(0)
                writeBytes(body)
            }.toByteArray()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decode(bytes: ByteArray): DecodedSessionLifecyclePacket? {
        return try {
            if (bytes.size !in SessionLifecycleProtocol.HEADER_BYTES..SessionLifecycleProtocol.MAX_PAYLOAD_BYTES) return null
            val reader = LifecycleReader(bytes)
            if (!reader.readBytes(4).contentEquals(SessionLifecycleProtocol.MAGIC) || reader.readU8() != SessionLifecycleProtocol.VERSION) return null
            val type = SessionLifecycleMessageType.fromWireId(reader.readU8()) ?: return null
            if (reader.readU16() != 0) return null
            val id = LifecycleMessageId.from(reader.readU64()) ?: return null
            val bodyLength = reader.readU16()
            if (reader.readU16() != 0 || bodyLength != bytes.size - SessionLifecycleProtocol.HEADER_BYTES) return null
            val header = SessionLifecycleHeader(type, id, bodyLength)
            val body = reader.readBytes(bodyLength)
            if (!reader.exhausted) return null
            val message = when (type) {
                SessionLifecycleMessageType.Heartbeat -> decodeHeartbeat(header, body, ack = false)
                SessionLifecycleMessageType.HeartbeatAck -> decodeHeartbeat(header, body, ack = true)
                SessionLifecycleMessageType.PathChallenge -> decodeChallenge(header, body, response = false)
                SessionLifecycleMessageType.PathResponse -> decodeChallenge(header, body, response = true)
                SessionLifecycleMessageType.PathMigrationPrepare -> decodeMigrationEntries(header, body, ready = false)
                SessionLifecycleMessageType.PathMigrationReady -> decodeMigrationEntries(header, body, ready = true)
                SessionLifecycleMessageType.PathMigrationCommit -> decodeCommit(header, body, ack = false)
                SessionLifecycleMessageType.PathMigrationAck -> decodeCommit(header, body, ack = true)
                SessionLifecycleMessageType.DisconnectNotice -> decodeDisconnect(header, body, ack = false)
                SessionLifecycleMessageType.DisconnectAck -> decodeDisconnect(header, body, ack = true)
            } ?: return null
            DecodedSessionLifecyclePacket(message, bytes.copyOf())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun hash(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun migrationPlanHash(
        prepare: SessionLifecycleMessage.PathMigrationPrepare,
        ready: SessionLifecycleMessage.PathMigrationReady,
    ): ByteArray? {
        val left = encode(prepare) ?: return null
        val right = encode(ready) ?: return null
        return hash(left + right)
    }

    private fun encodeBody(message: SessionLifecycleMessage): ByteArray? = when (message) {
        is SessionLifecycleMessage.Heartbeat -> encodeHeartbeat(message.heartbeatId, message.activePathId)
        is SessionLifecycleMessage.HeartbeatAck -> encodeHeartbeat(message.heartbeatId, message.activePathId)
        is SessionLifecycleMessage.PathChallenge -> encodeChallenge(
            message.migrationId,
            message.targetPathId,
            message.targetPathKind,
            message.challenge,
        )
        is SessionLifecycleMessage.PathResponse -> encodeChallenge(
            message.migrationId,
            message.targetPathId,
            message.targetPathKind,
            message.challenge,
        )
        is SessionLifecycleMessage.PathMigrationPrepare -> encodeMigrationEntries(
            message.migrationId,
            message.targetPathId,
            message.preparedConfigurationHash,
            message.entries,
        )
        is SessionLifecycleMessage.PathMigrationReady -> encodeMigrationEntries(
            message.migrationId,
            message.targetPathId,
            message.preparedConfigurationHash,
            message.entries,
        )
        is SessionLifecycleMessage.PathMigrationCommit -> encodeCommit(
            message.migrationId,
            message.targetPathId,
            message.migrationPlanHash,
        )
        is SessionLifecycleMessage.PathMigrationAck -> encodeCommit(
            message.migrationId,
            message.targetPathId,
            message.migrationPlanHash,
        )
        is SessionLifecycleMessage.DisconnectNotice -> encodeDisconnect(
            message.reason,
            message.sessionGeneration,
            message.activePathId,
        )
        is SessionLifecycleMessage.DisconnectAck -> encodeDisconnect(
            message.reason,
            message.sessionGeneration,
            message.activePathId,
        )
    }

    private fun encodeHeartbeat(id: HeartbeatId, pathId: PathId): ByteArray? = if (!id.isValid) {
        null
    } else {
        LifecycleWriter(16).apply {
            writeU64(id.value)
            writeU32(pathId.value)
            writeU32(0u)
        }.toByteArray()
    }

    private fun encodeChallenge(
        id: PathMigrationId,
        pathId: PathId,
        kind: NetworkPathKind,
        challenge: ByteArray,
    ): ByteArray? = if (!id.isValid || challenge.size != SessionLifecycleProtocol.CHALLENGE_BYTES) {
        null
    } else {
        LifecycleWriter(32).apply {
            writeU64(id.value)
            writeU32(pathId.value)
            writeU8(kind.toWire())
            writeBytes(ByteArray(3))
            writeBytes(challenge)
        }.toByteArray()
    }

    private fun encodeMigrationEntries(
        id: PathMigrationId,
        pathId: PathId,
        configurationHash: ByteArray,
        entries: List<PathMigrationEntry>,
    ): ByteArray? = if (!id.isValid || !validHash(configurationHash) || !entries.validSet()) {
        null
    } else {
        LifecycleWriter(48 + entries.size * 8).apply {
            writeU64(id.value)
            writeU32(pathId.value)
            writeU8(entries.size)
            writeBytes(ByteArray(3))
            writeBytes(configurationHash)
            entries.sortedBy { it.channelId.value }.forEach {
                writeU32(it.channelId.value)
                writeU16(it.localPort)
                writeU16(0)
            }
        }.toByteArray()
    }

    private fun encodeCommit(id: PathMigrationId, pathId: PathId, planHash: ByteArray): ByteArray? =
        if (!id.isValid || !validHash(planHash)) {
            null
        } else {
            LifecycleWriter(48).apply {
                writeU64(id.value)
                writeU32(pathId.value)
                writeU32(0u)
                writeBytes(planHash)
            }.toByteArray()
        }

    private fun encodeDisconnect(reason: DisconnectReason, generation: SessionGeneration, pathId: PathId): ByteArray =
        LifecycleWriter(12).apply {
            writeU8(reason.wireId)
            writeU8(0)
            writeU16(0)
            writeU32(generation.value)
            writeU32(pathId.value)
        }.toByteArray()

    private fun decodeHeartbeat(
        header: SessionLifecycleHeader,
        body: ByteArray,
        ack: Boolean,
    ): SessionLifecycleMessage? {
        if (body.size != 16) return null
        val reader = LifecycleReader(body)
        val heartbeat = HeartbeatId.from(reader.readU64()) ?: return null
        val path = PathId.from(reader.readU32()) ?: return null
        if (reader.readU32() != 0u || !reader.exhausted) return null
        return if (ack) {
            SessionLifecycleMessage.HeartbeatAck(
                header,
                heartbeat,
                path,
            )
        } else {
            SessionLifecycleMessage.Heartbeat(header, heartbeat, path)
        }
    }

    private fun decodeChallenge(
        header: SessionLifecycleHeader,
        body: ByteArray,
        response: Boolean,
    ): SessionLifecycleMessage? {
        if (body.size != 32) return null
        val reader = LifecycleReader(body)
        val id = PathMigrationId.from(reader.readU64()) ?: return null
        val path = PathId.from(reader.readU32()) ?: return null
        val kind = fromWirePath(reader.readU8()) ?: return null
        if (!reader.readBytes(3).all { it == 0.toByte() }) return null
        val challenge = reader.readBytes(SessionLifecycleProtocol.CHALLENGE_BYTES)
        if (!reader.exhausted) return null
        return if (response) {
            SessionLifecycleMessage.PathResponse(header, id, path, kind, challenge)
        } else {
            SessionLifecycleMessage.PathChallenge(header, id, path, kind, challenge)
        }
    }

    private fun decodeMigrationEntries(
        header: SessionLifecycleHeader,
        body: ByteArray,
        ready: Boolean,
    ): SessionLifecycleMessage? {
        if (body.size < 48) return null
        val reader = LifecycleReader(body)
        val id = PathMigrationId.from(reader.readU64()) ?: return null
        val path = PathId.from(reader.readU32()) ?: return null
        val count = reader.readU8()
        if (!reader.readBytes(3).all {
                it == 0.toByte()
            } || count !in 1..SessionLifecycleProtocol.MAX_CHANNEL_MIGRATION_ENTRIES
        ) {
            return null
        }
        val hash = reader.readBytes(SessionLifecycleProtocol.HASH_BYTES)
        if (!validHash(hash) || reader.remaining != count * 8) return null
        val entries = List(count) {
            val channel = ChannelId.from(reader.readU32()) ?: return null
            val port = reader.readU16()
            if (reader.readU16() != 0) return null
            PathMigrationEntry(channel, port)
        }
        if (!reader.exhausted || !entries.validSet()) return null
        return if (ready) {
            SessionLifecycleMessage.PathMigrationReady(header, id, path, hash, entries)
        } else {
            SessionLifecycleMessage.PathMigrationPrepare(header, id, path, hash, entries)
        }
    }

    private fun decodeCommit(header: SessionLifecycleHeader, body: ByteArray, ack: Boolean): SessionLifecycleMessage? {
        if (body.size != 48) return null
        val reader = LifecycleReader(body)
        val id = PathMigrationId.from(reader.readU64()) ?: return null
        val path = PathId.from(reader.readU32()) ?: return null
        if (reader.readU32() != 0u) return null
        val hash = reader.readBytes(SessionLifecycleProtocol.HASH_BYTES)
        if (!validHash(hash) || !reader.exhausted) return null
        return if (ack) {
            SessionLifecycleMessage.PathMigrationAck(
                header,
                id,
                path,
                hash,
            )
        } else {
            SessionLifecycleMessage.PathMigrationCommit(header, id, path, hash)
        }
    }

    private fun decodeDisconnect(
        header: SessionLifecycleHeader,
        body: ByteArray,
        ack: Boolean,
    ): SessionLifecycleMessage? {
        if (body.size != 12) return null
        val reader = LifecycleReader(body)
        val reason = DisconnectReason.fromWireId(reader.readU8()) ?: return null
        if (reader.readU8() != 0 || reader.readU16() != 0) return null
        val generation = SessionGeneration.from(reader.readU32()) ?: return null
        val path = PathId.from(reader.readU32()) ?: return null
        if (!reader.exhausted) return null
        return if (ack) {
            SessionLifecycleMessage.DisconnectAck(
                header,
                reason,
                generation,
                path,
            )
        } else {
            SessionLifecycleMessage.DisconnectNotice(header, reason, generation, path)
        }
    }

    private fun SessionLifecycleHeader.isValidFor(message: SessionLifecycleMessage): Boolean =
        lifecycleMessageId.isValid &&
            messageType == when (message) {
                is SessionLifecycleMessage.Heartbeat -> SessionLifecycleMessageType.Heartbeat
                is SessionLifecycleMessage.HeartbeatAck -> SessionLifecycleMessageType.HeartbeatAck
                is SessionLifecycleMessage.PathChallenge -> SessionLifecycleMessageType.PathChallenge
                is SessionLifecycleMessage.PathResponse -> SessionLifecycleMessageType.PathResponse
                is SessionLifecycleMessage.PathMigrationPrepare -> SessionLifecycleMessageType.PathMigrationPrepare
                is SessionLifecycleMessage.PathMigrationReady -> SessionLifecycleMessageType.PathMigrationReady
                is SessionLifecycleMessage.PathMigrationCommit -> SessionLifecycleMessageType.PathMigrationCommit
                is SessionLifecycleMessage.PathMigrationAck -> SessionLifecycleMessageType.PathMigrationAck
                is SessionLifecycleMessage.DisconnectNotice -> SessionLifecycleMessageType.DisconnectNotice
                is SessionLifecycleMessage.DisconnectAck -> SessionLifecycleMessageType.DisconnectAck
            }

    private fun List<PathMigrationEntry>.validSet(): Boolean =
        size in 1..SessionLifecycleProtocol.MAX_CHANNEL_MIGRATION_ENTRIES &&
            all(PathMigrationEntry::isValid) && map { it.channelId }.distinct().size == size

    private fun validHash(value: ByteArray): Boolean = value.size == SessionLifecycleProtocol.HASH_BYTES
    private fun NetworkPathKind.toWire(): Int = when (this) {
        NetworkPathKind.Lan -> 1
        NetworkPathKind.Direct -> 2
    }
    private fun fromWirePath(value: Int): NetworkPathKind? = when (value) {
        1 -> NetworkPathKind.Lan
        2 -> NetworkPathKind.Direct
        else -> null
    }
}

private class LifecycleWriter(initialCapacity: Int = 64) {
    private var bytes = ByteArray(initialCapacity)
    private var size = 0
    fun writeU8(value: Int) = writeByte(value)
    fun writeU16(value: Int) {
        writeByte(value ushr 8)
        writeByte(value)
    }
    fun writeU32(value: UInt) {
        for (shift in 24 downTo 0 step 8) writeByte((value shr shift).toInt())
    }
    fun writeU64(value: ULong) {
        for (shift in 56 downTo 0 step 8) writeByte((value shr shift).toInt())
    }
    fun writeBytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(bytes, size)
        size += value.size
    }
    fun toByteArray(): ByteArray = bytes.copyOf(size)
    private fun writeByte(value: Int) {
        ensure(1)
        bytes[size++] = value.toByte()
    }
    private fun ensure(additional: Int) {
        if (size + additional > bytes.size) bytes = bytes.copyOf((size + additional).coerceAtLeast(bytes.size * 2))
    }
}

private class LifecycleReader(private val bytes: ByteArray) {
    private var offset = 0
    val exhausted: Boolean get() = offset == bytes.size
    val remaining: Int get() = bytes.size - offset
    fun readU8(): Int = requireBytes(1).let { bytes[offset++].toInt() and 0xff }
    fun readU16(): Int = (readU8() shl 8) or readU8()
    fun readU32(): UInt =
        ((readU8().toUInt() shl 24) or (readU8().toUInt() shl 16) or (readU8().toUInt() shl 8) or readU8().toUInt())
    fun readU64(): ULong {
        var result = 0uL
        repeat(8) { result = (result shl 8) or readU8().toULong() }
        return result
    }
    fun readBytes(length: Int): ByteArray {
        requireBytes(length)
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }
    private fun requireBytes(length: Int) {
        require(length >= 0 && remaining >= length) { "Truncated WNSL payload" }
    }
}
