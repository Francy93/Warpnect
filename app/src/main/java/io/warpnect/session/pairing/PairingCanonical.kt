package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.ImmutableBytes

/** Canonical byte construction for every value signed, hashed, or MACed by Pairing Bootstrap V1. */
object PairingCanonical {
    private val commitDomain = "Warpnect Pairing Commit v1".encodeToByteArray()
    private val responseSignatureDomain = "Warpnect Pairing Response Signature v1".encodeToByteArray()
    private val revealSignatureDomain = "Warpnect Pairing Reveal Signature v1".encodeToByteArray()
    private val transcriptDomain = "Warpnect Pairing Transcript v1".encodeToByteArray()
    private val initiatorConfirmDomain = "Warpnect Pairing Initiator Confirm v1".encodeToByteArray()
    private val responderConfirmDomain = "Warpnect Pairing Responder Confirm v1".encodeToByteArray()
    private val sasDomain = "Warpnect Pairing SAS v1".encodeToByteArray()
    private val confirmationContextDomain = "Warpnect Pairing Confirmation v1".encodeToByteArray()
    private val sasRejectionDomain = "Warpnect Pairing SAS Rejection v1".encodeToByteArray()

    fun commitment(crypto: PairingCryptoProvider, material: InitiatorRevealMaterial): PairingHash =
        PairingHash.requireSha256(crypto.sha256(concat(commitDomain, initiatorRevealMaterial(material))))

    fun responseSignatureInput(attemptId: PairingAttemptId, response: PairingBootstrapMessage.Response): ByteArray =
        PairingWireWriter().apply {
            writeLengthPrefixed(responseSignatureDomain)
            writeAttemptId(attemptId)
            writeByte(response.suite.wireId)
            writeBytes(response.commitHash.bytes())
            writeDeviceId(response.responderDeviceId)
            writeLengthPrefixed(response.responderIdentityPublicKey.encodedSpki())
            writeLengthPrefixed(response.responderEphemeralPublicKey.encodedSpki())
            writeBytes(response.responderNonce.bytes())
        }.toByteArray()

    fun revealSignatureInput(
        attemptId: PairingAttemptId,
        commitHash: PairingHash,
        response: PairingBootstrapMessage.Response,
        material: InitiatorRevealMaterial,
        crypto: PairingCryptoProvider,
    ): ByteArray = PairingWireWriter().apply {
        writeLengthPrefixed(revealSignatureDomain)
        writeAttemptId(attemptId)
        writeByte(material.suite.wireId)
        writeBytes(commitHash.bytes())
        writeBytes(crypto.sha256(PairingBootstrapCodec.canonicalMessageBytes(response)))
        writeLengthPrefixed(initiatorRevealMaterial(material))
    }.toByteArray()

    fun transcriptHash(
        crypto: PairingCryptoProvider,
        attemptId: PairingAttemptId,
        commit: PairingBootstrapMessage.Commit,
        response: PairingBootstrapMessage.Response,
        reveal: PairingBootstrapMessage.Reveal,
    ): PairingHash = PairingHash.requireSha256(
        crypto.sha256(
            PairingWireWriter().apply {
                writeLengthPrefixed(transcriptDomain)
                writeAttemptId(attemptId)
                writeLengthPrefixed(PairingBootstrapCodec.canonicalMessageBytes(commit))
                writeLengthPrefixed(PairingBootstrapCodec.canonicalMessageBytes(response))
                writeLengthPrefixed(PairingBootstrapCodec.canonicalMessageBytes(reveal))
            }.toByteArray(),
        ),
    )

    fun deriveKeys(
        crypto: PairingCryptoProvider,
        transcriptHash: PairingHash,
        sharedSecret: ImmutableBytes,
    ): PairingDerivedKeys {
        val prk = crypto.hkdfExtract(transcriptHash.bytes(), sharedSecret.toByteArray())
        return PairingDerivedKeys(
            initiatorConfirmKey = ImmutableBytes.copyOf(
                crypto.hkdfExpand(prk, initiatorConfirmDomain, PairingBootstrapProtocol.HASH_BYTES),
            ),
            responderConfirmKey = ImmutableBytes.copyOf(
                crypto.hkdfExpand(prk, responderConfirmDomain, PairingBootstrapProtocol.HASH_BYTES),
            ),
            sasMaterial = ImmutableBytes.copyOf(
                crypto.hkdfExpand(prk, sasDomain, PairingBootstrapProtocol.HASH_BYTES),
            ),
        ).also { prk.fill(0) }
    }

    fun confirmationContext(
        attemptId: PairingAttemptId,
        transcriptHash: PairingHash,
        initiatorDeviceId: DeviceId,
        responderDeviceId: DeviceId,
        senderRole: PairingRole,
    ): ByteArray = PairingWireWriter().apply {
        writeLengthPrefixed(confirmationContextDomain)
        writeAttemptId(attemptId)
        writeBytes(transcriptHash.bytes())
        writeDeviceId(initiatorDeviceId)
        writeDeviceId(responderDeviceId)
        writeByte(if (senderRole == PairingRole.Initiator) 1 else 2)
        writeByte(1) // accepted
    }.toByteArray()

    fun confirmationKeyLabel(role: PairingRole): ByteArray =
        if (role == PairingRole.Initiator) initiatorConfirmDomain.copyOf() else responderConfirmDomain.copyOf()

    fun sas(crypto: PairingCryptoProvider, material: ImmutableBytes): String? {
        var current = material.toByteArray()
        repeat(MAX_SAS_REJECTION_ROUNDS) { round ->
            var offset = 0
            while (offset + 4 <= current.size) {
                val candidate = ((current[offset].toInt() and 0xff).toULong() shl 24) or
                    ((current[offset + 1].toInt() and 0xff).toULong() shl 16) or
                    ((current[offset + 2].toInt() and 0xff).toULong() shl 8) or
                    (current[offset + 3].toInt() and 0xff).toULong()
                if (candidate < SAS_REJECTION_LIMIT) {
                    return (candidate % 1_000_000u).toString().padStart(6, '0')
                }
                offset += 4
            }
            val nextInput = PairingWireWriter().apply {
                writeLengthPrefixed(sasRejectionDomain)
                writeBytes(current)
                writeU16(round)
            }.toByteArray()
            current.fill(0)
            current = crypto.sha256(nextInput)
        }
        current.fill(0)
        return null
    }

    fun initiatorRevealMaterial(material: InitiatorRevealMaterial): ByteArray = PairingWireWriter().apply {
        writeAttemptId(material.pairingAttemptId)
        writeDeviceId(material.initiatorDeviceId)
        writeByte(material.suite.wireId)
        writeLengthPrefixed(material.initiatorIdentityPublicKey.encodedSpki())
        writeLengthPrefixed(material.initiatorEphemeralPublicKey.encodedSpki())
        writeBytes(material.initiatorNonce.bytes())
    }.toByteArray()

    private fun concat(left: ByteArray, right: ByteArray): ByteArray = ByteArray(left.size + right.size).also {
        left.copyInto(it)
        right.copyInto(it, left.size)
    }

    private const val SAS_REJECTION_LIMIT: ULong = 4_294_000_000uL
    private const val MAX_SAS_REJECTION_ROUNDS: Int = 16
}

data class PairingDerivedKeys(
    val initiatorConfirmKey: ImmutableBytes,
    val responderConfirmKey: ImmutableBytes,
    val sasMaterial: ImmutableBytes,
) {
    fun destroy() {
        initiatorConfirmKey.wipe()
        responderConfirmKey.wipe()
        sasMaterial.wipe()
    }
}
