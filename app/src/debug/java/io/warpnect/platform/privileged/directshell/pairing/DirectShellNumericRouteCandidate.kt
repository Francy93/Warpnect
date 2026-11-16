package io.warpnect.platform.privileged.directshell.pairing

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Same-device routing model only. Neither NSD data nor a route can attest an authenticated peer. */
internal object DirectShellNumericRouteCandidate {
    class NetworkOwner

    data class LinkAddress(val address: String, val prefix: Int)

    class Snapshot(
        val owner: NetworkOwner,
        val epoch: Long,
        val interfaceName: String,
        addresses: Collection<LinkAddress>,
        addressesOnOtherLinks: Collection<String>,
    ) {
        private val owned = addresses.toList()
        private val competing = addressesOnOtherLinks.toSet()

        fun owns(address: String, bytes: ByteArray): Boolean {
            if (!interfaceName.matches(INTERFACE) || address in competing) return false
            val link = owned.singleOrNull { it.address == address } ?: return false
            if (link.prefix !in 1..30) return false
            val value = bytes.fold(0L) { acc, octet -> (acc shl 8) or (octet.toLong() and 255) }
            val mask = (1L shl (32 - link.prefix)) - 1
            return (value and mask) !in setOf(0L, mask)
        }

        fun sameLink(other: Snapshot): Boolean = owner === other.owner && epoch == other.epoch &&
            interfaceName == other.interfaceName && owned == other.owned && competing == other.competing
    }

    /** One exact already-owned, unconnected socket. An eventual adapter must bind before TCP connect. */
    interface OwnedSocket {
        fun bind(owner: NetworkOwner)
        fun connect(endpoint: InetSocketAddress, timeoutMillis: Int)
    }

    sealed interface Route {
        fun networkLost(owner: NetworkOwner, epoch: Long): Boolean
        fun bindAndConnect(
            originalWork: DirectShellPairingWorkContext,
            current: () -> Snapshot?,
            operationActive: () -> Boolean,
        ): Boolean
    }

    private class Admission(
        val work: DirectShellPairingWorkContext,
        val selected: Snapshot,
        val endpoint: InetSocketAddress,
    )

    sealed interface Binding {
        val endpoint: InetSocketAddress

        fun matches(
            ticket: DirectShellPairingTicket,
            endpoint: InetSocketAddress,
            clock: DirectShellPairingClock,
            deadline: Long,
        ): Boolean

        fun ensureCurrent()

        fun connect(socket: Socket, stop: (DirectShellPairingTicket) -> Unit)
    }

    private class OwnedBinding(
        private val admitted: Admission,
        private val current: () -> Snapshot?,
        private val active: () -> Boolean,
        private val bindSocket: (Socket) -> Unit,
    ) : Binding {
        override val endpoint get() = admitted.endpoint
        private val claimed = AtomicBoolean()

        override fun matches(
            ticket: DirectShellPairingTicket,
            endpoint: InetSocketAddress,
            clock: DirectShellPairingClock,
            deadline: Long,
        ): Boolean = admitted.work.ticket === ticket && this.endpoint == endpoint && admitted.work.clock === clock &&
            admitted.work.deadlineElapsedRealtimeMs == deadline

        override fun ensureCurrent() {
            check(active() && current()?.let(admitted.selected::sameLink) == true) { "pairing_network_changed" }
        }

        override fun connect(socket: Socket, stop: (DirectShellPairingTicket) -> Unit) {
            check(claimed.compareAndSet(false, true)) { "pairing_numeric_connect_already_used" }
            val route = OwnedRoute(
                admitted.work,
                admitted.selected,
                endpoint,
                object : OwnedSocket {
                    override fun bind(owner: NetworkOwner) {
                        check(owner === admitted.selected.owner && !socket.isBound && !socket.isConnected)
                        bindSocket(socket)
                    }

                    override fun connect(endpoint: InetSocketAddress, timeoutMillis: Int) {
                        socket.connect(endpoint, timeoutMillis)
                    }
                },
                stop,
            )
            check(route.bindAndConnect(admitted.work, current, active))
        }
    }

