package io.warpnect.platform.privileged.directshell.pairing

import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.LinkAddress
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.NetworkOwner
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.Snapshot
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellNumericRouteCandidateTest {
    private val token = NetworkOwner()
    private val ticket = DirectShellPairingTicket("owner", "route", 1, 7, 0)
    private var now = 2_000L
    private val work = DirectShellPairingWorkContext(ticket, 10_000, { now }, { _, _ -> DirectShellPairingTimer {} })
    private val called = mutableListOf<String>()
    private val stops = mutableListOf<DirectShellPairingTicket>()
    private var onBind: () -> Unit = {}
    private var onConnect: () -> Unit = {}
    private val socket = object : DirectShellNumericRouteCandidate.OwnedSocket {
        override fun bind(owner: NetworkOwner) {
            assertSame(token, owner)
            called.add("bind")
            onBind()
        }

        override fun connect(endpoint: InetSocketAddress, timeoutMillis: Int) {
            assertEquals("192.168.1.25", endpoint.address.hostAddress)
            assertEquals(50227, endpoint.port)
            assertEquals(8_000, timeoutMillis)
            called.add("connect")
            onConnect()
        }
    }

    private fun snapshot(
        owner: NetworkOwner = token,
        epoch: Long = 7,
        ip: String = "192.168.1.25",
        other: Collection<String> = emptyList(),
    ) = Snapshot(owner, epoch, "wlan0", listOf(LinkAddress(ip, 24)), other)

    private fun evaluate(s: Snapshot, ip: String = "192.168.1.25", port: Int = 50227) =
        DirectShellNumericRouteCandidate.evaluate(work, s, ip, port, socket, stops::add)

    @Test fun onlyOneBoundConnectForTheExactWorkAndSnapshot() {
        val route = requireNotNull(evaluate(snapshot()))
        val clonedWork = DirectShellPairingWorkContext(ticket, 10_000, { now }, work.scheduler)
        assertFalse(route.bindAndConnect(clonedWork, ::snapshotForCallback) { true })
        assertTrue(route.bindAndConnect(work, ::snapshotForCallback) { true })
        assertFalse(route.bindAndConnect(work, ::snapshotForCallback) { true })
        assertEquals(listOf("bind", "connect"), called)
        assertTrue(stops.isEmpty())
    }

    @Test fun rejectsExternalAmbiguousStaleAndNonNumericRoutingHints() {
        val s = snapshot()
        for (ip in listOf("phone.local", "192.168.1.26", "192.168.001.25", "::ffff:192.168.1.25", "127.0.0.1")) {
            assertNull(evaluate(s, ip))
        }
        for (ip in listOf("224.1.1.4", "169.254.1.5", "192.168.1.0", "192.168.1.255", "0.2.3.4")) {
            assertNull(evaluate(snapshot(ip = ip), ip))
        }
        assertNull(evaluate(snapshot(epoch = 6)))
        assertNull(evaluate(snapshot(other = listOf("192.168.1.25"))))
        assertNull(evaluate(s, port = 0))
        assertTrue(called.isEmpty())
    }

    @Test fun snapshotsAreDefensivelyCopiedAndChangedLinkCannotConnect() {
        val addresses = mutableListOf(LinkAddress("192.168.1.25", 24))
        val frozen = Snapshot(token, 7, "wlan0", addresses, emptyList())
        addresses.clear()
        val route = requireNotNull(evaluate(frozen))
        assertFalse(route.bindAndConnect(work, { snapshot(owner = NetworkOwner()) }) { true })
        assertTrue(called.isEmpty())
        assertEquals(listOf(ticket), stops)
    }

    @Test fun invalidationDuringBindStopsOnlyTheExactOperation() {
        val route = requireNotNull(evaluate(snapshot()))
        onBind = {
            assertFalse(route.networkLost(token, 6))
            assertFalse(route.networkLost(NetworkOwner(), 7))
            assertTrue(route.networkLost(token, 7))
        }
        assertFalse(route.bindAndConnect(work, ::snapshotForCallback) { true })
        assertEquals(listOf("bind"), called)
        assertEquals(listOf(ticket), stops)
    }

    @Test fun driftAfterConnectIsNotSuccessfulAdmission() {
        var current = snapshot()
        val route = requireNotNull(evaluate(current))
        onConnect = { current = snapshot(ip = "192.168.1.26") }
        assertFalse(route.bindAndConnect(work, { current }) { true })
        assertEquals(listOf("bind", "connect"), called)
        assertEquals(listOf(ticket), stops)
    }

    @Test fun deadlineDuringBindDoesNotGrantEvenOneMillisecondOfConnect() {
        val route = requireNotNull(evaluate(snapshot()))
        onBind = { now = 10_000 }
        assertFalse(route.bindAndConnect(work, ::snapshotForCallback) { true })
        assertEquals(listOf("bind"), called)
        assertEquals(listOf(ticket), stops)
    }

    private fun snapshotForCallback() = snapshot()
}
