package io.warpnect.platform.privileged.directshell.pairing

import android.net.Network
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Unregistered pairing-only adapter. No discovery, reconnect port, authority or credential writer. */
internal class DirectShellNumericPairingAttempt private constructor(
    internal val transport: DirectShellPairingTransport,
    private val protocol: DirectShellOwnedPairingProtocol,
    private val selected: DirectShellNumericRouteCandidate.Snapshot,
    private val work: DirectShellPairingWorkContext,
    private val invalidated: AtomicBoolean,
    private val sameNetwork: () -> Boolean,
) {
    fun verifiedPeer() = if (transport.observe().outcome == DirectShellPairingTransport.Outcome.PEER_INFO_VERIFIED &&
        sameNetwork()
    ) {
        protocol.verifiedPeer()
    } else {
        null
    }

    fun lost(owner: DirectShellNumericRouteCandidate.NetworkOwner, epoch: Long): Boolean {
        if (selected.owner !== owner || selected.epoch != epoch) return false
        if (invalidated.compareAndSet(false, true)) transport.requestStop(work.ticket)
        return true
    }

    /** A platform owner creates snapshots bound to this exact Android Network object. */
    class SelectedNetwork(private val network: Network) {
        val owner = DirectShellNumericRouteCandidate.NetworkOwner()

        fun prepare(
            work: DirectShellPairingWorkContext,
            selected: DirectShellNumericRouteCandidate.Snapshot,
            current: () -> DirectShellNumericRouteCandidate.Snapshot?,
            numericIp: String,
            pairingPort: Int,
            active: () -> Boolean,
            recoveryReady: () -> Boolean,
            identity: () -> DirectShellOwnedPairingProtocol.Identity,
        ): DirectShellNumericPairingAttempt? {
            if (selected.owner !== owner) return null
            return Companion.prepare(
                work, selected, current, numericIp, pairingPort, active, recoveryReady, identity, network::bindSocket,
            )
        }
    }

    companion object {
        internal fun prepare(
            work: DirectShellPairingWorkContext,
            selected: DirectShellNumericRouteCandidate.Snapshot,
            current: () -> DirectShellNumericRouteCandidate.Snapshot?,
            numericIp: String,
            pairingPort: Int,
            active: () -> Boolean,
            recoveryReady: () -> Boolean,
            identity: () -> DirectShellOwnedPairingProtocol.Identity,
            bindSocket: (Socket) -> Unit,
            socketFactory: () -> Socket = { Socket() },
        ): DirectShellNumericPairingAttempt? {
            val invalidated = AtomicBoolean()
            fun sameNetwork() = !invalidated.get() && active() && current()?.let(selected::sameLink) == true
            val binding = DirectShellNumericRouteCandidate.binding(
                work, selected, numericIp, pairingPort, current, { !invalidated.get() && active() }, bindSocket,
            ) ?: return null
            val protocol = DirectShellOwnedPairingProtocol(binding.endpoint, identity)
            check(protocol.bindWork(work.ticket, work))
            val transport = DirectShellPairingTransport(
                work.ticket,
                binding.endpoint,
                work.deadlineElapsedRealtimeMs,
                work.clock,
                work.scheduler,
                { sameNetwork() && recoveryReady() && sameNetwork() },
                protocol,
                socketFactory,
                numericBinding = binding,
            )
            return DirectShellNumericPairingAttempt(transport, protocol, selected, work, invalidated, ::sameNetwork)
        }
    }
}
