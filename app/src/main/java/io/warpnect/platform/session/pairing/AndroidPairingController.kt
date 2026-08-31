package io.warpnect.platform.session.pairing

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.warpnect.diagnostics.DiagnosticEventWriter
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCompletedListener
import io.warpnect.session.pairing.PairingConfig
import io.warpnect.session.pairing.PairingController
import io.warpnect.session.pairing.PairingControllerResult
import io.warpnect.session.pairing.PairingDebugObserver
import io.warpnect.session.pairing.PairingEventListener
import io.warpnect.session.pairing.PairingMonotonicClock
import io.warpnect.session.pairing.PairingSnapshot
import io.warpnect.session.pairing.PairingTransport
import io.warpnect.session.pairing.PairingTransportEndpoint
import io.warpnect.session.pairing.PairingTransportSendResult
import io.warpnect.session.pairing.PairingWallClock
import io.warpnect.session.trust.TrustedPeerStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Android control-plane owner for pairing. Datagram callbacks, crypto state transitions, prompt
 * delivery, retry timers, and pairing-window lifecycle are serialized on WarpnectPairing.
 */
class AndroidPairingController(
    localSigner: LocalDeviceIdentitySigner,
    trustedPeerStore: TrustedPeerStore,
    transport: PairingTransport,
    private val config: PairingConfig = PairingConfig(),
    eventListener: PairingEventListener? = null,
    completedListener: PairingCompletedListener? = null,
    diagnosticEvents: DiagnosticEventWriter? = null,
    debugObserver: PairingDebugObserver? = null,
) : AutoCloseable {
    private val controlThread = HandlerThread(THREAD_NAME).apply { start() }
    private val controlHandler = Handler(controlThread.looper)
    private val dispatchedTransport = HandlerDispatchingPairingTransport(
        transport,
        controlHandler,
        ::scheduleNextLocked,
    )
    private val controller = PairingController(
        localSigner = localSigner,
        trustedPeerStore = trustedPeerStore,
        transport = dispatchedTransport,
        config = config,
        monotonicClock = AndroidPairingMonotonicClock,
        wallClock = PairingWallClock { System.currentTimeMillis() },
        eventListener = eventListener,
        completedListener = completedListener,
        diagnosticEvents = diagnosticEvents,
        debugObserver = debugObserver,
    )

    @Volatile
    private var closed = false

    private val timer = object : Runnable {
        override fun run() {
            if (closed) return
            controller.advance()
            scheduleNextLocked()
        }
    }

    fun openPairingWindow(windowMs: Long = config.pairingWindowMs): PairingControllerResult = onControl {
        controller.openPairingWindow(windowMs).also { scheduleNextLocked() }
    }

    fun closePairingWindow(): PairingControllerResult = onControl {
        controller.closePairingWindow().also { scheduleNextLocked() }
    }

    fun beginPairing(
        endpoint: PairingTransportEndpoint,
        remoteUntrustedAlias: String? = null,
    ): PairingControllerResult = onControl {
        controller.beginPairing(endpoint, remoteUntrustedAlias).also { scheduleNextLocked() }
    }

    /** Explicit LAN-only runtime bridge for a selected untrusted discovery presence. */
    fun beginPairing(
        discovery: LocalDiscoveryController,
        presenceId: DiscoveryPresenceId,
        remoteUntrustedAlias: String? = null,
    ): PairingControllerResult = onControl {
        val lan = discovery.resolveRoute(presenceId, DiscoveryRouteKind.Lan)
        val descriptor = lan.descriptor as? DiscoveryRouteDescriptor.Lan
        if (descriptor != null) {
            val address = descriptor.addressCandidates.firstOrNull()?.hostAddress
            if (address != null) {
                controller.beginPairing(PairingTransportEndpoint(address, descriptor.port), remoteUntrustedAlias)
                    .also { scheduleNextLocked() }
            } else {
                PairingControllerResult(
                    io.warpnect.session.pairing.PairingError.DiscoveryRouteUnavailable,
                    controller.snapshot(),
                )
            }
        } else {
            val direct = discovery.resolveRoute(presenceId, DiscoveryRouteKind.Direct)
            PairingControllerResult(
                if (direct.descriptor != null) {
                    io.warpnect.session.pairing.PairingError.PairingTransportUnavailable
                } else {
                    io.warpnect.session.pairing.PairingError.DiscoveryRouteUnavailable
                },
                controller.snapshot(),
            )
        }
    }

    fun acceptVerification(attemptId: io.warpnect.session.pairing.PairingAttemptId): PairingControllerResult =
        onControl {
            controller.acceptVerification(attemptId).also { scheduleNextLocked() }
        }

    fun rejectVerification(
        attemptId: io.warpnect.session.pairing.PairingAttemptId,
        mismatch: Boolean = false,
    ): PairingControllerResult = onControl {
        controller.rejectVerification(attemptId, mismatch).also { scheduleNextLocked() }
    }

    fun snapshot(): PairingSnapshot = onControl(controller::snapshot)

    override fun close() {
        if (closed) return
        onControl {
            if (!closed) {
                controlHandler.removeCallbacks(timer)
                controller.close()
                closed = true
            }
        }
        controlThread.quitSafely()
    }

    private fun scheduleNextLocked() {
        controlHandler.removeCallbacks(timer)
        val deadline = controller.nextWakeAtMonotonicMs() ?: return
        val delayMs = (deadline - AndroidPairingMonotonicClock.nowMs()).coerceAtLeast(1L)
        controlHandler.postDelayed(timer, delayMs)
    }

    private fun <T> onControl(block: () -> T): T {
        if (closed || Looper.myLooper() == controlThread.looper) return block()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val complete = CountDownLatch(1)
        if (!controlHandler.post {
                try {
                    result.set(block())
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    complete.countDown()
                }
            }
        ) {
            return block()
        }
        complete.await()
        failure.get()?.let { throw IllegalStateException("Warpnect pairing control operation failed", it) }
        return requireNotNull(result.get())
    }

    private object AndroidPairingMonotonicClock : PairingMonotonicClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val THREAD_NAME = "WarpnectPairing"
    }
}

private class HandlerDispatchingPairingTransport(
    private val delegate: PairingTransport,
    private val handler: Handler,
    private val onDatagramDelivered: () -> Unit,
) : PairingTransport {
    @Volatile
    private var listener: ((PairingTransportEndpoint, ByteArray) -> Unit)? = null

    override fun setDatagramListener(listener: ((PairingTransportEndpoint, ByteArray) -> Unit)?) {
        this.listener = listener
        if (listener == null) {
            delegate.setDatagramListener(null)
        } else {
            delegate.setDatagramListener { endpoint, datagram ->
                handler.post {
                    this.listener?.invoke(endpoint, datagram)
                    onDatagramDelivered()
                }
            }
        }
    }

    override fun send(destination: PairingTransportEndpoint, datagram: ByteArray): PairingTransportSendResult =
        delegate.send(destination, datagram)

    override fun close() {
        listener = null
        delegate.close()
    }
}
