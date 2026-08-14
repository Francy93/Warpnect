package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityPublicKey
import java.io.ByteArrayOutputStream

enum class PairingCodecError {
    None,
    OversizeDatagram,
    TruncatedHeader,
    InvalidMagic,
    UnsupportedVersion,
    UnknownMessageType,
    NonZeroFlags,
    NonZeroReserved,
    InvalidAttemptId,
    PayloadLengthMismatch,
    TruncatedPayload,
    TrailingPayloadBytes,
    InvalidField,
}

data class PairingDecodeResult(
    val packet: PairingBootstrapPacket? = null,
    val error: PairingCodecError = PairingCodecError.None,
) {
    val isSuccess: Boolean
        get() = packet != null && error == PairingCodecError.None
}

/** Exact big-endian binary codec for standalone Pairing Bootstrap Protocol V1 packets. */
object PairingBootstrapCodec {
    private val magic = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte())

    fun encode(packet: PairingBootstrapPacket): ByteArray {
        val payload = encodeMessagePayload(packet.message)
        require(payload.size <= UShort.MAX_VALUE.toInt())
        val size = PairingBootstrapProtocol.HEADER_BYTES + payload.size
        require(size <= PairingBootstrapProtocol.MAX_DATAGRAM_BYTES) {
            "Pairing Bootstrap V1 datagrams must remain below 1200 bytes"
        }
        return PairingWireWriter(size).apply {
            writeBytes(magic)
            writeByte(PairingBootstrapProtocol.VERSION)
            writeByte(packet.message.type.wireId)
            writeU16(0)
            writeAttemptId(packet.attemptId)
            writeU16(payload.size)
            writeU16(0)
            writeBytes(payload)
        }.toByteArray()
    }

    fun decode(datagram: ByteArray): PairingDecodeResult {
        if (datagram.size > PairingBootstrapProtocol.MAX_DATAGRAM_BYTES) {
            return PairingDecodeResult(error = PairingCodecError.OversizeDatagram)
        }
        if (datagram.size < PairingBootstrapProtocol.HEADER_BYTES) {
            return PairingDecodeResult(error = PairingCodecError.TruncatedHeader)
        }
        val reader = PairingWireReader(datagram)
        return try {
            if (!reader.readBytes(
                    4,
                ).contentEquals(magic)
            ) {
                return PairingDecodeResult(error = PairingCodecError.InvalidMagic)
            }
            if (reader.readByte() != PairingBootstrapProtocol.VERSION) {
                return PairingDecodeResult(error = PairingCodecError.UnsupportedVersion)
            }
            val messageType = PairingMessageType.fromWireId(reader.readByte())
                ?: return PairingDecodeResult(error = PairingCodecError.UnknownMessageType)
            if (reader.readU16() != 0) return PairingDecodeResult(error = PairingCodecError.NonZeroFlags)
            val attemptId = reader.readAttemptId()
                ?: return PairingDecodeResult(error = PairingCodecError.InvalidAttemptId)
            val payloadLength = reader.readU16()
            if (reader.readU16() != 0) return PairingDecodeResult(error = PairingCodecError.NonZeroReserved)
            if (payloadLength != reader.remaining()) {
                return PairingDecodeResult(
                    error = PairingCodecError.PayloadLengthMismatch,
                )
            }
            val payload = reader.readBytes(payloadLength)
            val message = decodeMessagePayload(messageType, payload)
                ?: return PairingDecodeResult(error = PairingCodecError.InvalidField)
            PairingDecodeResult(PairingBootstrapPacket(attemptId, message))
        } catch (_: PairingWireReadException) {
            PairingDecodeResult(error = PairingCodecError.TruncatedPayload)
        }
    }

    /** Canonical message bytes used by signatures and transcript construction, never unordered maps. */
    fun canonicalMessageBytes(message: PairingBootstrapMessage): ByteArray = encodeMessagePayload(message)

    private fun encodeMessagePayload(message: PairingBootstrapMessage): ByteArray = PairingWireWriter().apply {
        when (message) {
            is PairingBootstrapMessage.Commit -> {
                writeByte(message.suite.wireId)
                writeBytes(message.commitHash.bytes())
            }
            is PairingBootstrapMessage.Response -> {
                writeByte(message.suite.wireId)
                writeDeviceId(message.responderDeviceId)
                writeLengthPrefixed(message.responderIdentityPublicKey.encodedSpki())
                writeLengthPrefixed(message.responderEphemeralPublicKey.encodedSpki())
                writeBytes(message.responderNonce.bytes())
                writeBytes(message.commitHash.bytes())
                writeLengthPrefixed(message.signature.der())
            }
            is PairingBootstrapMessage.Reveal -> {
                writeInitiatorRevealMaterial(message.material)
                writeLengthPrefixed(message.signature.der())
            }
            is PairingBootstrapMessage.Confirm -> {
                writeBytes(message.transcriptHash.bytes())
                writeBytes(message.confirmationMac.bytes())
            }
            is PairingBootstrapMessage.Reject -> writeByte(message.reason.wireId)
            is PairingBootstrapMessage.Abort -> writeByte(message.reason.wireId)
        }
    }.toByteArray()

    private fun decodeMessagePayload(type: PairingMessageType, payload: ByteArray): PairingBootstrapMessage? {
        return try {
            val reader = PairingWireReader(payload)
            val message = when (type) {
                PairingMessageType.Commit -> {
                    val suite = PairingCryptoSuite.fromWireId(reader.readByte()) ?: return null
                    val hash = PairingHash.fromSha256(
                        reader.readBytes(PairingBootstrapProtocol.HASH_BYTES),
                    ) ?: return null
                    PairingBootstrapMessage.Commit(suite, hash)
                }
                PairingMessageType.Response -> {
                    val suite = PairingCryptoSuite.fromWireId(reader.readByte()) ?: return null
                    val deviceId = reader.readDeviceId() ?: return null
                    val identityKey = IdentityPublicKey.fromSpki(
                        reader.readLengthPrefixed(PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES),
                    )
                        ?: return null
                    val ephemeralKey = EphemeralPublicKey.fromSpki(
                        reader.readLengthPrefixed(PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES),
                    )
                        ?: return null
                    val nonce = PairingNonce.fromBytes(
                        reader.readBytes(PairingBootstrapProtocol.NONCE_BYTES),
                    ) ?: return null
                    val commitHash = PairingHash.fromSha256(
                        reader.readBytes(PairingBootstrapProtocol.HASH_BYTES),
                    ) ?: return null
                    val signature = PairingSignature.fromDer(
                        reader.readLengthPrefixed(PairingBootstrapProtocol.MAX_SIGNATURE_BYTES),
                    )
                        ?: return null
                    PairingBootstrapMessage.Response(
                        suite,
                        deviceId,
                        identityKey,
                        ephemeralKey,
                        nonce,
                        commitHash,
                        signature,
                    )
                }
                PairingMessageType.Reveal -> {
                    val material = reader.readInitiatorRevealMaterial() ?: return null
                    val signature = PairingSignature.fromDer(
                        reader.readLengthPrefixed(PairingBootstrapProtocol.MAX_SIGNATURE_BYTES),
                    )
                        ?: return null
                    PairingBootstrapMessage.Reveal(material, signature)
                }
                PairingMessageType.Confirm -> {
                    val transcriptHash = PairingHash.fromSha256(
                        reader.readBytes(PairingBootstrapProtocol.HASH_BYTES),
                    ) ?: return null
                    val confirmationMac = PairingConfirmationMac.fromSha256(
                        reader.readBytes(PairingBootstrapProtocol.HASH_BYTES),
                    ) ?: return null
                    PairingBootstrapMessage.Confirm(transcriptHash, confirmationMac)
                }
                PairingMessageType.Reject -> PairingRejectReason.fromWireId(reader.readByte())
                    ?.let(PairingBootstrapMessage::Reject) ?: return null
                PairingMessageType.Abort -> PairingAbortReason.fromWireId(reader.readByte())
                    ?.let(PairingBootstrapMessage::Abort) ?: return null
            }
            if (!reader.isExhausted()) return null
            message
        } catch (_: PairingWireReadException) {
            null
        }
    }

    internal fun PairingWireWriter.writeInitiatorRevealMaterial(material: InitiatorRevealMaterial) {
        writeAttemptId(material.pairingAttemptId)
        writeDeviceId(material.initiatorDeviceId)
        writeByte(material.suite.wireId)
        writeLengthPrefixed(material.initiatorIdentityPublicKey.encodedSpki())
        writeLengthPrefixed(material.initiatorEphemeralPublicKey.encodedSpki())
        writeBytes(material.initiatorNonce.bytes())
    }

    private fun PairingWireReader.readInitiatorRevealMaterial(): InitiatorRevealMaterial? {
        val attemptId = readAttemptId() ?: return null
        val deviceId = readDeviceId() ?: return null
        val suite = PairingCryptoSuite.fromWireId(readByte()) ?: return null
        val identityKey = IdentityPublicKey.fromSpki(readLengthPrefixed(PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES))
            ?: return null
        val ephemeralKey = EphemeralPublicKey.fromSpki(
            readLengthPrefixed(PairingBootstrapProtocol.MAX_PUBLIC_KEY_BYTES),
        )
            ?: return null
        val nonce = PairingNonce.fromBytes(readBytes(PairingBootstrapProtocol.NONCE_BYTES)) ?: return null
        return InitiatorRevealMaterial(attemptId, deviceId, suite, identityKey, ephemeralKey, nonce)
    }
}

