package io.warpnect.platform.privileged.directshell.pairing

import io.github.muntashirakon.adb.PairingConnectionCtx
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.Certificate

/** Inert/unregistered protocol adapter. Identity loading and all library calls are worker-only. */
internal class DirectShellOwnedPairingProtocol(
    private val endpoint: InetSocketAddress,
    private val identity: () -> Identity,
) : DirectShellPairingTransport.Protocol {
    internal class Identity(val privateKey: PrivateKey, val certificate: Certificate) {
        override fun toString(): String = "PairingIdentity(redacted)"
    }

    private var context: PairingConnectionCtx? = null
    private var work: DirectShellPairingWorkContext? = null

    @Synchronized
    internal fun bindWork(ticket: DirectShellPairingTicket, value: DirectShellPairingWorkContext): Boolean {
        if (work != null || value.ticket !== ticket) return false
        work = value
        return true
    }

    @Volatile private var evidence: PairingConnectionCtx.AuthenticatedPeerInfo? = null

    /** Protocol knowledge only; never an Android trust, storage or Host grant. */
    fun verifiedPeer(): PairingConnectionCtx.AuthenticatedPeerInfo? = evidence

    override fun exchange(
        socket: Socket,
        code: CharArray,
        checkActive: () -> Unit,
    ): DirectShellPairingTransport.Completion {
        val owner = synchronized(this) { work } ?: error("pairing_owner_work_context_required")
        val checkpoint = {
            checkActive()
            check(owner.clock.elapsedRealtimeMs() < owner.deadlineElapsedRealtimeMs) { "pairing_work_expired" }
        }
        checkpoint()
        check(context == null) { "pairing_protocol_already_used" }
        val local = identity()
        checkpoint()
        val bytes = ByteArray(code.size) { code[it].code.toByte() }
        try {
            val owned = PairingConnectionCtx(
                requireNotNull(endpoint.address.hostAddress) { "pairing_endpoint_invalid" },
                endpoint.port,
                bytes,
                local.privateKey,
                local.certificate,
                "Warpnect",
            ).also { context = it }
            evidence = owned.startConnected(socket, endpoint, checkpoint)
            return DirectShellPairingTransport.Completion.PEER_INFO_VERIFIED
        } finally {
            bytes.fill(0)
        }
    }

    override fun release(): Boolean = context?.releaseConnected() ?: true

    companion object {
        /** No fallback budget. This factory creates no socket, worker, identity or protocol state. */
        fun transport(
            ticket: DirectShellPairingTicket,
            work: DirectShellPairingWorkContext?,
            endpoint: InetSocketAddress,
            recoveryReady: () -> Boolean,
            protocol: DirectShellOwnedPairingProtocol,
            socketFactory: () -> Socket = { Socket() },
        ): DirectShellPairingTransport? {
            if (work == null || work.ticket !== ticket || protocol.endpoint != endpoint) return null
            if (!protocol.bindWork(ticket, work)) return null
            return DirectShellPairingTransport(
                ticket,
                endpoint,
                work.deadlineElapsedRealtimeMs,
                work.clock,
                work.scheduler,
                recoveryReady,
                protocol,
                socketFactory,
            )
        }
    }
}
