package io.warpnect.session.handshake

import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandshakeCodecTest {
    private val crypto = JcaPairingCryptoProvider()

    @Test
    fun headerIs32BytesBigEndianAndRejectsReservedMutations() {
        val bytes = requireNotNull(
            SessionHandshakeCodec.encode(
                SessionHandshakeAttemptId.requireValid(0u, 1u),
                0,
                hello(),
            ),
        )
        assertEquals(32, SessionHandshakeProtocol.HEADER_BYTES)
        assertEquals('W'.code.toByte(), bytes[0])
        assertEquals(1, bytes[4].toInt())
        assertEquals(SessionHandshakeError.None, SessionHandshakeCodec.decode(bytes).second)
        val badReserved = bytes.copyOf().also { it[31] = 1 }
        assertNull(SessionHandshakeCodec.decode(badReserved).first)
        val badFlags = bytes.copyOf().also { it[6] = 0x04 }
        assertNull(SessionHandshakeCodec.decode(badFlags).first)
    }

    @Test
    fun encryptedAeadAuthenticatesTheExactHeader() {
        val key = ByteArray(16) { 1 }
        val iv = ByteArray(12) { 2 }
        val header =
            SessionHandshakeHeader(
                SessionHandshakeMessageType.ServerAuth,
                2,
                SessionHandshakeAttemptId.requireValid(0u, 5u),
                4,
                19,
            )
        val aad = SessionHandshakeCodec.encodeHeader(header)
        val encrypted = crypto.aes128GcmEncrypt(
            key,
            SessionHandshakeCanonical.aeadNonce(iv, 4),
            aad,
            byteArrayOf(1, 2, 3),
        )
        val cipher = (encrypted as io.warpnect.session.pairing.PairingCryptoResult.Value).value
        assertTrue(
            crypto.aes128GcmDecrypt(
                key,
                SessionHandshakeCanonical.aeadNonce(iv, 4),
                aad,
                cipher,
            ) is io.warpnect.session.pairing.PairingCryptoResult.Value,
        )
        assertTrue(
            crypto.aes128GcmDecrypt(
                key,
                SessionHandshakeCanonical.aeadNonce(iv, 5),
                aad,
                cipher,
            ) is io.warpnect.session.pairing.PairingCryptoResult.Failed,
        )
    }

    private fun hello(): SessionHandshakeMessage.ClientHello {
        val ephemeral = (
            crypto.generateEphemeralKeyPair() as io.warpnect.session.pairing.PairingCryptoResult.Value
            ).value
        return SessionHandshakeMessage.ClientHello(
            1,
            SessionId.requireValid(
                0u,
                2u,
            ),
            SessionGeneration.Initial,
            SessionRole.Client,
            SessionRole.Host,
            DiscoveryPresenceBinding.None,
            HandshakeNonce.requireValid(
                ByteArray(
                    32,
                ) {
                    3
                },
            ),
            ephemeral.publicKey,
        )
    }
}
