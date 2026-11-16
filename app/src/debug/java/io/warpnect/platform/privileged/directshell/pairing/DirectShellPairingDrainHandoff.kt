package io.warpnect.platform.privileged.directshell.pairing

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Unregistered DEBUG handoff. The observer belongs to the owner, never to an attempt's D. */
internal class DirectShellPairingDrainHandoff(
    private val delegate: DirectShellSyntheticPairingComposition,
    private val clock: DirectShellPairingClock,
    private val scheduler: DirectShellPairingScheduler,
    private val observerFactory: (Runnable) -> Thread = { Thread(it, "pairing-owner-drain") },
) : DirectShellPairingBackend, DirectShellPairingCommitter {
    private val gate = Any()
    private val wake = Semaphore(0)
    private val current = AtomicReference<Operation?>()
    private var observer: Thread? = null
    private var stopping = false
    private var observerFailed = false
    private var pending: Operation? = null

    internal data class State(
        val observerCreated: Boolean,
        val observerAlive: Boolean,
        val stopping: Boolean,
        val observerFailed: Boolean,
        val occupied: Boolean,
        val observationComplete: Boolean,
    )

    fun state(): State = synchronized(gate) {
        State(
            observer != null,
            observer?.isAlive == true,
            stopping,
            observerFailed,
            current.get() != null,
            current.get()?.observed?.get() == true,
        )
    }

    override fun create(ticket: DirectShellPairingTicket, observer: DirectShellPairingObserver): Operation {
        require(observer is ObservedDirectShellPairingObserver) { "pairing_observed_port_required" }
        check(synchronized(gate) { !stopping && !observerFailed }) { "pairing_owner_unavailable" }
        check(current.get() == null) { "pairing_operation_occupied" }
        val operation = Operation(ticket, delegate.create(ticket, observer))
        check(current.compareAndSet(null, operation)) { "pairing_operation_occupied" }
        return operation
    }

    override fun commit(
        ticket: DirectShellPairingTicket,
        authorization: DirectShellStagedAuthorization,
        control: DirectShellPairingCommitControl,
    ) {
        check(current.get()?.ticket === ticket) { "pairing_commit_owner_invalid" }
        delegate.commit(ticket, authorization, control)
    }

    internal inner class Operation(
        val ticket: DirectShellPairingTicket,
        private val operation: DirectShellSyntheticPairingComposition.Operation,
    ) : DeadlineAwarePairingOperation {
        private val started = AtomicBoolean()
        private val stopped = AtomicBoolean()
        private val closing = AtomicBoolean()
        internal val observed = AtomicBoolean()
        private val delivered = AtomicBoolean()
        private var cutoff = 0L

        override fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext) {
            check(
                work.ticket === ticket && work.clock === clock && work.scheduler === scheduler &&
                    work.deadlineElapsedRealtimeMs > clock.elapsedRealtimeMs() &&
                    work.deadlineElapsedRealtimeMs - clock.elapsedRealtimeMs() <= DirectShellPairingCoordinator.WORK_MS,
            ) {
                "pairing_work_owner_invalid"
            }
            code.useCharacters { }
            check(current.get() === this && started.compareAndSet(false, true) && !stopped.get()) {
                "pairing_work_already_stopped"
            }
            check(startObserver()) { "pairing_owner_observer_unavailable" }
            // P1 serializes start/cancel; recheck stop after infrastructure dispatch as well.
            check(!stopped.get() && synchronized(gate) { !stopping }) { "pairing_work_already_stopped" }
            operation.start(code, work)
        }

        override fun cancel() {
            stopped.set(true)
            operation.cancel()
        }

        override fun close(drained: (DirectShellPairingDrainReceipt) -> Unit) {
            if (!closing.compareAndSet(false, true)) return
            val now = clock.elapsedRealtimeMs()
            cutoff = if (now > Long.MAX_VALUE - OBSERVATION_MS) Long.MAX_VALUE else now + OBSERVATION_MS
            cancel()
            operation.close { receipt ->
                if (delivered.compareAndSet(false, true) && current.compareAndSet(this, null)) drained(receipt)
            }
            synchronized(gate) {
                // No infrastructure resurrection after explicit shutdown or failed dispatch.
                if (!stopping && !observerFailed && observer != null) {
                    check(pending == null) { "pairing_observation_occupied" }
                    pending = this
                    wake.release()
                }
            }
        }

        internal fun observe() {
            val remaining = (cutoff - clock.elapsedRealtimeMs()).coerceIn(0, OBSERVATION_MS)
            try {
                operation.awaitDrain(remaining)
            } finally {
                observed.set(true)
            }
        }
    }

    /** CPU-only dispatch, like the existing operation start; no join or delegate call under gate. */
    private fun startObserver(): Boolean = synchronized(gate) {
        if (stopping || observerFailed) return false
        observer?.let { return it.isAlive }
        try {
            val thread = observerFactory(Runnable { observeLoop() })
            check(thread.state == Thread.State.NEW) { "pairing_observer_not_new" }
            observer = thread
            thread.start()
            true
        } catch (_: Exception) {
            observerFailed = true
            false
        }
    }

    private fun observeLoop() {
        try {
            while (true) {
                wake.acquire()
                val operation = synchronized(gate) {
                    pending.also { pending = null }
                }
                operation?.observe()
                if (synchronized(gate) { stopping && pending == null }) return
            }
        } catch (_: Exception) {
            synchronized(gate) { observerFailed = true }
        }
    }

    /** Infrastructure shutdown is separate from P1.close and is never a drain attestation. */
    fun stopObserver() {
        synchronized(gate) {
            if (stopping) return
            stopping = true
            wake.release()
        }
        current.get()?.cancel()
    }

    /** Explicit owner-side physical observation only; never call under P1/UI or on this thread. */
    fun awaitObserverTermination(observationMs: Long = OBSERVATION_MS): Boolean {
        require(observationMs in 0..OBSERVATION_MS)
        val thread = synchronized(gate) { observer }
        check(thread !== Thread.currentThread()) { "pairing_observer_self_join" }
        if (thread != null && observationMs > 0) TimeUnit.MILLISECONDS.timedJoin(thread, observationMs)
        return thread?.isAlive != true
    }

    private companion object {
        const val OBSERVATION_MS = 3_000L
    }
}
