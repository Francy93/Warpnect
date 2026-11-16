package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.LinkAddress
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.NetworkOwner
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.Snapshot
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellNumericPairingAttemptTest {
    private val network = NetworkOwner()
    private val ticket = DirectShellPairingTicket("numeric", "fixture", 1, 7, 0)
    private val clock = DirectShellPairingClock { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }
    private val work = DirectShellPairingWorkContext(
        ticket,
        clock.elapsedRealtimeMs() + 10_000,
        clock,
        { _, _ -> DirectShellPairingTimer {} },
    )
    private val selected = Snapshot(network, 7, "wlan0", listOf(LinkAddress("192.0.2.11", 24)), emptyList())
    private val binds = AtomicInteger()
    private val sockets = AtomicInteger()
    private val identities = AtomicInteger()

    @Test
    fun modeledRouteStillRequiresRealAuthenticatedPeerInfo() {
        OwnedPairingPeerFixture().use { peer ->
            val socket = RoutedSocket(peer.endpoint)
            val attempt = create(peer.endpoint.port, socket)
            try {
                assertTrue(start(attempt))
                assertFalse(start(attempt))
                attempt.transport.awaitWork()
                val drain = attempt.transport.awaitDrain()
                assertEquals(DirectShellPairingTransport.Outcome.PEER_INFO_VERIFIED, drain.outcome)
                val proof = checkNotNull(attempt.verifiedPeer())
                assertEquals(InetSocketAddress("192.0.2.11", peer.endpoint.port), proof.endpoint)
                val digest = MessageDigest.getInstance("SHA-256").digest(StorageFixture.identity.certificate.encoded)
                assertArrayEquals(digest, proof.localCertificateSha256)
                assertArrayEquals(digest, proof.remoteCertificateSha256)
                assertEquals(1, identities.get())
                assertEquals(1, binds.get())
                assertEquals(1, sockets.get())
                assertTrue(drain.drained && socket.isClosed)
                assertTrue(attempt.lost(network, 7))
                assertNull(attempt.verifiedPeer())
                assertEquals(DirectShellPairingExternalEffect.POSSIBLE, attempt.transport.observe().externalEffect)
            } finally {
                stop(attempt)
            }
        }
    }

    @Test
    fun exactLossClosesTheOnlySocketDuringPinnedTlsAndDoesNotClaimAuthentication() {
        OwnedPairingPeerFixture(OwnedPairingPeerFixture.Mode.TLS_STALL).use { peer ->
            val socket = RoutedSocket(peer.endpoint)
            val attempt = create(peer.endpoint.port, socket)
            try {
                assertTrue(start(attempt))
                peer.await()
                assertFalse(attempt.lost(NetworkOwner(), 7))
                assertFalse(attempt.lost(network, 6))
                assertTrue(attempt.lost(network, 7))
                val drain = attempt.transport.awaitDrain()
                assertTrue(drain.drained && socket.isClosed)
                assertEquals(DirectShellPairingTransport.Outcome.CANCELLED, drain.outcome)
                assertEquals(DirectShellPairingExternalEffect.POSSIBLE, drain.externalEffect)
                assertNull(attempt.verifiedPeer())
                assertFalse(start(attempt))
                assertEquals(1, sockets.get())
            } finally {
                stop(attempt)
            }
        }
    }

    @Test
    fun lossBeforeDispatchMakesNoSocketAndNoIdentity() {
        val attempt = create(31111, Socket())
        assertTrue(attempt.lost(network, 7))
        assertFalse(start(attempt))
        assertTrue(attempt.transport.observe().drained)
        assertEquals(DirectShellPairingExternalEffect.NONE_POSSIBLE, attempt.transport.observe().externalEffect)
        assertEquals(0, sockets.get())
        assertEquals(0, identities.get())
    }

    private fun create(port: Int, socket: Socket): DirectShellNumericPairingAttempt = checkNotNull(
        DirectShellNumericPairingAttempt.prepare(
            work, selected, { selected }, "192.0.2.11", port, { true }, { true },
            {
                identities.incrementAndGet()
                DirectShellOwnedPairingProtocol.Identity(
                    StorageFixture.identity.privateKey,
                    StorageFixture.identity.certificate,
                )
            },
            { binds.incrementAndGet() },
            { socket.also { sockets.incrementAndGet() } },
        ),
    )

    private fun start(attempt: DirectShellNumericPairingAttempt) =
        checkNotNull(DirectShellPairingCode.take(OwnedPairingPeerFixture.CODE.toCharArray())).use {
            attempt.transport.start(it)
        }

    private fun stop(attempt: DirectShellNumericPairingAttempt) {
        attempt.transport.requestStop(ticket)
        assertTrue(attempt.transport.awaitDrain().drained)
    }

    /** Models numeric routing only. Actual protocol traffic remains limited to the test-owned loopback peer. */
    private inner class RoutedSocket(private val peer: InetSocketAddress) : Socket() {
        private var reportedPeer: SocketAddress? = null

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            assertEquals(1, binds.get())
            assertTrue(timeout in 1..10_000)
            reportedPeer = endpoint
            super.connect(peer, timeout)
        }

        override fun getRemoteSocketAddress(): SocketAddress? = if (isConnected) reportedPeer else null
    }
}
