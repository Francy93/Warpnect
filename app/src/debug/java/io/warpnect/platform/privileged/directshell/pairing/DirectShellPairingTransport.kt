package io.warpnect.platform.privileged.directshell.pairing

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Unwired loopback qualification boundary, NOT an ADB backend or an authentication grant. */
internal class DirectShellPairingTransport(
    private val ticket: DirectShellPairingTicket,
    private val endpoint: InetSocketAddress,
    private val deadlineMs: Long,
    private val clock: DirectShellPairingClock,
    private val scheduler: DirectShellPairingScheduler,
    private val recoveryReady: () -> Boolean,
    private val protocol: Protocol,
    private val socketFactory: () -> Socket = { Socket() },
    private val connector: (Socket, InetSocketAddress, Int) -> Unit = { socket, address, timeout ->
        socket.connect(address, timeout)
    },
) {
    /** Worker-only borrowed socket/code. No retained copies; release runs on that same worker. */
    internal interface Protocol {
        fun exchange(socket: Socket, code: CharArray, checkActive: () -> Unit): Completion
        fun release(): Boolean
    }

    internal enum class Completion { NOT_ENTERED, INCOMPLETE, REJECTED, FIXTURE_VERIFIED, PEER_INFO_VERIFIED }
    internal enum class Outcome {
        IDLE,
        RUNNING,
        DRAIN_PENDING,
        CLEANUP_UNCONFIRMED,
        CANCELLED,
        EXPIRED,
        CONNECT_FAILED,
        RECOVERY_UNCONFIRMED,
        FAILED,
        REJECTED,
        FIXTURE_VERIFIED,
        PEER_INFO_VERIFIED,
    }
    internal enum class SocketClosure { NOT_CREATED, CLOSED, UNKNOWN }

    internal data class Receipt(
        val ticket: DirectShellPairingTicket,
        val outcome: Outcome,
        val completion: Completion,
        val socket: SocketClosure,
        val ioWorkerTerminated: Boolean,
        val closeWorkerTerminated: Boolean,
        val protocolReleased: Boolean,
        val codeReleased: Boolean,
        val externalEffect: DirectShellPairingExternalEffect,
    ) {
        val drained: Boolean get() = socket != SocketClosure.UNKNOWN && ioWorkerTerminated &&
            closeWorkerTerminated && protocolReleased && codeReleased
    }

    private val gate = Any()
    private val closeSignal = CountDownLatch(1)
    private var started = false
    private var stopped: Outcome? = null
    private var settled = false
    private var outcome = Outcome.IDLE
    private var completion = Completion.NOT_ENTERED
    private var effect = DirectShellPairingExternalEffect.NONE_POSSIBLE
    private var socket: Socket? = null
    private var closeClaimed = false
    private var socketClosed = false
    private var protocolReleased = true
    private var code: CharArray? = null
    private var ioWorker: Thread? = null
    private var closeWorker: Thread? = null
    private var timer: DirectShellPairingTimer? = null

    init {
        val bytes = endpoint.address?.address
        require(
            !endpoint.isUnresolved && endpoint.port in 1..65535 &&
                (
                    bytes.contentEquals(byteArrayOf(127, 0, 0, 1)) ||
                        (bytes?.size == 16 && bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte())
                    ),
        ) { "pairing_transport_loopback_required" }
    }

    /** Explicit one-shot dispatch; constructors/inspection create neither sockets nor workers. */
    fun start(input: DirectShellPairingCode): Boolean {
        synchronized(gate) {
            if (started || stopped != null) return false
            started = true
            val now = clock.elapsedRealtimeMs()
            if (deadlineMs <= now || deadlineMs - now > 60_000) {
                stopped = Outcome.EXPIRED
                outcome = Outcome.EXPIRED
                settled = true
                return false
            }
            try {
                input.useCharacters { code = it.copyOf() }
            } catch (_: IllegalStateException) {
                outcome = Outcome.FAILED
                settled = true
                return false
            }
            outcome = Outcome.RUNNING
            ioWorker = Thread(::run, "pairing-transport-io")
            closeWorker = Thread({
                closeSignal.await()
                closeSocket()
            }, "pairing-transport-close")
            timer = scheduler.at(deadlineMs) { requestStop(ticket, expired = true) }
            // Thread dispatch is CPU-only; no socket, protocol, crypto or join runs under this gate.
            closeWorker!!.start()
            ioWorker!!.start()
            return true
        }
    }

    /** CPU-only stop signal. Closing an actual socket is owned by the close worker, not a UI caller. */
    fun requestStop(owner: DirectShellPairingTicket, expired: Boolean = false): Boolean = synchronized(gate) {
        if (owner !== ticket) return false
        if (!settled && stopped == null) {
            stopped = if (expired) Outcome.EXPIRED else Outcome.CANCELLED
            if (!started) {
                outcome = stopped!!
                settled = true
            }
        }
        closeSignal.countDown()
        true
    }

    private fun checkActive() = synchronized(gate) {
        if (clock.elapsedRealtimeMs() >= deadlineMs && stopped == null && !settled) stopped = Outcome.EXPIRED
        check(stopped == null) { "pairing_transport_stopped" }
    }

    private fun run() {
        var connecting = true
        try {
            checkActive()
            val created = socketFactory()
            synchronized(gate) { socket = created }
            checkActive()
            created.setSoLinger(false, 0)
            val remaining = (deadlineMs - clock.elapsedRealtimeMs()).coerceIn(1, 60_000).toInt()
            connector(created, endpoint, remaining)
            connecting = false
            checkActive()
            if (!recoveryReady()) {
                settle(Outcome.RECOVERY_UNCONFIRMED)
                return
            }
            synchronized(gate) {
                checkActive()
                // Admission precedes possibly trust-affecting protocol work, not remote success.
                effect = DirectShellPairingExternalEffect.POSSIBLE
                completion = Completion.INCOMPLETE
                protocolReleased = false
            }
            val result = protocol.exchange(created, requireNotNull(code), ::checkActive)
            synchronized(gate) { completion = result }
            settle(
                when (result) {
                    Completion.FIXTURE_VERIFIED -> Outcome.FIXTURE_VERIFIED
                    Completion.PEER_INFO_VERIFIED -> Outcome.PEER_INFO_VERIFIED
                    else -> Outcome.REJECTED
                },
            )
        } catch (_: Exception) {
            settle(if (connecting) Outcome.CONNECT_FAILED else Outcome.FAILED)
        } finally {
            closeSignal.countDown()
            closeSocket()
            synchronized(gate) {
                code?.fill('\u0000')
                code = null
            }
            val entered = synchronized(gate) { !protocolReleased }
            if (entered) {
                val released = try {
                    protocol.release()
                } catch (_: Exception) {
                    false
                }
                synchronized(gate) { protocolReleased = released }
            }
            timer?.cancel()
        }
    }

    private fun settle(result: Outcome) = synchronized(gate) {
        if (!settled) {
            if (clock.elapsedRealtimeMs() >= deadlineMs && stopped == null) stopped = Outcome.EXPIRED
            outcome = stopped ?: result
            settled = true
        }
    }

    private fun closeSocket() {
        val owned = synchronized(gate) {
            if (closeClaimed || socket == null) return
            closeClaimed = true
            socket!!
        }
        val confirmed = try {
            owned.close()
            owned.isClosed
        } catch (_: Exception) {
            false
        }
        synchronized(gate) { socketClosed = confirmed }
    }

    fun observe(): Receipt = synchronized(gate) {
        val ioDone = ioWorker?.isAlive != true
        val closeDone = closeWorker?.isAlive != true
        val closure = when {
            socket == null && ioDone -> SocketClosure.NOT_CREATED
            socketClosed -> SocketClosure.CLOSED
            else -> SocketClosure.UNKNOWN
        }
        val visible = when {
            !settled -> outcome
            !ioDone || !closeDone -> Outcome.DRAIN_PENDING
            closure == SocketClosure.UNKNOWN || !protocolReleased || code != null -> Outcome.CLEANUP_UNCONFIRMED
            else -> outcome
        }
        Receipt(
            ticket, visible, completion, closure, ioDone, closeDone,
            protocolReleased, code == null, effect,
        )
    }

    /** Composition-worker only: consume the original remaining work budget, never a new stage budget. */
    fun awaitWork(): Receipt {
        val remaining = (deadlineMs - clock.elapsedRealtimeMs()).coerceIn(0, 60_000)
        val owned = synchronized(gate) { ioWorker }
        check(owned !== Thread.currentThread()) { "pairing_transport_self_join" }
        if (owned != null && remaining > 0) TimeUnit.MILLISECONDS.timedJoin(owned, remaining)
        if (clock.elapsedRealtimeMs() >= deadlineMs) requestStop(ticket, expired = true)
        return observe()
    }

    /** Worker/test-only bounded observation, NEVER a coordinator-monitor or UI operation. */
    fun awaitDrain(observationMs: Long = 3_000): Receipt {
        require(observationMs in 0..3_000) { "pairing_transport_observation_bound" }
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(observationMs)
        val workers = synchronized(gate) { listOf(ioWorker, closeWorker) }
        for (worker in workers) {
            if (worker == null) continue
            val remaining = end - System.nanoTime()
            if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(worker, remaining)
        }
        return observe()
    }
}
