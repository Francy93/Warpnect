package io.warpnect.platform.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import io.warpnect.session.discovery.DiscoveryError

data class AndroidMulticastLockEnvironment(
    val deviceSdk: Int,
    val tiramisuExtensionVersion: Int,
)

enum class AndroidMulticastLockPlan {
    AcquireCompatibilityLock,
    SystemManagedForeground,
}

/** Applies the NSD T-extension multicast guidance without holding a lock while discovery is idle. */
class AndroidDiscoveryMulticastLockPlanner {
    fun plan(environment: AndroidMulticastLockEnvironment): AndroidMulticastLockPlan =
        if (environment.deviceSdk >= Build.VERSION_CODES.TIRAMISU &&
            environment.tiramisuExtensionVersion >= TIRAMISU_EXTENSION_SYSTEM_MANAGED
        ) {
            AndroidMulticastLockPlan.SystemManagedForeground
        } else {
            AndroidMulticastLockPlan.AcquireCompatibilityLock
        }

    companion object {
        const val TIRAMISU_EXTENSION_SYSTEM_MANAGED = 7
    }
}

class AndroidDiscoveryMulticastLock(
    context: Context,
    private val planner: AndroidDiscoveryMulticastLockPlanner = AndroidDiscoveryMulticastLockPlanner(),
) : AutoCloseable {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null

    fun acquireForActiveNsd(): DiscoveryError {
        if (currentPlan() != AndroidMulticastLockPlan.AcquireCompatibilityLock || lock?.isHeld == true) {
            return DiscoveryError.None
        }
        val multicastLock = wifiManager?.createMulticastLock("WarpnectDiscovery")
            ?: return DiscoveryError.DiscoveryFailed
        return try {
            multicastLock.setReferenceCounted(false)
            multicastLock.acquire()
            lock = multicastLock
            DiscoveryError.None
        } catch (_: SecurityException) {
            DiscoveryError.LanPermissionDenied
        }
    }

    fun releaseWhenInactive() {
        val multicastLock = lock ?: return
        try {
            if (multicastLock.isHeld) multicastLock.release()
        } finally {
            lock = null
        }
    }

    override fun close() = releaseWhenInactive()

    private fun currentPlan(): AndroidMulticastLockPlan = planner.plan(
        AndroidMulticastLockEnvironment(
            deviceSdk = Build.VERSION.SDK_INT,
            tiramisuExtensionVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU)
            } else {
                0
            },
        ),
    )
}
