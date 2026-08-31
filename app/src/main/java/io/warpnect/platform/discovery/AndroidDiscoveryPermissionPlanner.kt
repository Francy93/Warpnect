package io.warpnect.platform.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import io.warpnect.session.discovery.DiscoveryError

const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
const val ACCESS_FINE_LOCATION_PERMISSION = "android.permission.ACCESS_FINE_LOCATION"
const val NEARBY_WIFI_DEVICES_PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"

data class AndroidLanPermissionEnvironment(
    val deviceSdk: Int,
    val targetSdk: Int,
    val accessLocalNetworkDeclared: Boolean,
    val accessLocalNetworkGranted: Boolean,
)

enum class AndroidLanPermissionPlan {
    NotRequiredForCurrentTarget,
    Granted,
    PermissionRequired,
}

data class AndroidWifiDirectDiscoveryEnvironment(
    val deviceSdk: Int,
    val targetSdk: Int,
    val nearbyWifiDevicesGranted: Boolean,
    val fineLocationGranted: Boolean,
    val locationServicesEnabled: Boolean,
    val featureSupported: Boolean,
    val managerAvailable: Boolean,
    val p2pEnabled: Boolean,
)

enum class AndroidWifiDirectDiscoveryPlan(
    val error: DiscoveryError,
) {
    Ready(DiscoveryError.None),
    PermissionRequired(DiscoveryError.DirectPermissionRequired),
    LocationServicesDisabled(DiscoveryError.LocationServicesDisabled),
    Unsupported(DiscoveryError.DirectDiscoveryUnsupported),
    Disabled(DiscoveryError.P2pDisabled),
}

/**
 * Pure, version-gated permission and capability planner. It reports requirements to the app; it
 * never calls requestPermissions() or silently changes the requested backend policy.
 */
class AndroidDiscoveryPermissionPlanner {
    /**
     * Runtime permissions required before the app may invoke Wi-Fi Direct discovery APIs. This is
     * deliberately separate from capability readiness: a disabled P2P radio must not hide a
     * missing runtime permission from the UI.
     */
    fun wifiDirectRuntimePermissions(environment: AndroidWifiDirectDiscoveryEnvironment): Set<String> = when {
        environment.deviceSdk >= Build.VERSION_CODES.TIRAMISU &&
            environment.targetSdk >= Build.VERSION_CODES.TIRAMISU &&
            !environment.nearbyWifiDevicesGranted -> setOf(NEARBY_WIFI_DEVICES_PERMISSION)
        environment.deviceSdk <= Build.VERSION_CODES.S_V2 && !environment.fineLocationGranted -> {
            setOf(ACCESS_FINE_LOCATION_PERMISSION)
        }
        else -> emptySet()
    }

    fun planLan(environment: AndroidLanPermissionEnvironment): AndroidLanPermissionPlan = when {
        environment.targetSdk < ANDROID_17_SDK || environment.deviceSdk < ANDROID_17_SDK -> {
            AndroidLanPermissionPlan.NotRequiredForCurrentTarget
        }
        environment.accessLocalNetworkDeclared && environment.accessLocalNetworkGranted -> {
            AndroidLanPermissionPlan.Granted
        }
        else -> AndroidLanPermissionPlan.PermissionRequired
    }

    fun lanError(environment: AndroidLanPermissionEnvironment): DiscoveryError = when (planLan(environment)) {
        AndroidLanPermissionPlan.NotRequiredForCurrentTarget,
        AndroidLanPermissionPlan.Granted,
        -> DiscoveryError.None
        AndroidLanPermissionPlan.PermissionRequired -> DiscoveryError.LanPermissionRequired
    }

    fun planWifiDirect(environment: AndroidWifiDirectDiscoveryEnvironment): AndroidWifiDirectDiscoveryPlan = when {
        !environment.featureSupported || !environment.managerAvailable -> {
            AndroidWifiDirectDiscoveryPlan.Unsupported
        }
        !environment.p2pEnabled -> AndroidWifiDirectDiscoveryPlan.Disabled
        wifiDirectRuntimePermissions(environment).isNotEmpty() -> AndroidWifiDirectDiscoveryPlan.PermissionRequired
        !environment.locationServicesEnabled -> {
            AndroidWifiDirectDiscoveryPlan.LocationServicesDisabled
        }
        else -> AndroidWifiDirectDiscoveryPlan.Ready
    }

    companion object {
        const val ANDROID_17_SDK = 37
    }
}

/** Android-only environment query isolated from the discovery core and permission UI. */
class AndroidDiscoveryEnvironmentProvider(
    private val context: Context,
) {
    fun lanEnvironment(): AndroidLanPermissionEnvironment = AndroidLanPermissionEnvironment(
        deviceSdk = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
        accessLocalNetworkDeclared = requestedPermissions().contains(ACCESS_LOCAL_NETWORK_PERMISSION),
        accessLocalNetworkGranted = isGranted(ACCESS_LOCAL_NETWORK_PERMISSION),
    )

    fun wifiDirectEnvironment(p2pEnabled: Boolean): AndroidWifiDirectDiscoveryEnvironment =
        AndroidWifiDirectDiscoveryEnvironment(
            deviceSdk = Build.VERSION.SDK_INT,
            targetSdk = context.applicationInfo.targetSdkVersion,
            nearbyWifiDevicesGranted = isGranted(NEARBY_WIFI_DEVICES_PERMISSION),
            fineLocationGranted = isGranted("android.permission.ACCESS_FINE_LOCATION"),
            locationServicesEnabled = locationServicesEnabled(),
            featureSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            managerAvailable = context.getSystemService(Context.WIFI_P2P_SERVICE) != null,
            p2pEnabled = p2pEnabled,
        )

    fun missingWifiDirectRuntimePermissions(): Set<String> = AndroidDiscoveryPermissionPlanner()
        .wifiDirectRuntimePermissions(wifiDirectEnvironment(p2pEnabled = true))

    private fun requestedPermissions(): Set<String> = try {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        emptySet()
    }

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun locationServicesEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }
}
