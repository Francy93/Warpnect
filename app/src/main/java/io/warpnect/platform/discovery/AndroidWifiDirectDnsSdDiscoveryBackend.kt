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
    private val debugLog = AndroidDiscoveryDebugLog(context)
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
                debugLog.event(kind, "p2p_disabled", DiscoveryError.P2pDisabled)
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
        val manager = p2pManager ?: return rejected(
            "prepare_failed",
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        ensureStateReceiver()
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return rejected("prepare_failed", capabilityError)
        val initializedChannel = manager.initialize(
            appContext,
            controlHandler.looper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    dispatchToControl { notifyChannelLost() }
                }
            },
        ) ?: return rejected("prepare_failed", DiscoveryError.DirectDiscoveryUnsupported)
        channel = initializedChannel
        return try {
            manager.setDnsSdResponseListeners(
                initializedChannel,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ ->
                    // TXT data is the authoritative Warpnect record. The paired listener is
                    // intentionally registered so framework DNS-SD response handling remains
                    // complete without manufacturing a peer identity from a device address.
                    dispatchToControl { debugLog.event(kind, "dns_sd_service_response") }
                },
                WifiP2pManager.DnsSdTxtRecordListener { fullDomain, record, device ->
                    dispatchToControl {
                        debugLog.event(kind, "dns_sd_txt_record_received")
                        publishTxtRecord(fullDomain, record, device)
                    }
                },
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            rejected("prepare_failed", DiscoveryError.DirectPermissionDenied)
        }
    }

    override fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult {
        val manager = p2pManager ?: return rejected(
            "local_service_failed",
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        val activeChannel = channel ?: return rejected("local_service_failed", DiscoveryError.NotPrepared)
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return rejected("local_service_failed", capabilityError)
        val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
            request.serviceInstanceName,
            DiscoveryPresenceSchema.SERVICE_TYPE,
            DiscoveryAdvertisementCodec.encode(request.advertisement, includeBootstrapPort = true),
        )
        localService = serviceInfo
        advertisingGeneration = request.advertisementGeneration
        advertisingControllerGeneration = request.controllerGeneration
        return try {
            debugLog.event(kind, "local_service_add_requested")
            manager.addLocalService(
                activeChannel,
                serviceInfo,
                actionListener(
                    controllerGeneration = request.controllerGeneration,
                    operation = DiscoveryBackendOperation.Advertising,
                    operationGeneration = request.advertisementGeneration,
                    onSuccess = {
                        debugLog.event(kind, "local_service_added")
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
            rejected("local_service_failed", DiscoveryError.DirectPermissionDenied)
        } catch (_: IllegalArgumentException) {
            clearAdvertisingIfMatches(request.controllerGeneration, request.advertisementGeneration)
            rejected("local_service_failed", DiscoveryError.RegistrationFailed)
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
        val manager = p2pManager ?: return rejected(
            "service_request_failed",
            DiscoveryError.DirectDiscoveryUnsupported,
        )
        val activeChannel = channel ?: return rejected("service_request_failed", DiscoveryError.NotPrepared)
        val capabilityError = directReadinessError()
        if (capabilityError != DiscoveryError.None) return rejected("service_request_failed", capabilityError)
        val request = WifiP2pDnsSdServiceRequest.newInstance(DiscoveryPresenceSchema.SERVICE_TYPE)
        serviceRequest = request
        browsingGeneration = controllerGeneration
        return try {
            debugLog.event(kind, "service_request_add_requested")
            manager.addServiceRequest(
                activeChannel,
                request,
                actionListener(
                    controllerGeneration,
                    DiscoveryBackendOperation.Browsing,
                    operationGeneration = null,
                    onSuccess = {
                        debugLog.event(kind, "service_request_added")
                        startServiceDiscovery(controllerGeneration, request)
                    },
                ),
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            clearBrowsingIfMatches(controllerGeneration)
            rejected("service_request_failed", DiscoveryError.DirectPermissionDenied)
        } catch (_: IllegalArgumentException) {
            clearBrowsingIfMatches(controllerGeneration)
            rejected("service_request_failed", DiscoveryError.DiscoveryFailed)
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
        val manager = p2pManager ?: run {
            reportBrowsingFailure(controllerGeneration, DiscoveryError.DirectDiscoveryUnsupported)
            return
        }
        val activeChannel = channel ?: run {
            reportBrowsingFailure(controllerGeneration, DiscoveryError.NotPrepared)
            return
        }
        try {
            debugLog.event(kind, "discovery_start_requested")
            manager.discoverServices(
                activeChannel,
                actionListener(
                    controllerGeneration,
                    DiscoveryBackendOperation.Browsing,
                    operationGeneration = null,
                    onSuccess = {
                        if (browsingGeneration == controllerGeneration) {
                            debugLog.event(kind, "discovery_started")
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
            reportBrowsingFailure(controllerGeneration, DiscoveryError.DirectPermissionDenied)
        }
    }

    private fun publishTxtRecord(fullDomain: String, record: Map<String, String>, device: WifiP2pDevice) {
        val controllerGeneration = browsingGeneration ?: return
        if (!fullDomain.matchesWarpnectServiceType()) {
            debugLog.event(kind, "dns_sd_txt_ignored_service")
            return
        }
        val decoded = DiscoveryAdvertisementCodec.decode(record)
        if (decoded is DiscoveryAdvertisementDecodeResult.Rejected) {
            debugLog.event(kind, "dns_sd_txt_rejected")
            observer?.onMalformedAdvertisement(controllerGeneration, kind, decoded.error)
            return
        }
        val advertisement = (decoded as DiscoveryAdvertisementDecodeResult.Decoded).advertisement
        val port = advertisement.bootstrapPort
        if (port == null) {
            debugLog.event(kind, "dns_sd_txt_missing_port")
            observer?.onMalformedAdvertisement(controllerGeneration, kind, DiscoveryAdvertisementError.InvalidPort)
            return
        }
        val deviceAddress = device.deviceAddress.takeIf(String::isNotBlank) ?: run {
            debugLog.event(kind, "dns_sd_txt_missing_peer")
            return
        }
        val routeKey = "$deviceAddress|$fullDomain"
        if (routeKey !in directPeersByRouteKey &&
            directPeersByRouteKey.size >= DiscoveryBounds.HARD_MAX_DISCOVERED_PRESENCES
        ) {
            debugLog.event(kind, "dns_sd_route_capacity_dropped")
            observer?.onRouteCapacityDropped(controllerGeneration, kind)
            return
        }
        directPeersByRouteKey[routeKey] = WifiP2pDevice(device)
        debugLog.event(kind, "dns_sd_txt_accepted")
        debugLog.routeObserved(kind)
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
                val event = when (operation) {
                    DiscoveryBackendOperation.Advertising -> "local_service_failed"
                    DiscoveryBackendOperation.Browsing -> "discovery_failed"
                }
                debugLog.event(kind, event, reason.toDiscoveryError(), reason)
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
        debugLog.event(kind, "channel_lost", DiscoveryError.P2pChannelLost)
        debugLog.p2pChannelDisconnected()
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

    private fun rejected(event: String, error: DiscoveryError): DiscoveryBackendCommandResult {
        debugLog.event(kind, event, error)
        return DiscoveryBackendCommandResult.rejected(error)
    }

    private fun reportBrowsingFailure(controllerGeneration: Long, error: DiscoveryError) {
        debugLog.event(kind, "discovery_failed", error)
        observer?.onBackendState(
            controllerGeneration,
            kind,
            DiscoveryBackendOperation.Browsing,
            null,
            DiscoveryBackendState.Failed,
            error,
        )
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
