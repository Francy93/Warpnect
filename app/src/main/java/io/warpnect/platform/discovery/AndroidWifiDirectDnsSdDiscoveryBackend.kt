package io.warpnect.platform.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Handler
import io.warpnect.session.discovery.DiscoveryAdvertisementCodec
import io.warpnect.session.discovery.DiscoveryAdvertisementDecodeResult
import io.warpnect.session.discovery.DiscoveryAdvertisementError
import io.warpnect.session.discovery.DiscoveryBackend
import io.warpnect.session.discovery.DiscoveryBackendAdvertisingRequest
import io.warpnect.session.discovery.DiscoveryBackendCommandResult
import io.warpnect.session.discovery.DiscoveryBackendObserver
import io.warpnect.session.discovery.DiscoveryBackendOperation
import io.warpnect.session.discovery.DiscoveryBackendState
import io.warpnect.session.discovery.DiscoveryBounds
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoveryOpaqueRouteLocator
import io.warpnect.session.discovery.DiscoveryPresenceSchema
import io.warpnect.session.discovery.DiscoveryRouteDescriptor
import io.warpnect.session.discovery.DiscoveryRouteKind
import io.warpnect.session.discovery.DiscoveryRouteObservation
import java.util.Locale

/**
 * Wi-Fi Direct DNS-SD adapter. It advertises and discovers service records only; it never creates
 * a Wi-Fi P2P connection or group. Raw WifiP2pDevice references stay private and ephemeral.
 */
