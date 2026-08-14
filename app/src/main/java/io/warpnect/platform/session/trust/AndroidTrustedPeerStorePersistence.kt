package io.warpnect.platform.session.trust

import android.content.Context
import android.util.AtomicFile
import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityFingerprint
import io.warpnect.session.identity.IdentityKeyAlgorithm
import io.warpnect.session.identity.IdentityPublicKey
import io.warpnect.session.trust.TRUSTED_PEER_STORE_SCHEMA_VERSION
import io.warpnect.session.trust.TrustedPeerRecord
import io.warpnect.session.trust.TrustedPeerStoreLoadResult
import io.warpnect.session.trust.TrustedPeerStorePersistence
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * App-private AtomicFile persistence for public trusted-peer bindings. It contains no private
 * identity key and no persistent symmetric pairing secret.
 */
class AndroidTrustedPeerStorePersistence(
    context: Context,
    fileName: String = FILE_NAME,
) : TrustedPeerStorePersistence {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, fileName))

    override fun load(): TrustedPeerStoreLoadResult = try {
        decode(file.openRead().use { it.readBytes() })
    } catch (_: FileNotFoundException) {
        TrustedPeerStoreLoadResult.Empty
    } catch (_: IOException) {
        TrustedPeerStoreLoadResult.Failed("Unable to read trusted-peer store")
    }

    override fun replace(records: List<TrustedPeerRecord>): Boolean {
        val encoded = try {
            encode(records)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return false
        }
        return try {
            stream.write(encoded)
            file.finishWrite(stream)
            true
        } catch (_: IOException) {
            file.failWrite(stream)
            false
        }
    }

    private fun encode(records: List<TrustedPeerRecord>): ByteArray {
        require(records.size <= MAX_RECORDS_ON_DISK)
        return StoreWriter().apply {
            writeBytes(MAGIC)
            writeByte(TRUSTED_PEER_STORE_SCHEMA_VERSION)
            writeU16(records.size)
            records.forEach { writeRecord(it) }
        }.toByteArray()
    }

    private fun StoreWriter.writeRecord(record: TrustedPeerRecord) {
        writeDeviceId(record.peerDeviceId)
        writeByte(record.identityKeyAlgorithm.wireId)
        writeLengthPrefixed(record.identityPublicKey.encodedSpki())
        writeBytes(record.identityFingerprint.sha256())
        writeLong(record.pairedAtWallClockMs)
        writeLong(record.lastVerifiedAtWallClockMs)
        val alias = record.remoteAliasAtPairing
        if (alias == null) {
            writeU16(NO_ALIAS)
        } else {
            val bytes = alias.encodeToByteArray()
            require(bytes.size <= MAX_ALIAS_UTF8_BYTES)
            writeLengthPrefixed(bytes)
        }
    }

    private fun decode(bytes: ByteArray): TrustedPeerStoreLoadResult {
        return try {
            val reader = StoreReader(bytes)
            if (!reader.readBytes(MAGIC.size).contentEquals(MAGIC)) return corrupt()
            if (reader.readByte() != TRUSTED_PEER_STORE_SCHEMA_VERSION) return corrupt()
            val count = reader.readU16()
            if (count > MAX_RECORDS_ON_DISK) return corrupt()
            val records = ArrayList<TrustedPeerRecord>(count)
            repeat(count) { records += readRecord(reader) ?: return corrupt() }
            if (!reader.isExhausted()) return corrupt()
            if (records.isEmpty()) TrustedPeerStoreLoadResult.Empty else TrustedPeerStoreLoadResult.Records(records)
        } catch (_: StoreReadException) {
            corrupt()
        }
    }

    private fun readRecord(reader: StoreReader): TrustedPeerRecord? {
        val deviceId = reader.readDeviceId() ?: return null
        val algorithm = IdentityKeyAlgorithm.fromWireId(reader.readByte()) ?: return null
        val key = IdentityPublicKey.fromSpki(
            reader.readLengthPrefixed(IdentityPublicKey.MAX_ENCODED_BYTES),
        ) ?: return null
        val fingerprint = IdentityFingerprint.fromSha256(
            reader.readBytes(IdentityFingerprint.SHA256_BYTES),
        ) ?: return null
        val pairedAt = reader.readLong()
        val lastVerifiedAt = reader.readLong()
        val aliasLength = reader.readU16()
        val alias = when (aliasLength) {
            NO_ALIAS -> null
            else -> {
                if (aliasLength > MAX_ALIAS_UTF8_BYTES) return null
                decodeUtf8(reader.readBytes(aliasLength)) ?: return null
            }
        }
        return try {
            TrustedPeerRecord(deviceId, algorithm, key, fingerprint, pairedAt, lastVerifiedAt, alias)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeUtf8(value: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun corrupt(): TrustedPeerStoreLoadResult =
        TrustedPeerStoreLoadResult.Corrupt("Trusted-peer store is malformed")

    private class StoreWriter {
        private val output = ByteArrayOutputStream()

        fun writeByte(value: Int) {
            require(value in 0..0xff)
            output.write(value)
        }

        fun writeU16(value: Int) {
            require(value in 0..0xffff)
            writeByte((value ushr 8) and 0xff)
            writeByte(value and 0xff)
        }

        fun writeLong(value: Long) {
            val unsigned = value.toULong()
            for (shift in 56 downTo 0 step 8) writeByte(((unsigned shr shift) and 0xffu).toInt())
        }

        fun writeDeviceId(deviceId: DeviceId) {
            writeUnsignedLong(deviceId.high)
            writeUnsignedLong(deviceId.low)
        }

        fun writeUnsignedLong(value: ULong) {
            for (shift in 56 downTo 0 step 8) writeByte(((value shr shift) and 0xffu).toInt())
        }

        fun writeLengthPrefixed(value: ByteArray) {
            require(value.size <= 0xffff)
            writeU16(value.size)
            writeBytes(value)
        }

        fun writeBytes(value: ByteArray) {
            output.write(value)
        }

        fun toByteArray(): ByteArray = output.toByteArray()
    }

    private class StoreReader(
        private val input: ByteArray,
    ) {
        private var index = 0

        fun isExhausted(): Boolean = index == input.size

        fun readByte(): Int {
            if (index >= input.size) throw StoreReadException
            return input[index++].toInt() and 0xff
        }

        fun readU16(): Int = (readByte() shl 8) or readByte()

        fun readLong(): Long = readUnsignedLong().toLong()

        fun readUnsignedLong(): ULong {
            var value = 0uL
            repeat(8) { value = (value shl 8) or readByte().toULong() }
            return value
        }

        fun readDeviceId(): DeviceId? = DeviceId.fromParts(readUnsignedLong(), readUnsignedLong())

        fun readLengthPrefixed(maxLength: Int): ByteArray {
            val length = readU16()
            if (length !in 1..maxLength) throw StoreReadException
            return readBytes(length)
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || input.size - index < length) throw StoreReadException
            return input.copyOfRange(index, index + length).also { index += length }
        }
    }

    private data object StoreReadException : RuntimeException()

    private companion object {
        val MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte())
        const val FILE_NAME: String = "warpnect.trusted.peers.v1.bin"
        const val MAX_RECORDS_ON_DISK: Int = 128
        const val MAX_ALIAS_UTF8_BYTES: Int = TrustedPeerRecord.MAX_STORED_ALIAS_LENGTH * 4
        const val NO_ALIAS: Int = 0xffff
    }
}