internal class PairingWireWriter(initialSize: Int = 128) {
    private val output = ByteArrayOutputStream(initialSize)

    fun writeByte(value: Int) {
        require(value in 0..0xff)
        output.write(value)
    }

    fun writeU16(value: Int) {
        require(value in 0..0xffff)
        writeByte((value ushr 8) and 0xff)
        writeByte(value and 0xff)
    }

    fun writeULong(value: ULong) {
        for (shift in 56 downTo 0 step 8) writeByte(((value shr shift) and 0xffu).toInt())
    }

    fun writeAttemptId(value: PairingAttemptId) {
        writeULong(value.high)
        writeULong(value.low)
    }

    fun writeDeviceId(value: DeviceId) {
        writeULong(value.high)
        writeULong(value.low)
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

private class PairingWireReader(
    private val input: ByteArray,
) {
    private var index = 0

    fun remaining(): Int = input.size - index

    fun isExhausted(): Boolean = index == input.size

    fun readByte(): Int {
        if (index >= input.size) throw PairingWireReadException
        return input[index++].toInt() and 0xff
    }

    fun readU16(): Int = (readByte() shl 8) or readByte()

    fun readULong(): ULong {
        var result = 0uL
        repeat(8) { result = (result shl 8) or readByte().toULong() }
        return result
    }

    fun readAttemptId(): PairingAttemptId? = PairingAttemptId.fromParts(readULong(), readULong())

    fun readDeviceId(): DeviceId? = DeviceId.fromParts(readULong(), readULong())

    fun readLengthPrefixed(maximumLength: Int): ByteArray {
        val length = readU16()
        if (length !in 1..maximumLength) throw PairingWireReadException
        return readBytes(length)
    }

    fun readBytes(length: Int): ByteArray {
        if (length < 0 || length > remaining()) throw PairingWireReadException
        return input.copyOfRange(index, index + length).also { index += length }
    }
}

private data object PairingWireReadException : RuntimeException()