    private class OwnedRoute(
        private val work: DirectShellPairingWorkContext,
        private val selected: Snapshot,
        private val endpoint: InetSocketAddress,
        private val socket: OwnedSocket,
        private val requestOwnedStop: (DirectShellPairingTicket) -> Unit,
    ) : Route {
        private val claimed = AtomicBoolean()
        private val stopped = AtomicBoolean()

        override fun networkLost(owner: NetworkOwner, epoch: Long): Boolean {
            if (selected.owner !== owner || selected.epoch != epoch) return false
            stop()
            return true
        }

        /** No retry. The stop callback signals the existing owner, never closes a borrowed socket inline. */
        override fun bindAndConnect(
            originalWork: DirectShellPairingWorkContext,
            current: () -> Snapshot?,
            operationActive: () -> Boolean,
        ): Boolean {
            if (originalWork !== work || !claimed.compareAndSet(false, true)) return false
            fun active() = !stopped.get() && operationActive() &&
                current()?.let(selected::sameLink) == true &&
                work.clock.elapsedRealtimeMs() < work.deadlineElapsedRealtimeMs
            var connected = false
            try {
                if (!active()) return false
                socket.bind(selected.owner)
                if (!active()) return false
                val remaining = work.deadlineElapsedRealtimeMs - work.clock.elapsedRealtimeMs()
                if (remaining !in 1..DirectShellPairingCoordinator.WORK_MS) return false
                socket.connect(endpoint, remaining.toInt())
                connected = active()
                return connected
            } catch (_: Exception) {
                return false
            } finally {
                if (!connected) stop()
            }
        }

        private fun stop() {
            if (stopped.compareAndSet(false, true)) requestOwnedStop(work.ticket)
        }
    }

    fun evaluate(
        work: DirectShellPairingWorkContext,
        selected: Snapshot,
        numericIp: String,
        pairingPort: Int,
        socket: OwnedSocket,
        requestOwnedStop: (DirectShellPairingTicket) -> Unit,
    ): Route? = admit(work, selected, numericIp, pairingPort)?.let {
        OwnedRoute(work, selected, it.endpoint, socket, requestOwnedStop)
    }

    fun binding(
        work: DirectShellPairingWorkContext,
        selected: Snapshot,
        numericIp: String,
        pairingPort: Int,
        current: () -> Snapshot?,
        active: () -> Boolean,
        bindSocket: (Socket) -> Unit,
    ): Binding? = admit(work, selected, numericIp, pairingPort)?.let { OwnedBinding(it, current, active, bindSocket) }

    private fun admit(
        work: DirectShellPairingWorkContext,
        selected: Snapshot,
        numericIp: String,
        pairingPort: Int,
    ): Admission? {
        if (selected.epoch != work.ticket.networkEpoch || pairingPort !in 1..65535) return null
        val bytes = parseUnicastV4(numericIp) ?: return null
        if (!selected.owns(numericIp, bytes)) return null
        val endpoint = InetSocketAddress(InetAddress.getByAddress(bytes), pairingPort)
        return Admission(work, selected, endpoint)
    }

    private fun parseUnicastV4(value: String): ByteArray? {
        if (value.length !in 7..15) return null
        val fields = value.split('.')
        if (fields.size != 4 || fields.any { !OCTET.matches(it) }) return null
        val octets = fields.map(String::toInt)
        if (octets.any { it > 255 } || octets[0] in setOf(0, 127) || octets[0] >= 224 ||
            (octets[0] == 169 && octets[1] == 254)
        ) {
            return null
        }
        return ByteArray(4) { octets[it].toByte() }
    }

    private val INTERFACE = Regex("[A-Za-z0-9_.-]{1,32}")
    private val OCTET = Regex("0|[1-9][0-9]{0,2}")
}