class AndroidWifiDirectDnsSdDiscoveryBackend(
    context: Context,
    private val controlHandler: Handler,
    private val permissionPlanner: AndroidDiscoveryPermissionPlanner = AndroidDiscoveryPermissionPlanner(),
    private val environmentProvider: AndroidDiscoveryEnvironmentProvider =
        AndroidDiscoveryEnvironmentProvider(context.applicationContext),
    private val p2pManager: WifiP2pManager? = context.applicationContext.getSystemService(
        Context.WIFI_P2P_SERVICE,
    ) as? WifiP2pManager,
) : DiscoveryBackend {
    override val kind: DiscoveryRouteKind = DiscoveryRouteKind.Direct

    private val appContext = context.applicationContext
    private var observer: DiscoveryBackendObserver? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var p2pEnabled = true
    private var advertisingGeneration: Long? = null
    private var advertisingControllerGeneration: Long? = null
    private var browsingGeneration: Long? = null
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private val directPeersByRouteKey = mutableMapOf<String, WifiP2pDevice>()

    private val p2pStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) return
            p2pEnabled = intent.getIntExtra(
                WifiP2pManager.EXTRA_WIFI_STATE,
                WifiP2pManager.WIFI_P2P_STATE_DISABLED,
            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
            if (!p2pEnabled) {
                browsingGeneration?.let { generation ->
                    observer?.onBackendState(
                        generation,
                        kind,
                        DiscoveryBackendOperation.Browsing,
                        null,
                        DiscoveryBackendState.Failed,
                        DiscoveryError.P2pDisabled,
                    )
                }
                advertisingControllerGeneration?.let { generation ->
                    observer?.onBackendState(
                        generation,
                        kind,
                        DiscoveryBackendOperation.Advertising,
                        advertisingGeneration,
                        DiscoveryBackendState.Failed,
                        DiscoveryError.P2pDisabled,
                    )
                }
            }
        }
    }

    override fun prepare(observer: DiscoveryBackendObserver): DiscoveryBackendCommandResult {
        this.observer = observer
        val manager = p2pManager ?: return DiscoveryBackendCommandResult.rejected(
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        ensureStateReceiver()
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return DiscoveryBackendCommandResult.rejected(capabilityError)
        val initializedChannel = manager.initialize(
            appContext,
            controlHandler.looper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    dispatchToControl { notifyChannelLost() }
                }
            },
        ) ?: return DiscoveryBackendCommandResult.rejected(DiscoveryError.DirectDiscoveryUnsupported)
        channel = initializedChannel
        return try {
            manager.setDnsSdResponseListeners(
                initializedChannel,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
                    // TXT data is the authoritative Warpnect record. The paired listener is
                    // intentionally registered so framework DNS-SD response handling remains
                    // complete without manufacturing a peer identity from a device address.
                },
                WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device ->
                    dispatchToControl {
                        publishTxtRecord(fullDomain, record, device)
                    }
                },
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            DiscoveryBackendCommandResult.rejected(DiscoveryError.DirectPermissionDenied)
        }
    }

    override fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult {
        val manager = p2pManager ?: return DiscoveryBackendCommandResult.rejected(
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        val activeChannel = channel ?: return DiscoveryBackendCommandResult.rejected(DiscoveryError.NotPrepared)
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return DiscoveryBackendCommandResult.rejected(capabilityError)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            request.serviceInstanceName,
            DiscoveryPresenceSchema.SERVICE_TYPE,
            DiscoveryAdvertisementCodec.encode(request.advertisement, includeBootstrapPort = true),
        )
        localService = serviceInfo
        advertisingGeneration = request.advertisementGeneration
        advertisingControllerGeneration = request.controllerGeneration
        return try {
            manager.addLocalService(
                activeChannel,
                serviceInfo,
                actionListener(
                    controllerGeneration = request.controllerGeneration,
                    operation = DiscoveryBackendOperation.Advertising,
                    operationGeneration = request.advertisementGeneration,
                    onSuccess = {
                        observer?.onBackendState(
                            request.controllerGeneration,
                            kind,
                            DiscoveryBackendOperation.Advertising,
                            request.advertisementGeneration,
                            DiscoveryBackendState.Running,
                        )
                    },
                ),
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            clearAdvertisingIfMatches(request.controllerGeneration, request.advertisementGeneration)
            DiscoveryBackendCommandResult.rejected(DiscoveryError.DirectPermissionDenied)
        } catch (_: IllegalArgumentException) {
            clearAdvertisingIfMatches(request.controllerGeneration, request.advertisementGeneration)
            DiscoveryBackendCommandResult.rejected(DiscoveryError.RegistrationFailed)
        }
    }

    override fun stopAdvertising(controllerGeneration: Long, advertisementGeneration: Long?) {
        val service = localService ?: return
        clearAdvertisingIfMatches(controllerGeneration, advertisementGeneration)
        try {
            p2pManager?.removeLocalService(
                channel ?: return,
                service,
                removalActionListener(controllerGeneration),
            )
        } catch (_: SecurityException) {
            observer?.onBackendDiagnostic(controllerGeneration, kind, DiscoveryError.DirectPermissionDenied)
        }
    }

    override fun startBrowsing(controllerGeneration: Long): DiscoveryBackendCommandResult {
        val manager = p2pManager ?: return DiscoveryBackendCommandResult.rejected(
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        val activeChannel = channel ?: return DiscoveryBackendCommandResult.rejected(DiscoveryError.NotPrepared)
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return DiscoveryBackendCommandResult.rejected(capabilityError)
        val request = WifiP2pDnsSdServiceRequest.newInstance(DiscoveryPresenceSchema.SERVICE_TYPE)
        serviceRequest = request
        browsingGeneration = controllerGeneration
        return try {
            manager.addServiceRequest(
                activeChannel,
                request,
                actionListener(
                    controllerGeneration,
                    DiscoveryBackendOperation.Browsing,
                    operationGeneration = null,
                    onSuccess = {
                        startServiceDiscovery(controllerGeneration, request)
                    },
                ),
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            clearBrowsingIfMatches(controllerGeneration)
            DiscoveryBackendCommandResult.rejected(DiscoveryError.DirectPermissionDenied)
        } catch (_: IllegalArgumentException) {
            clearBrowsingIfMatches(controllerGeneration)
            DiscoveryBackendCommandResult.rejected(DiscoveryError.DiscoveryFailed)
        }
    }

    override fun stopBrowsing(controllerGeneration: Long) {
        val request = serviceRequest ?: return
        clearBrowsingIfMatches(controllerGeneration)
        directPeersByRouteKey.clear()
        try {
            p2pManager?.removeServiceRequest(
                channel ?: return,
                request,
                removalActionListener(controllerGeneration),
            )
        } catch (_: SecurityException) {
            observer?.onBackendDiagnostic(controllerGeneration, kind, DiscoveryError.DirectPermissionDenied)
        }
    }

    override fun close() {
        advertisingControllerGeneration?.let { generation -> stopAdvertising(generation, advertisingGeneration) }
        browsingGeneration?.let(::stopBrowsing)
        directPeersByRouteKey.clear()
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(p2pStateReceiver)
            } catch (_: IllegalArgumentException) {
                // The framework may have already removed a receiver after process teardown.
            }
            receiverRegistered = false
        }
        observer = null
        channel = null
    }

    /** Platform-private lookup seam for RFC-005G; the opaque locator never enters normal snapshots. */
    internal fun directPeer(locator: DiscoveryOpaqueRouteLocator): WifiP2pDevice? =
        directPeersByRouteKey[locator.value.removePrefix("direct:")]

    private fun startServiceDiscovery(controllerGeneration: Long, request: WifiP2pDnsSdServiceRequest) {
        if (browsingGeneration != controllerGeneration || serviceRequest != request) return
        val manager = p2pManager ?: return
        val activeChannel = channel ?: return
        try {
            manager.discoverServices(
                activeChannel,
                actionListener(
                    controllerGeneration,
                    DiscoveryBackendOperation.Browsing,
                    operationGeneration = null,
                    onSuccess = {
                        if (browsingGeneration == controllerGeneration) {
                            observer?.onBackendState(
                                controllerGeneration,
                                kind,
                                DiscoveryBackendOperation.Browsing,
                                null,
                                DiscoveryBackendState.Running,
                            )
                        }
                    },
                ),
            )
        } catch (_: SecurityException) {
            observer?.onBackendState(
                controllerGeneration,
                kind,
                DiscoveryBackendOperation.Browsing,
                null,
                DiscoveryBackendState.Failed,
                DiscoveryError.DirectPermissionDenied,
            )
        }
    }

    private fun publishTxtRecord(fullDomain: String, record: Map<String, String>, device: WifiP2pDevice) {
        val controllerGeneration = browsingGeneration ?: return
        if (!fullDomain.matchesWarpnectServiceType()) return
        val decoded = DiscoveryAdvertisementCodec.decode(record)
        if (decoded is DiscoveryAdvertisementDecodeResult.Rejected) {
            observer?.onMalformedAdvertisement(controllerGeneration, kind, decoded.error)
            return
        }
        val advertisement = (decoded as DiscoveryAdvertisementDecodeResult.Decoded).advertisement
        val port = advertisement.bootstrapPort
        if (port == null) {
            observer?.onMalformedAdvertisement(controllerGeneration, kind, DiscoveryAdvertisementError.InvalidPort)
            return
        }
        val deviceAddress = device.deviceAddress.takeIf(String::isNotBlank) ?: return
        val routeKey = "$deviceAddress|$fullDomain"
        if (routeKey !in directPeersByRouteKey &&
            directPeersByRouteKey.size >= DiscoveryBounds.HARD_MAX_DISCOVERED_PRESENCES
        ) {
            observer?.onRouteCapacityDropped(controllerGeneration, kind)
            return
        }
        directPeersByRouteKey[routeKey] = WifiP2pDevice(device)
        observer?.onRouteObserved(
            controllerGeneration,
            DiscoveryRouteObservation(
                backendRouteKey = routeKey,
                kind = kind,
                advertisement = advertisement,
                descriptor = DiscoveryRouteDescriptor.Direct(
                    port = port,
                    peerLocator = DiscoveryOpaqueRouteLocator("direct:$routeKey"),
                ),
            ),
        )
    }

    private fun actionListener(
        controllerGeneration: Long,
        operation: DiscoveryBackendOperation,
        operationGeneration: Long?,
        onSuccess: () -> Unit,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            dispatchToControl(onSuccess)
        }

        override fun onFailure(reason: Int) {
            dispatchToControl {
                observer?.onBackendState(
                    controllerGeneration,
                    kind,
                    operation,
                    operationGeneration,
                    DiscoveryBackendState.Failed,
                    reason.toDiscoveryError(),
                )
            }
        }
    }

    /** Cleanup is best effort; an old removal failure must not fail a newer advertising epoch. */
    private fun removalActionListener(controllerGeneration: Long): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                dispatchToControl {
                    observer?.onBackendDiagnostic(
                        controllerGeneration,
                        kind,
                        reason.toDiscoveryError(),
                    )
                }
            }
        }

    private fun notifyChannelLost() {
        browsingGeneration?.let { generation ->
            observer?.onBackendState(
                generation,
                kind,
                DiscoveryBackendOperation.Browsing,
                null,
                DiscoveryBackendState.Failed,
                DiscoveryError.P2pChannelLost,
            )
        }
        advertisingControllerGeneration?.let { generation ->
            observer?.onBackendState(
                generation,
                kind,
                DiscoveryBackendOperation.Advertising,
                advertisingGeneration,
                DiscoveryBackendState.Failed,
                DiscoveryError.P2pChannelLost,
            )
        }
        channel = null
    }

    private fun ensureStateReceiver() {
        if (receiverRegistered) return
        appContext.registerReceiver(
            p2pStateReceiver,
            IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION),
            null,
            controlHandler,
        )
        receiverRegistered = true
    }

    private fun directReadinessError(): DiscoveryError = permissionPlanner
        .planWifiDirect(environmentProvider.wifiDirectEnvironment(p2pEnabled))
        .error

    private fun clearAdvertisingIfMatches(controllerGeneration: Long, advertisementGeneration: Long?) {
        if (advertisingControllerGeneration == controllerGeneration &&
            this.advertisingGeneration == advertisementGeneration
        ) {
            localService = null
            advertisingGeneration = null
            advertisingControllerGeneration = null
        }
    }

    private fun clearBrowsingIfMatches(controllerGeneration: Long) {
        if (browsingGeneration == controllerGeneration) {
            serviceRequest = null
            browsingGeneration = null
        }
    }

    private fun dispatchToControl(block: () -> Unit) {
        controlHandler.post(block)
    }
}

private fun Int.toDiscoveryError(): DiscoveryError = when (this) {
    WifiP2pManager.P2P_UNSUPPORTED -> DiscoveryError.DirectDiscoveryUnsupported
    else -> DiscoveryError.DiscoveryFailed
}

internal fun String.matchesWarpnectServiceType(): Boolean {
    val domain = trimEnd('.').lowercase(Locale.ROOT)
    val serviceType = DiscoveryPresenceSchema.SERVICE_TYPE
    return domain == serviceType ||
        domain.endsWith(".$serviceType") ||
        domain.endsWith(".$serviceType.local")
}
