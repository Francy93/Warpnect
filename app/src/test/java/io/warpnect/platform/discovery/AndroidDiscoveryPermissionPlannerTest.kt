package io.warpnect.platform.discovery

import io.warpnect.session.discovery.DiscoveryError
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDiscoveryPermissionPlannerTest {
    private val planner = AndroidDiscoveryPermissionPlanner()

    @Test
    fun lanPermissionPlannerKeepsTargetThirtyFiveOnTheCurrentImplicitAccessPath() {
        assertEquals(
            AndroidLanPermissionPlan.NotRequiredForCurrentTarget,
            planner.planLan(
                AndroidLanPermissionEnvironment(
                    deviceSdk = 37,
                    targetSdk = 35,
                    accessLocalNetworkDeclared = false,
                    accessLocalNetworkGranted = false,
                ),
            ),
        )
        assertEquals(
            AndroidLanPermissionPlan.PermissionRequired,
            planner.planLan(
                AndroidLanPermissionEnvironment(
                    deviceSdk = 37,
                    targetSdk = 37,
                    accessLocalNetworkDeclared = false,
                    accessLocalNetworkGranted = false,
                ),
            ),
        )
        assertEquals(
            AndroidLanPermissionPlan.Granted,
            planner.planLan(
                AndroidLanPermissionEnvironment(
                    deviceSdk = 37,
                    targetSdk = 37,
                    accessLocalNetworkDeclared = true,
                    accessLocalNetworkGranted = true,
                ),
            ),
        )
    }

    @Test
    fun wifiDirectPlannerSeparatesNearbyDevicesLegacyLocationAndCapabilityStates() {
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.PermissionRequired,
            planner.planWifiDirect(
                directEnvironment(deviceSdk = 33, targetSdk = 35, nearbyGranted = false),
            ),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.LocationServicesDisabled,
            planner.planWifiDirect(
                directEnvironment(
                    deviceSdk = 33,
                    targetSdk = 35,
                    nearbyGranted = true,
                    locationEnabled = false,
                ),
            ),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.PermissionRequired,
            planner.planWifiDirect(
                directEnvironment(deviceSdk = 32, targetSdk = 32, fineLocationGranted = false),
            ),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.LocationServicesDisabled,
            planner.planWifiDirect(
                directEnvironment(
                    deviceSdk = 32,
                    targetSdk = 32,
                    fineLocationGranted = true,
                    locationEnabled = false,
                ),
            ),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.Ready,
            planner.planWifiDirect(
                directEnvironment(deviceSdk = 32, targetSdk = 32, fineLocationGranted = true),
            ),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.Unsupported,
            planner.planWifiDirect(directEnvironment(featureSupported = false)),
        )
        assertEquals(
            AndroidWifiDirectDiscoveryPlan.Disabled,
            planner.planWifiDirect(directEnvironment(p2pEnabled = false)),
        )
        assertEquals(
            DiscoveryError.LocationServicesDisabled,
            AndroidWifiDirectDiscoveryPlan.LocationServicesDisabled.error,
        )
    }

    @Test
    fun wifiDirectRuntimePermissionPlanRequestsNearbyBeforeP2pCallsOnApiThirtyThreeAndLater() {
        assertEquals(
            setOf(NEARBY_WIFI_DEVICES_PERMISSION),
            planner.wifiDirectRuntimePermissions(
                directEnvironment(deviceSdk = 36, targetSdk = 35, nearbyGranted = false, p2pEnabled = false),
            ),
        )
        assertEquals(
            emptySet<String>(),
            planner.wifiDirectRuntimePermissions(
                directEnvironment(deviceSdk = 36, targetSdk = 35, nearbyGranted = true, p2pEnabled = false),
            ),
        )
    }

    @Test
    fun wifiDirectRuntimePermissionPlanUsesFineLocationOnlyOnLegacyAndroid() {
        assertEquals(
            setOf(ACCESS_FINE_LOCATION_PERMISSION),
            planner.wifiDirectRuntimePermissions(
                directEnvironment(deviceSdk = 32, targetSdk = 32, fineLocationGranted = false),
            ),
        )
    }

    @Test
    fun multicastPlannerOnlyRequestsCompatibilityLockBeforeTiramisuExtensionSeven() {
        val multicastPlanner = AndroidDiscoveryMulticastLockPlanner()
        assertEquals(
            AndroidMulticastLockPlan.AcquireCompatibilityLock,
            multicastPlanner.plan(AndroidMulticastLockEnvironment(deviceSdk = 32, tiramisuExtensionVersion = 0)),
        )
        assertEquals(
            AndroidMulticastLockPlan.AcquireCompatibilityLock,
            multicastPlanner.plan(AndroidMulticastLockEnvironment(deviceSdk = 33, tiramisuExtensionVersion = 6)),
        )
        assertEquals(
            AndroidMulticastLockPlan.SystemManagedForeground,
            multicastPlanner.plan(AndroidMulticastLockEnvironment(deviceSdk = 33, tiramisuExtensionVersion = 7)),
        )
    }

    private fun directEnvironment(
        deviceSdk: Int = 33,
        targetSdk: Int = 35,
        nearbyGranted: Boolean = true,
        fineLocationGranted: Boolean = true,
        locationEnabled: Boolean = true,
        featureSupported: Boolean = true,
        managerAvailable: Boolean = true,
        p2pEnabled: Boolean = true,
    ): AndroidWifiDirectDiscoveryEnvironment = AndroidWifiDirectDiscoveryEnvironment(
        deviceSdk = deviceSdk,
        targetSdk = targetSdk,
        nearbyWifiDevicesGranted = nearbyGranted,
        fineLocationGranted = fineLocationGranted,
        locationServicesEnabled = locationEnabled,
        featureSupported = featureSupported,
        managerAvailable = managerAvailable,
        p2pEnabled = p2pEnabled,
    )
}
