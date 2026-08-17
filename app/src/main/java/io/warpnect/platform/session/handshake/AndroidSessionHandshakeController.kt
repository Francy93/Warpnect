package io.warpnect.platform.session.handshake

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.warpnect.platform.session.control.AndroidSecureSessionControlTransport
import io.warpnect.platform.session.control.SecureSessionControlDatagramIo
import io.warpnect.session.SessionId
import io.warpnect.session.SessionManager
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.CurrentDiscoveryPresenceProvider
import io.warpnect.session.handshake.DiscoveryPresenceBinding
import io.warpnect.session.handshake.ExpectedPeerConstraint
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeConfig
import io.warpnect.session.handshake.SessionHandshakeController
import io.warpnect.session.handshake.SessionHandshakeEngineResult
import io.warpnect.session.handshake.SessionHandshakeEventListener
import io.warpnect.session.handshake.SessionHandshakeMonotonicClock
import io.warpnect.session.handshake.SessionHandshakeSnapshot
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.handshake.SessionManagerRecoveryHandshakeAdmissionResolver
import io.warpnect.session.identity.LocalDeviceIdentitySigner
import io.warpnect.session.pairing.PairingCryptoProvider
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.trust.TrustedPeerStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Serialized Android control plane for WNSH crypto, timers, and UDP callbacks. */
class AndroidSessionHandshakeController(
    localSigner: LocalDeviceIdentitySigner,
    trustedPeers: TrustedPeerStore,
    sessionManager: SessionManager,
    private val transport: SessionHandshakeTransport,
    crypto: PairingCryptoProvider,
    private val config: SessionHandshakeConfig = SessionHandshakeConfig(),
    discovery: LocalDiscoveryController? = null,
    eventListener: SessionHandshakeEventListener? = null,
) : AutoCloseable {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatched = HandlerDispatchingSessionHandshakeTransport(transport, handler, ::scheduleNextLocked)
    private val controller = SessionHandshakeController(
        transport = dispatched,
        localSigner = localSigner,
        trustedPeers = trustedPeers,
        sessionManager = sessionManager,
        crypto = crypto,
        config = config,
        clock = AndroidHandshakeClock,
        presenceProvider = CurrentDiscoveryPresenceProvider {
            discovery?.currentAdvertisingPresenceId()
        },
        recoveryAdmissionResolver = SessionManagerRecoveryHandshakeAdmissionResolver(
            sessionManager,
            AndroidHandshakeClock,
        ),
        eventListener = eventListener,
    )

    @Volatile private var closed = false
    private val timer = object : Runnable {
        override fun run() {
            if (!closed) {
                controller.advance()
                scheduleNextLocked()
            }
        }
    }

    fun connect(
        endpoint: HandshakeTransportEndpoint,
        sessionId: SessionId,
        discoveryBinding: DiscoveryPresenceBinding = DiscoveryPresenceBinding.None,
        expectedPeer: ExpectedPeerConstraint = ExpectedPeerConstraint.AnyTrustedPeer,
    ): SessionHandshakeEngineResult = onControl {
        controller.startInitiator(
            endpoint,
            sessionId,
            targetPresence = discoveryBinding,
            expectedPeer = expectedPeer,
        ).also {
            scheduleNextLocked()
        }
    }

    fun snapshot(): SessionHandshakeSnapshot = onControl(controller::snapshot)

    /** Reuses the existing bound handshake endpoint; it never opens a second client UDP socket. */
    fun borrowSecureSessionControlTransport(
        bootstrap: AuthenticatedSessionBootstrap,
        protection: SessionProtectionRuntime,
    ): AndroidSecureSessionControlTransport? = onControl {
        val datagramIo = transport as? SecureSessionControlDatagramIo ?: return@onControl null
        AndroidSecureSessionControlTransport(
            datagramIo,
            protection,
            protection.sessionControlContext.receiveContextId,
            bootstrap.endpoint,
        )
    }

    override fun close() {
        if (closed) return
        onControl {
            if (!closed) {
                handler.removeCallbacks(timer)
                controller.close()
                closed = true
            }
        }
        thread.quitSafely()
    }

    private fun scheduleNextLocked() {
        handler.removeCallbacks(timer)
        val deadline = controller.nextWakeAtMonotonicMs() ?: return
        handler.postDelayed(timer, (deadline - AndroidHandshakeClock.nowMs()).coerceAtLeast(1L))
    }

    private fun <T> onControl(block: () -> T): T {
        if (closed || Looper.myLooper() == thread.looper) return block()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        val done = CountDownLatch(1)
        if (!handler.post {
                try {
                    result.set(block())
                } catch (
                    error: Throwable,
                ) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
        ) {
            return block()
        }
        done.await()
        failure.get()?.let { throw IllegalStateException("Warpnect session handshake control operation failed", it) }
        return requireNotNull(result.get())
    }

    private object AndroidHandshakeClock : SessionHandshakeMonotonicClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }
    private companion object {
        const val THREAD_NAME = "WarpnectSessionHandshake"
    }
}

private class HandlerDispatchingSessionHandshakeTransport(
    private val delegate: SessionHandshakeTransport,
    private val handler: Handler,
    private val onDelivered: () -> Unit,
) : SessionHandshakeTransport {
    @Volatile private var listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)? = null
    override fun setDatagramListener(listener: ((HandshakeTransportEndpoint, ByteArray) -> Unit)?) {
        this.listener = listener
        delegate.setDatagramListener(
            if (listener == null) {
                null
            } else {
                { endpoint, datagram ->
                    handler.post {
                        this.listener?.invoke(endpoint, datagram)
                        onDelivered()
                    }
                }
            },
        )
    }
    override fun send(endpoint: HandshakeTransportEndpoint, datagram: ByteArray): Boolean =
        delegate.send(endpoint, datagram)
    override fun close() {
        listener = null
        delegate.close()
    }
}
