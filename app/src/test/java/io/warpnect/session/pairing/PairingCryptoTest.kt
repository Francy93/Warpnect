package io.warpnect.session.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {
    private val crypto = JcaPairingCryptoProvider()

    @Test
    fun hkdfSha256MatchesRfc5869TestCaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = crypto.hkdfExtract(salt, ikm)
        val okm = crypto.hkdfExpand(prk, info, 42)

        assertArrayEquals(
            hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
            prk,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a" + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" + "34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun p256GeneratedKeysValidateAndAgreeOnOneSecret() {
        val first = requireEphemeral(crypto.generateEphemeralKeyPair())
        val second = requireEphemeral(crypto.generateEphemeralKeyPair())
        try {
            assertTrue(crypto.isValidP256PublicKey(first.publicKey.encodedSpki()))
            assertTrue(crypto.isValidP256PublicKey(second.publicKey.encodedSpki()))
            assertFalse(crypto.isValidP256PublicKey(byteArrayOf(1, 2, 3)))
            val firstSecret = requireBytes(first.deriveSharedSecret(second.publicKey)).toByteArray()
            val secondSecret = requireBytes(second.deriveSharedSecret(first.publicKey)).toByteArray()
            assertArrayEquals(firstSecret, secondSecret)
            firstSecret.fill(0)
            secondSecret.fill(0)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun sasUsesSixDigitsAndRejectsBiasedFirstCandidate() {
        val material = io.warpnect.session.identity.ImmutableBytes.copyOf(
            byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) + ByteArray(28),
        )
        val sas = requireNotNull(PairingCanonical.sas(crypto, material))
        assertEquals(6, sas.length)
        assertTrue(sas.all(Char::isDigit))
    }

    private fun requireEphemeral(result: PairingCryptoResult<PairingEphemeralKeyPair>): PairingEphemeralKeyPair =
        when (result) {
            is PairingCryptoResult.Value -> result.value
            is PairingCryptoResult.Failed -> throw AssertionError(result.detail)
        }

    private fun requireBytes(
        result: PairingCryptoResult<io.warpnect.session.identity.ImmutableBytes>,
    ): io.warpnect.session.identity.ImmutableBytes = when (result) {
        is PairingCryptoResult.Value -> result.value
        is PairingCryptoResult.Failed -> throw AssertionError(result.detail)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
