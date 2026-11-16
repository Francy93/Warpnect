package io.warpnect.platform.privileged.directshell.pairing

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit private observer, never registered from an app startup, carrier reply or readiness publication. */
internal class DirectShellAndroidWifiSelection private constructor(
    private val manager: ConnectivityManager,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val consumed = AtomicBoolean()
    private val fence = DirectShellWifiEpochFence<Network>()
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = fence.available(network)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            fence.capabilities(network, capabilities.isSuitable())

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
            fence.link(network, properties.observation())

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = fence.blocked(network, blocked)

        override fun onLost(network: Network) = fence.lost(network)
    }

    class OwnedAttempt(
        val attempt: DirectShellNumericPairingAttempt,
        private val watch: DirectShellWifiEpochFence.Watch,
        private val stop: () -> Unit,
    ) : AutoCloseable {
        override fun close() {
            try {
                stop()
            } finally {
                watch.close()
            }
        }
    }

    fun observedEpoch(): Long? = fence.current()?.let { observation ->
        if (!closed.get() && manager.activeNetwork == observation.network && fence.current() === observation) {
            observation.number
        } else {
            null
        }
    }

    fun prepare(
        work: DirectShellPairingWorkContext,
        numericIp: String,
        pairingPort: Int,
        active: () -> Boolean,
        recoveryReady: () -> Boolean,
        identity: () -> DirectShellOwnedPairingProtocol.Identity,
    ): OwnedAttempt? {
        if (consumed.get()) return null
        val observed = fence.current() ?: return null
        if (work.ticket.networkEpoch != observed.number) return null
        val selection = DirectShellNumericPairingAttempt.SelectedNetwork(observed.network)
        val initial = snapshot(observed, selection.owner) ?: return null
        val attempt = selection.prepare(
            work,
            initial,
            { snapshot(observed, selection.owner) },
            numericIp,
            pairingPort,
            active,
            recoveryReady,
            identity,
        ) ?: return null
        val stop: () -> Unit = { attempt.lost(selection.owner, observed.number) }
        val watch = fence.watch(observed, stop) ?: return null
        if (!consumed.compareAndSet(false, true)) {
            watch.close()
            stop()
            return null
        }
        return OwnedAttempt(attempt, watch, stop)
    }

    // Never query ConnectivityManager synchronously from a network callback.
    @Suppress("DEPRECATION")
    private fun snapshot(
        expected: DirectShellWifiEpochFence.Epoch<Network>,
        owner: DirectShellNumericRouteCandidate.NetworkOwner,
    ): DirectShellNumericRouteCandidate.Snapshot? {
        if (!stillSelected(expected)) return null
        val competing = try {
            manager.allNetworks.filterNot { it == expected.network }
                .flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
                .mapNotNull { it.address.hostAddress }
        } catch (_: SecurityException) {
            return null
        }
        if (!stillSelected(expected)) return null
        return DirectShellNumericRouteCandidate.Snapshot(
            owner,
            expected.number,
            expected.link.interfaceName,
            expected.link.addresses,
            competing,
        )
    }

    private fun stillSelected(expected: DirectShellWifiEpochFence.Epoch<Network>): Boolean = try {
        !closed.get() && fence.verify(expected) { network ->
            val isDefault = manager.activeNetwork == network
            val suitable = manager.getNetworkCapabilities(network)?.isSuitable() == true
            if (!isDefault || !suitable) {
                null
            } else {
                manager.getLinkProperties(network)?.observation()?.takeIf { manager.activeNetwork == network }
            }
        } && !closed.get()
    } catch (_: SecurityException) {
        false
    }

    private fun NetworkCapabilities.isSuitable() = hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)

    private fun LinkProperties.observation(): DirectShellWifiEpochFence.Link? {
        val name = interfaceName ?: return null
        val addresses = linkAddresses.mapNotNull {
            (it.address as? Inet4Address)?.hostAddress?.let { ip ->
                DirectShellNumericRouteCandidate.LinkAddress(ip, it.prefixLength)
            }
        }.sortedWith(compareBy({ it.address }, { it.prefix }))
        return DirectShellWifiEpochFence.Link(name, addresses)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            fence.close()
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }

    companion object {
        fun start(manager: ConnectivityManager): DirectShellAndroidWifiSelection {
            require(Build.VERSION.SDK_INT >= 30)
            return DirectShellAndroidWifiSelection(manager).also { manager.registerDefaultNetworkCallback(it.callback) }
        }
    }
}
