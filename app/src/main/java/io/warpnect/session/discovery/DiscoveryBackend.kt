package io.warpnect.session.discovery

import io.warpnect.session.pairing.PairingTransport

/** Small platform adapter seam. Backends never create sessions or invoke pairing/authentication. */
interface DiscoveryBackend : AutoCloseable {
    val kind: DiscoveryRouteKind

    fun prepare(observer: DiscoveryBackendObserver): DiscoveryBackendCommandResult

    fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult

    fun stopAdvertising(controllerGeneration: Long, advertisementGeneration: Long?)

    fun startBrowsing(controllerGeneration: Long): DiscoveryBackendCommandResult

    fun stopBrowsing(controllerGeneration: Long)
}

data class DiscoveryBackendAdvertisingRequest(
    val controllerGeneration: Long,
    val advertisementGeneration: Long,
    val serviceInstanceName: String,
    val advertisement: DiscoveryAdvertisement,
)

data class DiscoveryBackendCommandResult(
    val accepted: Boolean,
    val error: DiscoveryError = DiscoveryError.None,
) {
    companion object {
        val Accepted: DiscoveryBackendCommandResult = DiscoveryBackendCommandResult(accepted = true)

        fun rejected(error: DiscoveryError): DiscoveryBackendCommandResult =
            DiscoveryBackendCommandResult(accepted = false, error = error)
    }
}

interface DiscoveryBackendObserver {
    fun onBackendDiagnostic(controllerGeneration: Long, kind: DiscoveryRouteKind, error: DiscoveryError)

    fun onBackendState(
        controllerGeneration: Long,
        kind: DiscoveryRouteKind,
        operation: DiscoveryBackendOperation,
        operationGeneration: Long?,
        state: DiscoveryBackendState,
        error: DiscoveryError = DiscoveryError.None,
    )

    fun onEffectiveLanServiceName(
        controllerGeneration: Long,
        advertisementGeneration: Long,
        effectiveServiceName: String,
    )

    fun onRouteObserved(controllerGeneration: Long, observation: DiscoveryRouteObservation)

    fun onRouteLost(controllerGeneration: Long, kind: DiscoveryRouteKind, backendRouteKey: String)

    fun onRouteCapacityDropped(controllerGeneration: Long, kind: DiscoveryRouteKind)

    fun onMalformedAdvertisement(
        controllerGeneration: Long,
        kind: DiscoveryRouteKind,
        error: DiscoveryAdvertisementError,
    )
}

interface DiscoveryContactEndpointLease : AutoCloseable {
    val port: Int
}

/**
 * Narrow RFC-005C handoff seam for the exact reserved discovery contact port. The lease remains
 * owned by discovery; borrowing starts no receiver until pairing explicitly attaches a transport.
 */
interface PairingBootstrapContactEndpointLease : DiscoveryContactEndpointLease {
    fun borrowPairingTransport(): PairingTransport?
}

fun interface DiscoveryContactEndpointLeaseFactory {
    fun acquire(): DiscoveryContactEndpointLeaseResult
}

data class DiscoveryContactEndpointLeaseResult(
    val lease: DiscoveryContactEndpointLease? = null,
    val error: DiscoveryError = DiscoveryError.None,
) {
    val isSuccess: Boolean
        get() = lease != null && error == DiscoveryError.None
}
