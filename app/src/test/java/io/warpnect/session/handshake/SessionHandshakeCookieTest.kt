package io.warpnect.session.handshake

import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionHandshakeCookieTest {
    @Test
    fun cookieBindsEndpointAndExactClientHello() {
        val crypto = JcaPairingCryptoProvider()
        val clock = MutableClock(1_000L)
        val manager = SessionHandshakeCookieManager(crypto, clock)
        val endpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(10, 0, 0, 1), 1234)
        val attempt = SessionHandshakeAttemptId.requireValid(0u, 9u)
        val ephemeral = (
            crypto.generateEphemeralKeyPair() as io.warpnect.session.pairing.PairingCryptoResult.Value
            ).value
        val hello = SessionHandshakeMessage.ClientHello(
            1,
            SessionId.requireValid(
                0u,
                4u,
            ),
            SessionGeneration.Initial,
            SessionRole.Client,
            SessionRole.Host,
            DiscoveryPresenceBinding.None,
            HandshakeNonce.requireValid(
                ByteArray(
                    32,
                ) {
                    7
                },
            ),
            ephemeral.publicKey,
        )
        val initial = packet(requireNotNull(SessionHandshakeCodec.encode(attempt, 0, hello)))
        val cookie = requireNotNull(manager.issue(endpoint, initial))
        val retry = packet(
            requireNotNull(
                SessionHandshakeCodec.encode(attempt, 2, hello.copy(retryCookie = cookie)),
            ),
        )
        assertEquals(SessionHandshakeError.None, manager.validate(endpoint, initial, retry))
        assertEquals(
            SessionHandshakeError.CookieInvalid,
            manager.validate(
                HandshakeTransportEndpoint.requireValid(byteArrayOf(10, 0, 0, 2), 1234),
                initial,
                retry,
            ),
        )
        clock.now = 31_001L
        assertEquals(SessionHandshakeError.CookieExpired, manager.validate(endpoint, initial, retry))
    }

    private fun packet(bytes: ByteArray): SessionHandshakePacket = requireNotNull(
        SessionHandshakeCodec.decode(bytes).first,
    )
    private class MutableClock(var now: Long) : SessionHandshakeMonotonicClock {
        override fun nowMs(): Long = now
    }
}
