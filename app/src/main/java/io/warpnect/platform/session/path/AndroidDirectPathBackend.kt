package io.warpnect.platform.session.path

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import io.warpnect.platform.discovery.AndroidDiscoveryEnvironmentProvider
import io.warpnect.platform.discovery.AndroidDiscoveryPermissionPlanner
import io.warpnect.platform.discovery.AndroidWifiDirectDiscoveryPlan
import io.warpnect.platform.session.control.AndroidSecureSessionControlTransport
import io.warpnect.session.control.SecureSessionControlTransport
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-scoped RFC-005G Direct ownership. One instance owns the Host Group Owner manager,
 * one bounded candidate-socket dispatcher and one timer source; sessions receive only short-lived
 * setup coordinators and path leases.
 */
class AndroidDirectPathBackend private constructor(
    private val context: Context,
    private val controller: AndroidDirectPathController,
    val candidateDispatcher: AndroidDirectCandidateDatagramDispatcher,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val permissionPlanner = AndroidDiscoveryPermissionPlanner()
    private val environmentProvider = AndroidDiscoveryEnvironmentProvider(context)

    /** The public-API backend is present even when permissions/P2P state make it unavailable now. */
    fun isImplemented(): Boolean = !closed.get()

    /**
     * Runtime availability deliberately includes Android feature, permission, location-service and
     * P2P-enabled state. A Client additionally requires a usable discovered Direct route.
     */
    fun isPlatformAvailable(): Boolean = !closed.get() &&
        permissionPlanner.planWifiDirect(
            environmentProvider.wifiDirectEnvironment(controller.isP2pEnabled()),
        ) == AndroidWifiDirectDiscoveryPlan.Ready

    fun createCoordinator(
        secureControl: SecureSessionControlTransport,
        peerAddressResolver: DirectPeerAddressResolver,
    ): AndroidDirectSessionPathCoordinator? {
        val androidControl = secureControl as? AndroidSecureSessionControlTransport ?: return null
        if (closed.get()) return null
        return AndroidDirectSessionPathCoordinator(
            controller = controller,
            dispatcher = candidateDispatcher,
            secureControl = androidControl,
            peerAddressResolver = peerAddressResolver,
            scheduler = scheduler,
        )
    }

    /** Bounded bridge from the shared P2P group to per-Session Direct path lifecycle hints. */
    fun observeGroupState(observer: (Boolean) -> Unit): AutoCloseable? =
        if (closed.get()) null else controller.observeGroupState(observer)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controller.close()
        candidateDispatcher.close()
        scheduler.shutdownNow()
    }

    companion object {
        fun create(context: Context): AndroidDirectPathBackend? {
            val appContext = context.applicationContext
            if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return null
            val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return null
            val channel = try {
                manager.initialize(appContext, Looper.getMainLooper(), WifiP2pManager.ChannelListener {})
            } catch (_: RuntimeException) {
                null
            } ?: return null
            val groupManager = HostDirectGroupManager(manager, channel)
            val controller = try {
                AndroidDirectPathController(appContext, manager, channel, groupManager)
            } catch (_: RuntimeException) {
                groupManager.close()
                return null
            }
            return AndroidDirectPathBackend(
                appContext,
                controller,
                AndroidDirectCandidateDatagramDispatcher(),
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "WarpnectDirectPathTimer").apply { isDaemon = true }
                },
            )
        }
    }
}
