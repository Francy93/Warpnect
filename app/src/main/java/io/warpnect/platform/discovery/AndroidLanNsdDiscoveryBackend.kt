package io.warpnect.platform.discovery

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.ext.SdkExtensions
import io.warpnect.session.discovery.DiscoveryAddressCandidate
import io.warpnect.session.discovery.DiscoveryAdvertisementCodec
import io.warpnect.session.discovery.DiscoveryAdvertisementDecodeResult
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
import io.warpnect.session.discovery.DiscoveryRouteLiveness
import io.warpnect.session.discovery.DiscoveryRouteObservation
import io.warpnect.session.discovery.isValidPort
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

/** Android LAN DNS-SD adapter. Its route objects remain private until controlled route lookup. */
class AndroidLanNsdDiscoveryBackend(
    context: Context,
    private val dispatchToControl: ((() -> Unit) -> Unit),
    private val permissionPlanner: AndroidDiscoveryPermissionPlanner = AndroidDiscoveryPermissionPlanner(),
    private val environmentProvider: AndroidDiscoveryEnvironmentProvider =
        AndroidDiscoveryEnvironmentProvider(context.applicationContext),
    private val multicastLock: AndroidDiscoveryMulticastLock = AndroidDiscoveryMulticastLock(
        context.applicationContext,
    ),
    private val nsdManager: NsdManager? = context.applicationContext.getSystemService(NsdManager::class.java),
) : DiscoveryBackend {
    override val kind: DiscoveryRouteKind = DiscoveryRouteKind.Lan

    private val debugLog = AndroidDiscoveryDebugLog(context)
    private var observer: DiscoveryBackendObserver? = null
    private var registration: Registration? = null
    private var discovery: Discovery? = null
    private val resolutionQueue = SerializedServiceResolutionQueue<PendingResolution>(MAX_PENDING_RESOLUTIONS)
    private val lanNetworksByRouteKey = mutableMapOf<String, Network?>()

    override fun prepare(observer: DiscoveryBackendObserver): DiscoveryBackendCommandResult {
        this.observer = observer
        val permissionError = permissionPlanner.lanError(environmentProvider.lanEnvironment())
        if (permissionError != DiscoveryError.None) {
            debugLog.event(kind, "prepare_failed", permissionError)
            return DiscoveryBackendCommandResult.rejected(permissionError)
        }
        return if (nsdManager != null) {
            DiscoveryBackendCommandResult.Accepted
        } else {
            debugLog.event(kind, "prepare_failed", DiscoveryError.RequiredBackendUnavailable)
            DiscoveryBackendCommandResult.rejected(DiscoveryError.RequiredBackendUnavailable)
        }
    }

    override fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult {
        val manager = nsdManager ?: return rejected("registration_failed", DiscoveryError.RequiredBackendUnavailable)
        val permissionError = permissionPlanner.lanError(environmentProvider.lanEnvironment())
        if (permissionError != DiscoveryError.None) return rejected("registration_failed", permissionError)
        val multicastError = multicastLock.acquireForActiveNsd()
        if (multicastError != DiscoveryError.None) return rejected("registration_failed", multicastError)

        val info = NsdServiceInfo().apply {
            serviceName = request.serviceInstanceName
            serviceType = DiscoveryPresenceSchema.LAN_SERVICE_TYPE
            port = requireNotNull(request.advertisement.bootstrapPort)
            DiscoveryAdvertisementCodec.encode(request.advertisement, includeBootstrapPort = false).forEach {
                    (key, value) ->
                setAttribute(key, value)
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                dispatchToControl {
                    debugLog.event(
                        kind,
                        "registration_failed",
                        DiscoveryError.RegistrationFailed,
                        errorCode,
                    )
                    if (registration?.matches(request) == true) {
                        debugLog.hostRegistrationLost()
                        observer?.onBackendState(
                            request.controllerGeneration,
                            kind,
                            DiscoveryBackendOperation.Advertising,
                            request.advertisementGeneration,
                            DiscoveryBackendState.Failed,
                            DiscoveryError.RegistrationFailed,
                        )
                    }
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                dispatchToControl {
                    observer?.onBackendDiagnostic(
                        request.controllerGeneration,
                        kind,
                        DiscoveryError.RegistrationFailed,
                    )
                }
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                dispatchToControl {
                    debugLog.event(kind, "registration_succeeded")
                    if (registration?.matches(request) == true) {
                        debugLog.hostRegistrationActive()
                        observer?.onEffectiveLanServiceName(
                            request.controllerGeneration,
                            request.advertisementGeneration,
                            serviceInfo.serviceName,
                        )
                        observer?.onBackendState(
                            request.controllerGeneration,
                            kind,
                            DiscoveryBackendOperation.Advertising,
                            request.advertisementGeneration,
                            DiscoveryBackendState.Running,
                        )
                    }
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                dispatchToControl {
                    if (registration?.matches(request) == true) debugLog.hostRegistrationLost()
                }
            }
        }
        registration = Registration(request, listener)
        return try {
            debugLog.event(kind, "registration_started")
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            registration = null
            maybeReleaseMulticastLock()
            rejected("registration_failed", DiscoveryError.LanPermissionDenied)
        } catch (_: IllegalArgumentException) {
            registration = null
            maybeReleaseMulticastLock()
            rejected("registration_failed", DiscoveryError.RegistrationFailed)
        }
    }

    override fun stopAdvertising(controllerGeneration: Long, advertisementGeneration: Long?) {
        val active = registration ?: return
        registration = null
        try {
            nsdManager?.unregisterService(active.listener)
        } catch (_: IllegalArgumentException) {
            // A failed/old NSD registration may already have been released by the framework.
        } finally {
            maybeReleaseMulticastLock()
        }
    }

    override fun startBrowsing(controllerGeneration: Long): DiscoveryBackendCommandResult {
        val manager = nsdManager ?: return rejected("discovery_failed", DiscoveryError.RequiredBackendUnavailable)
        val permissionError = permissionPlanner.lanError(environmentProvider.lanEnvironment())
        if (permissionError != DiscoveryError.None) return rejected("discovery_failed", permissionError)
        val multicastError = multicastLock.acquireForActiveNsd()
        if (multicastError != DiscoveryError.None) return rejected("discovery_failed", multicastError)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                dispatchToControl {
                    debugLog.event(
                        kind,
                        "discovery_failed",
                        DiscoveryError.DiscoveryFailed,
                        errorCode,
                    )
                    if (discovery?.controllerGeneration == controllerGeneration) {
                        observer?.onBackendState(
                            controllerGeneration,
                            kind,
                            DiscoveryBackendOperation.Browsing,
                            null,
                            DiscoveryBackendState.Failed,
                            DiscoveryError.DiscoveryFailed,
                        )
                    }
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                dispatchToControl {
                    debugLog.event(
                        kind,
                        "discovery_stop_failed",
                        DiscoveryError.DiscoveryFailed,
                        errorCode,
                    )
                    observer?.onBackendDiagnostic(controllerGeneration, kind, DiscoveryError.DiscoveryFailed)
                }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                dispatchToControl {
                    debugLog.event(kind, "discovery_started")
                    if (discovery?.controllerGeneration == controllerGeneration) {
                        observer?.onBackendState(
                            controllerGeneration,
                            kind,
                            DiscoveryBackendOperation.Browsing,
                            null,
                            DiscoveryBackendState.Running,
                        )
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                dispatchToControl {
                    debugLog.event(kind, "service_found")
                    if (discovery?.controllerGeneration == controllerGeneration) {
                        resolveService(controllerGeneration, serviceInfo)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                dispatchToControl {
                    if (discovery?.controllerGeneration == controllerGeneration) {
                        val serviceKey = serviceKey(serviceInfo)
                        cancelResolution(serviceKey)
                        lanNetworksByRouteKey.keys
                            .filter { it.startsWith("$serviceKey|") }
                            .toList()
                            .forEach { routeKey ->
                                lanNetworksByRouteKey.remove(routeKey)
                                observer?.onRouteLost(controllerGeneration, kind, routeKey)
                            }
                    }
                }
            }
        }
        discovery = Discovery(controllerGeneration, listener)
        return try {
            debugLog.event(kind, "discovery_start_requested")
            manager.discoverServices(
                DiscoveryPresenceSchema.LAN_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
            DiscoveryBackendCommandResult.Accepted
        } catch (_: SecurityException) {
            discovery = null
            maybeReleaseMulticastLock()
            rejected("discovery_failed", DiscoveryError.LanPermissionDenied)
        } catch (_: IllegalArgumentException) {
            discovery = null
            maybeReleaseMulticastLock()
            rejected("discovery_failed", DiscoveryError.DiscoveryFailed)
        }
    }

    override fun stopBrowsing(controllerGeneration: Long) {
        val active = discovery ?: return
        discovery = null
        cancelAllResolutions()
        lanNetworksByRouteKey.clear()
        try {
            nsdManager?.stopServiceDiscovery(active.listener)
        } catch (_: IllegalArgumentException) {
            // NSD may have already stopped a failed discovery listener.
        } finally {
            maybeReleaseMulticastLock()
        }
    }

    override fun close() {
        registration?.let { stopAdvertising(it.request.controllerGeneration, it.request.advertisementGeneration) }
        discovery?.let { stopBrowsing(it.controllerGeneration) }
        cancelAllResolutions()
        lanNetworksByRouteKey.clear()
        multicastLock.close()
        observer = null
    }

    private fun resolveService(controllerGeneration: Long, serviceInfo: NsdServiceInfo) {
        val serviceKey = serviceKey(serviceInfo)
        when (
            val result = resolutionQueue.enqueue(
                serviceKey,
                PendingResolution(controllerGeneration, serviceKey, serviceInfo),
            )
        ) {
            is SerializedServiceResolutionQueue.EnqueueResult.Start -> startResolution(result.work)
            SerializedServiceResolutionQueue.EnqueueResult.CapacityReached ->
                observer?.onRouteCapacityDropped(controllerGeneration, kind)
            SerializedServiceResolutionQueue.EnqueueResult.Duplicate,
            SerializedServiceResolutionQueue.EnqueueResult.Queued,
            -> Unit
        }
    }

    private fun startResolution(work: SerializedServiceResolutionQueue.Work<PendingResolution>) {
        val manager = nsdManager ?: return
        val pending = work.value
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(failedServiceInfo: NsdServiceInfo, errorCode: Int) {
                dispatchToControl {
                    finishResolution(work) {
                        debugLog.event(
                            kind,
                            "resolve_failed",
                            DiscoveryError.ResolutionFailed,
                            errorCode,
                        )
                        if (discovery?.controllerGeneration == pending.controllerGeneration) {
                            observer?.onBackendDiagnostic(
                                pending.controllerGeneration,
                                kind,
                                DiscoveryError.ResolutionFailed,
                            )
                        }
                    }
                }
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                dispatchToControl {
                    finishResolution(work) {
                        debugLog.event(kind, "resolve_succeeded")
                        if (discovery?.controllerGeneration == pending.controllerGeneration) {
                            publishResolvedService(
                                pending.controllerGeneration,
                                pending.serviceKey,
                                resolvedServiceInfo,
                            )
                        }
                    }
                }
            }

            override fun onResolutionStopped(serviceInfo: NsdServiceInfo) {
                dispatchToControl { finishResolution(work) }
            }

            override fun onStopResolutionFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                dispatchToControl { finishResolution(work) }
            }
        }
        pending.listener = listener
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.resolveService(pending.serviceInfo, controlExecutor, listener)
            } else {
                manager.resolveService(pending.serviceInfo, listener)
            }
        } catch (_: SecurityException) {
            finishResolution(work) {
                debugLog.event(kind, "resolve_failed", DiscoveryError.LanPermissionDenied)
                if (discovery?.controllerGeneration == pending.controllerGeneration) {
                    observer?.onBackendDiagnostic(
                        pending.controllerGeneration,
                        kind,
                        DiscoveryError.LanPermissionDenied,
                    )
                }
            }
        } catch (_: IllegalArgumentException) {
            finishResolution(work) {
                debugLog.event(kind, "resolve_failed", DiscoveryError.ResolutionFailed)
                if (discovery?.controllerGeneration == pending.controllerGeneration) {
                    observer?.onBackendDiagnostic(
                        pending.controllerGeneration,
                        kind,
                        DiscoveryError.ResolutionFailed,
                    )
                }
            }
        }
    }

    private fun finishResolution(
        work: SerializedServiceResolutionQueue.Work<PendingResolution>,
        onCurrentResolution: () -> Unit = {},
    ) {
        if (!resolutionQueue.isActive(work)) return
        val cancelled = work.cancelled
        val next = resolutionQueue.complete(work)
        if (!cancelled) onCurrentResolution()
        next?.let(::startResolution)
    }

    private fun publishResolvedService(controllerGeneration: Long, serviceKey: String, serviceInfo: NsdServiceInfo) {
        val attributes = serviceInfo.attributes.decodeStrictUtf8()
        if (attributes == null) {
            observer?.onMalformedAdvertisement(
                controllerGeneration,
                kind,
                io.warpnect.session.discovery.DiscoveryAdvertisementError.InvalidTxtEncoding,
            )
            return
        }
        val decoded = DiscoveryAdvertisementCodec.decode(attributes)
        if (decoded is DiscoveryAdvertisementDecodeResult.Rejected) {
            observer?.onMalformedAdvertisement(controllerGeneration, kind, decoded.error)
            return
        }
        val port = serviceInfo.port
        val addresses = serviceInfo.hostAddressesCompat().mapNotNull { address ->
            address.hostAddress
                ?.takeIf(String::isNotBlank)
                ?.let(::DiscoveryAddressCandidate)
        }
        if (!isValidPort(port) || addresses.isEmpty()) {
            observer?.onBackendDiagnostic(controllerGeneration, kind, DiscoveryError.ResolutionFailed)
            return
        }
        val network = serviceInfo.networkCompat()
        val routeKey = "$serviceKey|${network?.hashCode() ?: 0}"
        if (routeKey !in lanNetworksByRouteKey &&
            lanNetworksByRouteKey.size >= DiscoveryBounds.HARD_MAX_DISCOVERED_PRESENCES
        ) {
            observer?.onRouteCapacityDropped(controllerGeneration, kind)
            return
        }
        lanNetworksByRouteKey[routeKey] = network
        val advertisement = (decoded as DiscoveryAdvertisementDecodeResult.Decoded).advertisement
        debugLog.routeObserved(kind)
        observer?.onRouteObserved(
            controllerGeneration,
            DiscoveryRouteObservation(
                backendRouteKey = routeKey,
                kind = kind,
                advertisement = advertisement,
                descriptor = DiscoveryRouteDescriptor.Lan(
                    addressCandidates = addresses,
                    port = port,
                    networkLocator = network?.let { DiscoveryOpaqueRouteLocator("lan:$routeKey") },
                ),
                liveness = DiscoveryRouteLiveness.BackendTracksLoss,
            ),
        )
    }

    private fun cancelResolution(serviceKey: String) {
        val cancellation = resolutionQueue.cancel(serviceKey)
        if (cancellation !is SerializedServiceResolutionQueue.Cancellation.Active) return
        stopActiveResolutionIfSupported(cancellation.work)
    }

    private fun cancelAllResolutions() {
        resolutionQueue.cancelAll()?.let(::stopActiveResolutionIfSupported)
    }

    private fun stopActiveResolutionIfSupported(work: SerializedServiceResolutionQueue.Work<PendingResolution>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val listener = work.value.listener ?: return
        try {
            nsdManager?.stopServiceResolution(listener)
        } catch (_: IllegalArgumentException) {
            // The framework no longer owns this operation, so the serialized slot can advance.
            finishResolution(work)
        }
    }

    private fun maybeReleaseMulticastLock() {
        if (registration == null && discovery == null) multicastLock.releaseWhenInactive()
    }

    private fun serviceKey(serviceInfo: NsdServiceInfo): String =
        "${serviceInfo.serviceName}|${serviceInfo.serviceType.trimEnd('.')}"

    private fun rejected(event: String, error: DiscoveryError): DiscoveryBackendCommandResult {
        debugLog.event(kind, event, error)
        return DiscoveryBackendCommandResult.rejected(error)
    }

    private data class Registration(
        val request: DiscoveryBackendAdvertisingRequest,
        val listener: NsdManager.RegistrationListener,
    ) {
        fun matches(other: DiscoveryBackendAdvertisingRequest): Boolean =
            request.controllerGeneration == other.controllerGeneration &&
                request.advertisementGeneration == other.advertisementGeneration
    }

    private data class Discovery(
        val controllerGeneration: Long,
        val listener: NsdManager.DiscoveryListener,
    )

    private data class PendingResolution(
        val controllerGeneration: Long,
        val serviceKey: String,
        val serviceInfo: NsdServiceInfo,
        var listener: NsdManager.ResolveListener? = null,
    )

    companion object {
        const val MAX_PENDING_RESOLUTIONS = 64
    }

    private val controlExecutor = Executor { task -> dispatchToControl { task.run() } }
}

private fun Map<String, ByteArray>.decodeStrictUtf8(): Map<String, String>? {
    val values = linkedMapOf<String, String>()
    var wireSize = 0
    for ((key, value) in this) {
        wireSize += 1 + key.toByteArray(StandardCharsets.UTF_8).size + 1 + value.size
        if (wireSize > DiscoveryPresenceSchema.TXT_MAX_BYTES) return null
        val decoded = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        values[key] = decoded
    }
    return values
}

@Suppress("DEPRECATION", "NewApi")
private fun NsdServiceInfo.hostAddressesCompat(): List<InetAddress> {
    return if (supportsModernHostAddresses()) hostAddresses else listOfNotNull(host)
}

private fun supportsModernHostAddresses(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
}

private fun NsdServiceInfo.networkCompat(): Network? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) network else null
