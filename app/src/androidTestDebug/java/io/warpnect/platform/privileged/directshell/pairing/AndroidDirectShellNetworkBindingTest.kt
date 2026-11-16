package io.warpnect.platform.privileged.directshell.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.LinkAddress
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.NetworkOwner
import io.warpnect.platform.privileged.directshell.pairing.DirectShellNumericRouteCandidate.Snapshot
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in package only. No discovery, real pair, network configuration or external socket. */
class AndroidDirectShellNetworkBindingTest {
    @Test
    fun oneBoundSocketReachesOnlyTheLocalSyntheticPeer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        check(target.packageName == "io.warpnect.networkgate") { "network_fixture_package" }
        check(Build.VERSION.SDK_INT == 30 || Build.VERSION.SDK_INT == 31) { "network_fixture_api" }
        val manager = target.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = checkNotNull(manager.activeNetwork) { "no_active_network" }
        val observer = ObserveSelectedWifi(wifi)
        val client = Socket()
        val server = ServerSocket()
        var registered = false
        var accepted: Socket? = null
        try {
            manager.registerDefaultNetworkCallback(observer)
            registered = true
            check(observer.ready.await(3, TimeUnit.SECONDS)) { "no_coherent_wifi_snapshot" }
            val first = checkNotNull(observer.view()) { "wifi_not_admitted" }
            val local = first.links.singleOrNull() ?: error("expected_one_local_ipv4")
            val address = checkNotNull(local.ip.hostAddress)
            val epoch = 1L
            val competing = otherAddresses(manager, wifi)
            val snapshot = first.snapshot(observer.owner, epoch, competing)
            check(snapshot.owns(address, local.ip.address)) { "ambiguous_local_address" }
            val ticket = DirectShellPairingTicket("network-fixture", "one-attempt", 1, epoch, 0)
            val work = DirectShellPairingWorkContext(
                ticket,
                SystemClock.elapsedRealtime() + 5_000,
                DirectShellPairingClock(SystemClock::elapsedRealtime),
                DirectShellPairingScheduler { _, _ -> error("fixture_has_no_timer_dispatch") },
            )
            check(manager.activeNetwork == wifi) { "selected_network_changed" }
            server.soTimeout = 1_200
            server.bind(InetSocketAddress(local.ip, 0), 1)
            val stopped = AtomicBoolean()
            var binds = 0
            var connects = 0
            val owned = object : DirectShellNumericRouteCandidate.OwnedSocket {
                override fun bind(owner: NetworkOwner) {
                    assertSame(observer.owner, owner)
                    assertFalse(client.isConnected)
                    wifi.bindSocket(client)
                    assertFalse(client.isConnected)
                    binds++
                    // Pin the source explicitly; the loopback-to-self route is not an RF transmission proof.
                    client.bind(InetSocketAddress(local.ip, 0))
                }

                override fun connect(endpoint: InetSocketAddress, timeoutMillis: Int) {
                    assertEquals(1, binds)
                    assertEquals(InetSocketAddress(local.ip, server.localPort), endpoint)
                    connects++
                    client.connect(endpoint, minOf(timeoutMillis, 1_200))
                }
            }
            val stop: (DirectShellPairingTicket) -> Unit = { exact ->
                assertSame(ticket, exact)
                if (stopped.compareAndSet(false, true)) client.close()
            }
            observer.onInvalidated.set { stop(ticket) }
            assertNull(DirectShellNumericRouteCandidate.evaluate(work, snapshot, "127.0.0.1", 12345, owned, stop))
            val stale = first.snapshot(observer.owner, epoch + 1, competing)
            assertNull(DirectShellNumericRouteCandidate.evaluate(work, stale, address, server.localPort, owned, stop))
            val route = requireNotNull(
                DirectShellNumericRouteCandidate.evaluate(work, snapshot, address, server.localPort, owned, stop),
            )
            assertFalse(route.networkLost(NetworkOwner(), epoch))
            assertFalse(route.networkLost(observer.owner, epoch - 1)) // Modeled stale event, never a radio change.
            val current = {
                if (manager.activeNetwork != wifi) {
                    null
                } else {
                    observer.view()?.snapshot(observer.owner, epoch, otherAddresses(manager, wifi))
                }
            }
            assertTrue("binding_or_connect_refused", route.bindAndConnect(work, current) { !stopped.get() })
            assertFalse(route.bindAndConnect(work, current) { !stopped.get() })
            val peer = server.accept().also { accepted = it }
            assertEquals(client.localSocketAddress, peer.remoteSocketAddress)
            assertEquals(server.localSocketAddress, client.remoteSocketAddress)
            assertEquals(local.ip, client.localAddress)
            peer.soTimeout = 1_200
            client.soTimeout = 1_200
            client.getOutputStream().write(0x5a)
            assertEquals(0x5a, peer.getInputStream().read())
            peer.getOutputStream().write(0x2d)
            assertEquals(0x2d, client.getInputStream().read())
            assertEquals(1, binds)
            assertEquals(1, connects)
            assertFalse(stopped.get())
            assertTrue(current()?.sameLink(snapshot) == true)
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "NETWORK_GATE api=${Build.VERSION.SDK_INT} handle=${wifi.networkHandle} epoch=$epoch " +
                            "interface=${first.name} prefix=${local.prefix} bind_before_connect=1 owned_clients=1 " +
                            "exact_local_peer=true bidirectional=true stale_rejected=true loss_observed=false\n",
                    )
                },
            )
        } finally {
            try {
                accepted?.close()
            } finally {
                try {
                    client.close()
                } finally {
                    try {
                        server.close()
                    } finally {
                        if (registered) manager.unregisterNetworkCallback(observer)
                        observer.onInvalidated.set(null)
                    }
                }
            }
            assertTrue(client.isClosed && server.isClosed && (accepted?.isClosed != false))
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        "NETWORK_GATE cleanup=verified peer_client_server_closed=true callback_unregistered=true\n",
                    )
                },
            )
        }
    }

    @Suppress("DEPRECATION") // Read-only inventory; never request or migrate a connection.
    private fun otherAddresses(manager: ConnectivityManager, selected: Network) = manager.allNetworks
        .filter { it != selected }
        .flatMap { network -> manager.getLinkProperties(network)?.linkAddresses.orEmpty() }
        .mapNotNull { it.address.hostAddress }

    private data class Local(val ip: Inet4Address, val prefix: Int)

    private data class Link(val name: String, val links: List<Local>) {
        fun snapshot(owner: NetworkOwner, epoch: Long, competing: Collection<String>) = Snapshot(
            owner,
            epoch,
            name,
            links.map { LinkAddress(checkNotNull(it.ip.hostAddress), it.prefix) },
            competing,
        )
    }

    private class ObserveSelectedWifi(private val selected: Network) : ConnectivityManager.NetworkCallback() {
        val owner = NetworkOwner()
        val ready = CountDownLatch(1)
        val onInvalidated = AtomicReference<(() -> Unit)?>()
        private var wifi = false
        private var latest: Link? = null
        private var frozen: Link? = null
        private var invalid = false

        @Synchronized fun view(): Link? = if (invalid) null else frozen

        @Synchronized override fun onAvailable(network: Network) {
            if (network != selected) invalidate()
        }

        @Synchronized override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network != selected || invalid) return
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (!wifi) invalidate() else freezeWhenCoherent()
        }

        @Synchronized override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network != selected || invalid) return
            val name = properties.interfaceName ?: return invalidate()
            val links = properties.linkAddresses.mapNotNull {
                (it.address as? Inet4Address)?.let { ip -> Local(ip, it.prefixLength) }
            }.sortedBy { it.ip.hostAddress }
            latest = Link(name, links)
            if (frozen != null && latest != frozen) invalidate() else freezeWhenCoherent()
        }

        @Synchronized override fun onLost(network: Network) {
            if (network == selected) invalidate()
        }

        private fun freezeWhenCoherent() {
            if (wifi && latest != null && frozen == null) {
                frozen = latest
                ready.countDown()
            }
        }

        private fun invalidate() {
            if (invalid) return
            invalid = true
            ready.countDown()
            onInvalidated.get()?.invoke()
        }
    }
}
