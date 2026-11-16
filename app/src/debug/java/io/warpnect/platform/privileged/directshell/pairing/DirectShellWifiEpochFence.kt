package io.warpnect.platform.privileged.directshell.pairing

/** The callback stream supplies facts; this fence owns no P1 ticket, credentials or transport. */
internal class DirectShellWifiEpochFence<N : Any> : AutoCloseable {
    data class Link(val interfaceName: String, val addresses: List<DirectShellNumericRouteCandidate.LinkAddress>)

    class Epoch<N>(val network: N, val number: Long, val link: Link)

    class Watch internal constructor(private val remove: () -> Unit) : AutoCloseable {
        override fun close() = remove()
    }

    private val lock = Any()
    private var closed = false
    private var network: N? = null
    private var suitable = false
    private var blocked: Boolean? = null
    private var link: Link? = null
    private var epoch = 0L
    private var current: Epoch<N>? = null
    private var pending: (() -> Unit)? = null

    fun available(value: N) = change {
        if (network != value) reset(value)
    }

    fun capabilities(value: N, wifiWithoutVpn: Boolean) = change {
        if (network == value) suitable = wifiWithoutVpn
    }

    fun link(value: N, updated: Link?) = change {
        if (network == value) link = updated?.copy(addresses = updated.addresses.toList())
    }

    fun blocked(value: N, status: Boolean) = change {
        if (network == value) blocked = status
    }

    fun lost(value: N) = change {
        if (network == value) reset(null)
    }

    fun current(): Epoch<N>? = synchronized(lock) { current }

    /** Independently sample platform state off the callback lock and reject a superseded observation. */
    fun verify(expected: Epoch<N>, inspect: (N) -> Link?): Boolean =
        current() === expected && inspect(expected.network) == expected.link && current() === expected

    fun watch(expected: Epoch<N>, onLoss: () -> Unit): Watch? = synchronized(lock) {
        if (current !== expected || pending != null) return@synchronized null
        // Use an owned closure so an old handle cannot unregister a newer watch.
        val owned: () -> Unit = { onLoss() }
        pending = owned
        Watch { synchronized(lock) { if (pending === owned) pending = null } }
    }

    override fun close() = change {
        closed = true
        reset(null)
    }

    private fun reset(value: N?) {
        network = value
        suitable = false
        blocked = null
        link = null
    }

    private fun change(update: () -> Unit) {
        val signal = synchronized(lock) {
            if (closed) return
            update()
            val value = network
            val facts = link
            val admitted = value != null && facts != null &&
                facts.addresses.isNotEmpty() && suitable && blocked == false
            val old = current
            if (old == null && !admitted || old != null && admitted && old.network == value && old.link == facts) return
            current = if (admitted) {
                epoch = Math.addExact(epoch, 1)
                Epoch(checkNotNull(value), epoch, checkNotNull(facts))
            } else {
                null
            }
            pending.also { pending = null }
        }
        signal?.invoke()
    }
}
