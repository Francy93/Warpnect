package io.warpnect.session.handshake

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.identity.IdentityKeyAlgorithm
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.JcaSoftwareIdentitySigner
import io.warpnect.session.trust.InMemoryTrustedPeerStorePersistence
import io.warpnect.session.trust.TrustedPeerRecord
import io.warpnect.session.trust.TrustedPeerStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandshakeEngineTest {
    private val crypto = JcaPairingCryptoProvider()
    private val endpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 47_000)

    @Test
    fun fullCookieHandshakeAuthenticatesBothPeersAndDerivesOneFreshRoot() {
        val flow = flow()
        assertEquals(SessionHandshakeError.None, flow.client.receive(endpoint, flow.serverHello).error)
        val clientAuth = send(flow.client.receive(endpoint, flow.serverAuth))
        val serverResult = flow.server.receive(endpoint, clientAuth)
        val serverBootstrap = completed(serverResult).bootstrap
        val serverComplete = send(serverResult)
        val clientBootstrap = completed(flow.client.receive(endpoint, serverComplete)).bootstrap

        assertEquals(flow.clientSigner.identity.deviceId, serverBootstrap.remoteDeviceId)
        assertEquals(flow.serverSigner.identity.deviceId, clientBootstrap.remoteDeviceId)
        assertEquals(serverBootstrap.sessionId, clientBootstrap.sessionId)
        assertEquals(serverBootstrap.authenticatedTranscriptHash, clientBootstrap.authenticatedTranscriptHash)
        serverBootstrap.rootSecret.withSecretBytes { serverSecret ->
            clientBootstrap.rootSecret.withSecretBytes { clientSecret ->
                assertArrayEquals(serverSecret, clientSecret)
            }
        }
        serverBootstrap.rootSecret.close()
        assertTrue(serverBootstrap.rootSecret.isDestroyed)
        try {
            serverBootstrap.rootSecret.withSecretBytes { it }
            throw AssertionError("Destroyed root secret remained readable")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun clientDoesNotRevealIdentityWhenServerAuthIsTampered() {
        val flow = flow()
        assertEquals(SessionHandshakeError.None, flow.client.receive(endpoint, flow.serverHello).error)
        val tampered = flow.serverAuth.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val result = flow.client.receive(endpoint, tampered)
        assertEquals(SessionHandshakeError.DecryptFailure, result.error)
        assertFalse(result.actions.any { it is SessionHandshakeEngineAction.Send })
    }

    @Test
    fun freshAttemptsProduceDifferentEphemeralRoots() {
        val first = completedHandshake(flow())
        val second = completedHandshake(flow())
        assertNotEquals(first.first.authenticatedTranscriptHash, second.first.authenticatedTranscriptHash)
        assertFalse(
            first.first.rootSecret.withSecretBytes { one ->
                second.first.rootSecret.withSecretBytes { two ->
                    one.contentEquals(
                        two,
                    )
                }
            },
        )
    }

    private fun completedHandshake(flow: Flow): Pair<AuthenticatedSessionBootstrap, AuthenticatedSessionBootstrap> {
        flow.client.receive(endpoint, flow.serverHello)
        val serverResult = flow.server.receive(endpoint, send(flow.client.receive(endpoint, flow.serverAuth)))
        val serverBootstrap = completed(serverResult).bootstrap
        val clientBootstrap = completed(flow.client.receive(endpoint, send(serverResult))).bootstrap
        return serverBootstrap to clientBootstrap
    }

    private fun flow(): Flow {
        val clientSigner = signer(1u)
        val serverSigner = signer(2u)
        val clientTrust = trust(serverSigner)
        val serverTrust = trust(clientSigner)
        val attempt = SessionHandshakeAttemptId.requireValid(
            0u,
            crypto.randomBytes(8).fold(1uL) { value, byte ->
                (value shl 8) or (byte.toInt() and 0xff).toULong()
            },
        )
        val started = SessionHandshakeEngine.initiate(
            attempt,
            endpoint,
            SessionId.requireValid(
                0u,
                crypto.randomBytes(8).fold(1uL) { value, byte ->
                    (value shl 8) or (byte.toInt() and 0xff).toULong()
                },
            ),
            SessionGeneration.Initial,
            DiscoveryPresenceBinding.None,
            clientSigner,
            clientTrust,
            crypto,
        )
        val client = requireNotNull(started.engine)
        val initial = packet(send(started.result))
        val cookieManager = SessionHandshakeCookieManager(crypto, FixedClock(1_000L))
        val cookie = requireNotNull(cookieManager.issue(endpoint, initial))
        val retry =
            packet(
                requireNotNull(
                    SessionHandshakeCodec.encode(attempt, 1, SessionHandshakeMessage.HelloRetry(cookie)),
                ),
            )
        val cookieHello = packet(send(client.receive(endpoint, retry.datagram)))
        val serverStarted = SessionHandshakeEngine.respond(
            endpoint,
            initial,
            retry,
            cookieHello,
            serverSigner,
            serverTrust,
            crypto,
            reservationAdmission(),
        )
        val server = requireNotNull(serverStarted.engine)
        val sends = serverStarted.result.actions.filterIsInstance<SessionHandshakeEngineAction.Send>()
        return Flow(client, server, clientSigner, serverSigner, sends[0].datagram, sends[1].datagram)
    }

    private fun reservationAdmission(): SessionHandshakeAdmission = SessionHandshakeAdmission {
            sessionId,
            peer,
            _,
        ->
        SessionHandshakeAdmissionResult(
            SessionHandshakeError.None,
            object : AuthenticatedSessionAdmissionReservation {
                override val sessionId = sessionId
                override val peerDeviceId = peer
                override val expiresAtMonotonicMs = 31_000L
                override fun close() = Unit
            },
        )
    }

    private fun trust(signer: JcaSoftwareIdentitySigner): TrustedPeerStore = TrustedPeerStore(
        InMemoryTrustedPeerStorePersistence(),
        crypto::sha256,
    ).also { store ->
        assertTrue(
            store.bind(
                TrustedPeerRecord(
                    signer.identity.deviceId,
                    IdentityKeyAlgorithm.EcdsaP256Sha256,
                    signer.identity.publicKey,
                    signer.identity.fingerprint,
                    0L,
                    0L,
                ),
            ).isSuccess,
        )
    }

    private fun signer(value: ULong): JcaSoftwareIdentitySigner = JcaSoftwareIdentitySigner.generate(
        DeviceId.requireValid(0u, value),
        crypto,
    )
    private fun packet(bytes: ByteArray): SessionHandshakePacket =
        requireNotNull(SessionHandshakeCodec.decode(bytes).first)
    private fun send(result: SessionHandshakeEngineResult): ByteArray = requireNotNull(
        result.actions.filterIsInstance<SessionHandshakeEngineAction.Send>().lastOrNull(),
    ).datagram
    private fun completed(result: SessionHandshakeEngineResult): SessionHandshakeEngineAction.Completed =
        requireNotNull(
            result.actions.filterIsInstance<SessionHandshakeEngineAction.Completed>().singleOrNull(),
        )
    private class FixedClock(
        private val value: Long,
    ) : SessionHandshakeMonotonicClock {
        override fun nowMs(): Long = value
    }
    private data class Flow(
        val client: SessionHandshakeEngine,
        val server: SessionHandshakeEngine,
        val clientSigner: JcaSoftwareIdentitySigner,
        val serverSigner: JcaSoftwareIdentitySigner,
        val serverHello: ByteArray,
        val serverAuth: ByteArray,
    )
}
