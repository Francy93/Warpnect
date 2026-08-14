package io.warpnect.session.pairing

import io.warpnect.session.DeviceId
import io.warpnect.session.identity.IdentityPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingBootstrapCodecTest {
    @Test
    fun allMessageTypesRoundTripThroughExactHeader() {
        val attempt = attempt(1u)
        val material = material(attempt)
        val messages = listOf(
            PairingBootstrapMessage.Commit(PairingCryptoSuite.P256Sha256, hash(1)),
            PairingBootstrapMessage.Response(
                PairingCryptoSuite.P256Sha256,
                device(2u),
                identityKey(2),
                ephemeralKey(3),
                nonce(4),
                hash(5),
                signature(6),
            ),
            PairingBootstrapMessage.Reveal(material, signature(7)),
            PairingBootstrapMessage.Confirm(hash(8), mac(9)),
            PairingBootstrapMessage.Reject(PairingRejectReason.UserRejected),
            PairingBootstrapMessage.Abort(PairingAbortReason.Timeout),
        )

        messages.forEach { message ->
            val encoded = PairingBootstrapCodec.encode(PairingBootstrapPacket(attempt, message))
            assertTrue(encoded.size < 1_200)
            assertArrayEquals(
                byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte()),
                encoded.copyOfRange(0, 4),
            )
            assertEquals(1, encoded[4].toInt() and 0xff)
            val decoded = PairingBootstrapCodec.decode(encoded)
            assertTrue(decoded.isSuccess)
            assertEquals(PairingBootstrapPacket(attempt, message), decoded.packet)
        }
    }

    @Test
    fun rejectsHeaderAndPayloadAmbiguity() {
        val encoded = PairingBootstrapCodec.encode(
            PairingBootstrapPacket(attempt(1u), PairingBootstrapMessage.Commit(PairingCryptoSuite.P256Sha256, hash(1))),
        )
        val badMagic = encoded.copyOf().also { it[0] = 0 }
        val badVersion = encoded.copyOf().also { it[4] = 2 }
        val badFlags = encoded.copyOf().also { it[6] = 1 }
        val badReserved = encoded.copyOf().also { it[26] = 1 }
        val wrongLength = encoded.copyOf().also { it[25] = 0 }

        assertEquals(PairingCodecError.InvalidMagic, PairingBootstrapCodec.decode(badMagic).error)
        assertEquals(PairingCodecError.UnsupportedVersion, PairingBootstrapCodec.decode(badVersion).error)
        assertEquals(PairingCodecError.NonZeroFlags, PairingBootstrapCodec.decode(badFlags).error)
        assertEquals(PairingCodecError.NonZeroReserved, PairingBootstrapCodec.decode(badReserved).error)
        assertEquals(PairingCodecError.PayloadLengthMismatch, PairingBootstrapCodec.decode(wrongLength).error)
        assertEquals(PairingCodecError.TruncatedHeader, PairingBootstrapCodec.decode(encoded.copyOf(27)).error)
        assertEquals(PairingCodecError.OversizeDatagram, PairingBootstrapCodec.decode(ByteArray(1_200)).error)
    }

    @Test
    fun canonicalCommitChangesForEveryInitiatorIdentityField() {
        val crypto = JcaPairingCryptoProvider()
        val base = material(attempt(1u))
        val original = PairingCanonical.commitment(crypto, base)
        val changedAttempt = PairingCanonical.commitment(crypto, base.copy(pairingAttemptId = attempt(2u)))
        val changedDevice = PairingCanonical.commitment(crypto, base.copy(initiatorDeviceId = device(22u)))
        val changedIdentityKey = PairingCanonical.commitment(
            crypto,
            base.copy(initiatorIdentityPublicKey = identityKey(23)),
        )
        val changedEphemeralKey = PairingCanonical.commitment(
            crypto,
            base.copy(initiatorEphemeralPublicKey = ephemeralKey(24)),
        )
        val changedNonce = PairingCanonical.commitment(crypto, base.copy(initiatorNonce = nonce(25)))

        listOf(changedAttempt, changedDevice, changedIdentityKey, changedEphemeralKey, changedNonce).forEach {
            assertNotEquals(original, it)
        }
    }

    @Test
    fun invalidZeroAttemptAndDeviceIdsNeverDecode() {
        val valid = PairingBootstrapCodec.encode(
            PairingBootstrapPacket(attempt(1u), PairingBootstrapMessage.Commit(PairingCryptoSuite.P256Sha256, hash(1))),
        )
        val zeroAttempt = valid.copyOf().also { bytes ->
            (8 until 24).forEach { index -> bytes[index] = 0 }
        }
        assertEquals(PairingCodecError.InvalidAttemptId, PairingBootstrapCodec.decode(zeroAttempt).error)

        val response = PairingBootstrapCodec.encode(
            PairingBootstrapPacket(
                attempt(2u),
                PairingBootstrapMessage.Response(
                    PairingCryptoSuite.P256Sha256,
                    device(2u),
                    identityKey(2),
                    ephemeralKey(3),
                    nonce(4),
                    hash(5),
                    signature(6),
                ),
            ),
        )
        val payloadDeviceOffset = PairingBootstrapProtocol.HEADER_BYTES + 1
        val zeroDevice = response.copyOf().also { bytes ->
            (payloadDeviceOffset until payloadDeviceOffset + 16).forEach { index -> bytes[index] = 0 }
        }
        assertFalse(PairingBootstrapCodec.decode(zeroDevice).isSuccess)
    }

    private fun material(attempt: PairingAttemptId): InitiatorRevealMaterial = InitiatorRevealMaterial(
        pairingAttemptId = attempt,
        initiatorDeviceId = device(1u),
        suite = PairingCryptoSuite.P256Sha256,
        initiatorIdentityPublicKey = identityKey(1),
        initiatorEphemeralPublicKey = ephemeralKey(2),
        initiatorNonce = nonce(3),
    )

    private fun attempt(value: ULong): PairingAttemptId = PairingAttemptId.requireValid(0u, value)

    private fun device(value: ULong): DeviceId = DeviceId.requireValid(0u, value)

    private fun hash(value: Int): PairingHash = PairingHash.requireSha256(ByteArray(32) { value.toByte() })

    private fun nonce(value: Int): PairingNonce = PairingNonce.requireBytes(ByteArray(32) { value.toByte() })

    private fun mac(value: Int): PairingConfirmationMac = PairingConfirmationMac.requireSha256(
        ByteArray(32) {
            value.toByte()
        },
    )

    private fun signature(value: Int): PairingSignature = PairingSignature.requireDer(ByteArray(70) { value.toByte() })

    private fun identityKey(value: Int): IdentityPublicKey =
        IdentityPublicKey.requireSpki(ByteArray(65) { (it + value).toByte() })

    private fun ephemeralKey(value: Int): EphemeralPublicKey =
        EphemeralPublicKey.requireSpki(ByteArray(65) { (it + value).toByte() })
}
