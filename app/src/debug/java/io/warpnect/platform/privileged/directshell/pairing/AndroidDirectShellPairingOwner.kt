package io.warpnect.platform.privileged.directshell.pairing

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unregistered DEBUG fixture owner. The caller owns this instance for one application-process
 * lifetime and supplies only test storage/identity and a synthetic loopback peer.
 */
internal class AndroidDirectShellPairingOwner internal constructor(
    readiness: () -> HostReadinessUiState,
    environment: () -> DirectShellPairingEnvironment,
    clock: DirectShellPairingClock,
    queue: DirectShellPairingTimerQueue,
    composition: (DirectShellPairingClock, DirectShellPairingScheduler) -> DirectShellSyntheticPairingComposition,
) : AutoCloseable {
    private var nonce = 0L // P1 invokes nextNonce only under its own monitor.
    private val closing = AtomicBoolean()
    private val scheduler = DirectShellElapsedPairingScheduler(clock, queue)
    private val handoff = DirectShellPairingDrainHandoff(composition(clock, scheduler), clock, scheduler)
    private val coordinator = DirectShellPairingCoordinator(
        readiness,
        environment,
        clock,
        scheduler,
        handoff,
        handoff,
        UUID.randomUUID().toString(),
        { (++nonce).toString() },
    )

    val state get() = coordinator.state

    fun request(readinessGeneration: Long) = coordinator.request(readinessGeneration)

    fun submitCode(ticket: DirectShellPairingTicket, input: CharArray) = coordinator.submitCode(ticket, input)

    /** A private UI carrier can bind only an already issued, process-local ticket. */
    fun bindCodeDelivery(ticket: DirectShellPairingTicket): CodeDelivery = CodeDelivery {
        coordinator.submitCode(ticket, it, requireCurrentReadiness = true)
    }

    fun cancel(ticket: DirectShellPairingTicket) = coordinator.cancel(ticket)

    fun environmentChanged() = coordinator.environmentChanged()

    /** Explicit foreground lifecycle signal; never creates or restores a ticket. */
    fun onResume() = coordinator.reevaluateDeadline()

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        // P1 must register the close observation before stopping its infrastructure.
        coordinator.close()
        handoff.stopObserver()
    }

    /** Separate infrastructure witness. Call only off the UI/observer, never as proof of attempt D. */
    fun awaitObserverTermination(observationMs: Long = 3_000) = handoff.awaitObserverTermination(observationMs)

    /** This object grants no consent; P1 still checks the ticket, generation and deadline. */
    class CodeDelivery internal constructor(private val submit: (CharArray) -> Boolean) {
        private val consumed = AtomicBoolean()

        fun deliver(input: CharArray): Boolean = try {
            consumed.compareAndSet(false, true) && submit(input)
        } finally {
            input.fill('\u0000')
        }

        fun invalidate() {
            consumed.set(true)
        }
    }

    companion object {
        fun forSyntheticFixture(
            readiness: () -> HostReadinessUiState,
            environment: () -> DirectShellPairingEnvironment,
            storage: () -> DirectShellCredentialStorage,
            identity: () -> DirectShellSyntheticPairingComposition.IdentityBorrow,
            endpoint: () -> InetSocketAddress,
        ): AndroidDirectShellPairingOwner {
            val handler = Handler(Looper.getMainLooper())
            val queue = object : DirectShellPairingTimerQueue {
                override fun post(task: Runnable, delayMs: Long) {
                    // A stopped queue grants no authority; the elapsed checkpoint still applies.
                    handler.postDelayed(task, delayMs)
                }

                override fun remove(task: Runnable) = handler.removeCallbacks(task)
            }
            return AndroidDirectShellPairingOwner(
                readiness,
                environment,
                SystemClock::elapsedRealtime,
                queue,
            ) { clock, scheduler ->
                DirectShellSyntheticPairingComposition(clock, scheduler, storage, identity, endpoint)
            }
        }
    }
}
