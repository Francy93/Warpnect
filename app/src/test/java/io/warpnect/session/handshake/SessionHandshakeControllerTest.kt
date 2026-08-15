package io.warpnect.session.handshake

import io.warpnect.session.DeviceId
import io.warpnect.session.DuplicatePeerSessionPolicy
import io.warpnect.session.SessionBehaviorPolicy
import io.warpnect.session.SessionId
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import io.warpnect.session.identity.IdentityKeyAlgorithm
import io.warpnect.session.pairing.JcaPairingCryptoProvider
import io.warpnect.session.pairing.JcaSoftwareIdentitySigner
import io.warpnect.session.trust.InMemoryTrustedPeerStorePersistence
import io.warpnect.session.trust.TrustedPeerRecord
import io.warpnect.session.trust.TrustedPeerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHandshakeControllerTest {
    private val crypto = JcaPairingCryptoProvider()
    private val clientEndpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 47_001)
    private val serverEndpoint = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 47_002)

    @Test
    fun outgoingAttemptsAreBoundedBeforeCreatingMoreHandshakeState() {
        val signer = signer(1u)
        val transport = QueuedTransport()
        val controller = SessionHandshakeController(
            transport,
            signer,
            TrustedPeerStore(InMemoryTrustedPeerStorePersistence(), crypto::sha256),
            manager(signer.identity.deviceId),
            crypto,
            SessionHandshakeConfig(maxOutgoingAttempts = 1),
        )

        assertEquals(
            SessionHandshakeError.None,
            controller.startInitiator(serverEndpoint, session(1u)).error,
        )
        assertEquals(
            SessionHandshakeError.AtCapacity,
            controller.startInitiator(serverEndpoint, session(2u)).error,
        )
        assertEquals(1, controller.snapshot().activeOutgoingAttempts)
        controller.close()
    }

    @Test
    fun completedHandshakeCacheEvictsOldestEntryAtItsFixedCapacity() {
        val clientSigner = signer(1u)
        val serverSigner = signer(2u)
        val clientTransport = QueuedTransport()
        val serverTransport = QueuedTransport()
        val client = SessionHandshakeController(
            clientTransport,
            clientSigner,
            trust(serverSigner),
            manager(clientSigner.identity.deviceId),
            crypto,
            eventListener = SessionHandshakeEventListener { it.rootSecret.close() },
        )
        val server = SessionHandshakeController(
            serverTransport,
            serverSigner,
            trust(clientSigner),
            manager(serverSigner.identity.deviceId),
            crypto,
            eventListener = SessionHandshakeEventListener { bootstrap ->
                bootstrap.rootSecret.close()
                bootstrap.admissionReservation?.close()
            },
        )

        repeat(SessionHandshakeProtocol.RECENT_COMPLETED_CAPACITY + 1) { index ->
            assertEquals(
                SessionHandshakeError.None,
                client.startInitiator(serverEndpoint, session((index + 1).toULong())).error,
            )
            drain(clientTransport, serverTransport, client, server)
        }

        assertEquals(
            SessionHandshakeProtocol.RECENT_COMPLETED_CAPACITY,
            server.snapshot().recentCompletedCacheSize,
        )
        assertEquals(
            (SessionHandshakeProtocol.RECENT_COMPLETED_CAPACITY + 1).toLong(),
            server.snapshot().successfulHandshakes,
        )
        client.close()
        server.close()
    }

    private fun drain(
        clientTransport: QueuedTransport,
        serverTransport: QueuedTransport,
        client: SessionHandshakeController,
        server: SessionHandshakeController,
    ) {
        while (clientTransport.hasPending || serverTransport.hasPending) {
            clientTransport.drain().forEach { server.receive(clientEndpoint, it) }
            serverTransport.drain().forEach { client.receive(serverEndpoint, it) }
        }
    }

    private fun manager(localDeviceId: DeviceId): SessionManager = SessionManager(
        SessionManagerConfig(
            localDeviceId = localDeviceId,
            initialPolicy = SessionBehaviorPolicy(
                maxConcurrentClients = 8,
                duplicatePeerSessionPolicy = DuplicatePeerSessionPolicy.MultipleSessionsPerPeer,
            ),
        ),
    )

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
    private fun session(value: ULong): SessionId = SessionId.requireValid(0u, value)

    private class QueuedTransport : SessionHandshakeTransport {
        private var listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
        private val pending = ArrayDeque<ByteArray>()
        val hasPending: Boolean get() = pending.isNotEmpty()

        override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean {
            pending += datagram.copyOf()
            return true
        }

        fun drain(): List<ByteArray> = buildList {
            while (pending.isNotEmpty()) add(pending.removeFirst())
        }

        override fun close() {
            pending.clear()
            listener = null
        }
    }
}
