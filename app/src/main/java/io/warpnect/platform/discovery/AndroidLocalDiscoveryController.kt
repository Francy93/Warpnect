package io.warpnect.platform.discovery

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.warpnect.session.discovery.DefaultLocalDiscoveryController
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryBackend
import io.warpnect.session.discovery.DiscoveryConfig
import io.warpnect.session.discovery.DiscoveryMonotonicClock
import io.warpnect.session.discovery.DiscoveryOpaqueRouteLocator
import io.warpnect.session.discovery.DiscoveryOperationResult
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryPresenceIdGenerator
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.DiscoveryRouteLookupResult
import io.warpnect.session.discovery.DiscoveryRouteToken
import io.warpnect.session.discovery.HostAvailabilityProvider
import io.warpnect.session.discovery.LocalDiscoveryController
import io.warpnect.session.discovery.SecureRandomDiscoveryPresenceIdGenerator
import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.pairing.PairingTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Android lifecycle owner for RFC-005B control-plane work. NSD and Wi-Fi P2P callbacks, bounded
 * cache changes, availability updates, and stale-route expiry all run on one low-frequency thread.
 */
class AndroidLocalDiscoveryController(
    context: Context,
    private val config: DiscoveryConfig,
    presenceIdGenerator: DiscoveryPresenceIdGenerator = SecureRandomDiscoveryPresenceIdGenerator(),
    availabilityProvider: HostAvailabilityProvider? = null,
    endpointLeaseFactory: io.warpnect.session.discovery.DiscoveryContactEndpointLeaseFactory =
        AndroidDiscoveryContactEndpointLeaseFactory(),
) : LocalDiscoveryController {
    private val appContext = context.applicationContext
    private val controlThread = HandlerThread(THREAD_NAME).apply { start() }
    private val controlHandler = Handler(controlThread.looper)
    private val delegate = DefaultLocalDiscoveryController(
        config = config,
        backends = createBackends(),
        contactEndpointLeaseFactory = endpointLeaseFactory,
        clock = AndroidDiscoveryClock,
        presenceIdGenerator = presenceIdGenerator,
        availabilityProvider = availabilityProvider,
    )
    private var directBackend: AndroidWifiDirectDnsSdDiscoveryBackend? = null

    @Volatile
    private var closed = false

    private val expiryTask = object : Runnable {
        override fun run() {
            if (closed) return
            delegate.expireStaleRoutes()
            scheduleExpiryIfNeeded()
        }
    }

    override fun prepare(): DiscoveryOperationResult = onControl {
        delegate.prepare()
    }

    override fun start(): DiscoveryOperationResult = onControl {
        delegate.start().also { scheduleExpiryIfNeeded() }
    }

    override fun stop(): DiscoveryOperationResult = onControl {
        controlHandler.removeCallbacks(expiryTask)
        delegate.stop()
    }

    override fun startAdvertising(): DiscoveryOperationResult = onControl {
        delegate.startAdvertising()
    }

    override fun stopAdvertising(): DiscoveryOperationResult = onControl {
        delegate.stopAdvertising()
    }

    override fun startBrowsing(): DiscoveryOperationResult = onControl {
        delegate.startBrowsing().also { scheduleExpiryIfNeeded() }
    }

    override fun stopBrowsing(): DiscoveryOperationResult = onControl {
        controlHandler.removeCallbacks(expiryTask)
        delegate.stopBrowsing()
    }

    override fun updateAvailability(availability: DiscoveryAvailability): DiscoveryOperationResult = onControl {
        delegate.updateAvailability(availability)
    }

    override fun refreshAvailability(): DiscoveryOperationResult = onControl {
        delegate.refreshAvailability()
    }

    override fun expireStaleRoutes(): DiscoveryOperationResult = onControl {
        delegate.expireStaleRoutes()
    }

    override fun resolveRoute(token: DiscoveryRouteToken): DiscoveryRouteLookupResult = delegate.resolveRoute(token)

    override fun resolveRoute(presenceId: DiscoveryPresenceId, kind: DiscoveryRouteKind): DiscoveryRouteLookupResult =
        delegate.resolveRoute(presenceId, kind)

    override fun borrowPairingTransport(): PairingTransport? = onControl {
        delegate.borrowPairingTransport()
    }

    override fun borrowSessionHandshakeTransport(): SessionHandshakeTransport? = onControl {
        delegate.borrowSessionHandshakeTransport()
    }

    override fun currentAdvertisingPresenceId(): DiscoveryPresenceId? = delegate.currentAdvertisingPresenceId()

    override fun discoveredPresences() = delegate.discoveredPresences()

    /**
     * Resolves an opaque RFC-005B Direct route to the current Android P2P device address.
     * The address never enters discovery snapshots or portable SCL state; RFC-005G consumes it
     * only to request `WifiP2pManager.connect()` before its authenticated Direct probe.
     */
    fun directPeerAddress(locator: DiscoveryOpaqueRouteLocator): String? = onControl {
        directBackend?.directPeer(locator)?.deviceAddress?.takeIf(String::isNotBlank)
    }

    override fun snapshot() = delegate.snapshot()

    override fun close() {
        if (closed) return
        onControl {
            if (!closed) {
                controlHandler.removeCallbacks(expiryTask)
                delegate.close()
                closed = true
            }
        }
        controlThread.quitSafely()
    }

    private fun createBackends(): List<DiscoveryBackend> = buildList {
        if (config.enableLanDiscovery) {
            add(
                AndroidLanNsdDiscoveryBackend(
                    context = appContext,
                    dispatchToControl = { block -> controlHandler.post(block) },
                ),
            )
        }
        if (config.enableDirectDiscovery) {
            val direct = AndroidWifiDirectDnsSdDiscoveryBackend(
                context = appContext,
                controlHandler = controlHandler,
            )
            directBackend = direct
            add(direct)
        }
    }

    private fun scheduleExpiryIfNeeded() {
        controlHandler.removeCallbacks(expiryTask)
        if (!closed && delegate.snapshot().browsingRequested) {
            controlHandler.postDelayed(expiryTask, config.expiryCheckIntervalMs)
        }
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
        failure.get()?.let { throw IllegalStateException("Warpnect discovery control operation failed", it) }
        return requireNotNull(result.get())
    }

    private object AndroidDiscoveryClock : DiscoveryMonotonicClock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

    private companion object {
        const val THREAD_NAME = "WarpnectDiscovery"
    }
}
