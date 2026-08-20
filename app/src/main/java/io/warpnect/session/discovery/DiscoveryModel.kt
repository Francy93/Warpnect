package io.warpnect.session.discovery

import io.warpnect.session.SessionManager
import io.warpnect.session.SessionRole
import io.warpnect.session.SessionSnapshot

object DiscoveryPresenceSchema {
    const val VERSION = 1
    const val SERVICE_TYPE = "_warpnect._udp"
    const val LAN_SERVICE_TYPE = "$SERVICE_TYPE."
    const val TXT_MAX_BYTES = 255
}

object DiscoveryBounds {
    const val DEFAULT_MAX_DISCOVERED_PRESENCES = 64
    const val HARD_MAX_DISCOVERED_PRESENCES = 64
    const val DEFAULT_PRESENCE_STALE_AFTER_MS = 30_000L
    const val DEFAULT_EXPIRY_CHECK_INTERVAL_MS = 5_000L
    const val MAX_ROUTES_PER_PRESENCE = 2
}

enum class DiscoveryMode {
    AdvertiseOnly,
    BrowseOnly,
    AdvertiseAndBrowse,
    ;

    val advertises: Boolean
        get() = this != BrowseOnly

    val browses: Boolean
        get() = this != AdvertiseOnly
}

enum class DiscoveryBackendPolicy {
    LanOnly,
    DirectOnly,
    DirectAndLan,
    ;

    val enablesLan: Boolean
        get() = this != DirectOnly

    val enablesDirect: Boolean
        get() = this != LanOnly
}

enum class DiscoveryAvailability {
    Available,
    Unavailable,
    AtCapacity,
    ;

    val advertisedValue: String
        get() = if (this == Available) "1" else "0"

    companion object {
        fun fromAdvertisedValue(value: String): DiscoveryAvailability? = when (value) {
            "1" -> Available
            "0" -> Unavailable
            else -> null
        }
    }
}

enum class DiscoveryVisibilityPolicy {
    HideWhenUnavailable,
    AdvertiseUnavailable,
}

enum class DiscoveryRouteKind {
    Lan,
    Direct,
}

enum class DiscoveryRouteStatus {
    Seen,
    Resolved,
    Lost,
    Expired,
    Failed,
}

enum class DiscoveryPresenceStatus {
    Usable,
    Unavailable,
    Conflicted,
}

enum class DiscoveryControllerState {
    Stopped,
    Preparing,
    Prepared,
    Starting,
    Running,
    RunningDegraded,
    Stopping,
    Error,
    Closed,
}

enum class DiscoveryBackendState {
    Disabled,
    Stopped,
    Preparing,
    Prepared,
    Starting,
    Running,
    Stopping,
    PermissionRequired,
    PermissionDenied,
    LocationServicesDisabled,
    Unsupported,
    Failed,
    Closed,
}

enum class DiscoveryBackendOperation {
    Advertising,
    Browsing,
}

enum class DiscoveryError {
    None,
    InvalidConfig,
    NotPrepared,
    Closed,
    BackendNotConfigured,
    RequiredBackendUnavailable,
    ContactEndpointUnavailable,
    InvalidAdvertisement,
    InvalidRoute,
    RouteNotFound,
    LanPermissionRequired,
    LanPermissionDenied,
    DirectPermissionRequired,
    DirectPermissionDenied,
    LocationServicesDisabled,
    DirectDiscoveryUnsupported,
    P2pDisabled,
    P2pChannelLost,
    RegistrationFailed,
    DiscoveryFailed,
    ResolutionFailed,
    OperationRejected,
}

data class DiscoveryConfig(
    val mode: DiscoveryMode = DiscoveryMode.BrowseOnly,
    val backendPolicy: DiscoveryBackendPolicy = DiscoveryBackendPolicy.DirectAndLan,
    val offeredRole: SessionRole = SessionRole.Host,
    val displayAlias: DiscoveryDisplayAlias? = DiscoveryDisplayAlias.DEFAULT_HOST,
    val visibilityPolicy: DiscoveryVisibilityPolicy = DiscoveryVisibilityPolicy.HideWhenUnavailable,
    val maxDiscoveredPresences: Int = DiscoveryBounds.DEFAULT_MAX_DISCOVERED_PRESENCES,
    val presenceStaleAfterMs: Long = DiscoveryBounds.DEFAULT_PRESENCE_STALE_AFTER_MS,
    val expiryCheckIntervalMs: Long = DiscoveryBounds.DEFAULT_EXPIRY_CHECK_INTERVAL_MS,
) {
    val advertise: Boolean
        get() = mode.advertises

    val browse: Boolean
        get() = mode.browses

    val enableLanDiscovery: Boolean
        get() = backendPolicy.enablesLan

    val enableDirectDiscovery: Boolean
        get() = backendPolicy.enablesDirect

    fun validate(): DiscoveryError = when {
        maxDiscoveredPresences !in 1..DiscoveryBounds.HARD_MAX_DISCOVERED_PRESENCES -> {
            DiscoveryError.InvalidConfig
        }
        presenceStaleAfterMs <= 0L || expiryCheckIntervalMs <= 0L ||
            expiryCheckIntervalMs > presenceStaleAfterMs -> DiscoveryError.InvalidConfig
        else -> DiscoveryError.None
    }
}

data class DiscoveryAdvertisement(
    val schemaVersion: Int = DiscoveryPresenceSchema.VERSION,
    val presenceId: DiscoveryPresenceId,
    val offeredRole: SessionRole,
    val availability: DiscoveryAvailability,
    val displayAlias: DiscoveryDisplayAlias? = null,
    val bootstrapPort: Int? = null,
)

data class DiscoveryAddressCandidate(
    val hostAddress: String,
)

sealed interface DiscoveryRouteDescriptor {
    val kind: DiscoveryRouteKind

    data class Lan(
        val addressCandidates: List<DiscoveryAddressCandidate>,
        val port: Int,
        val networkLocator: DiscoveryOpaqueRouteLocator? = null,
    ) : DiscoveryRouteDescriptor {
        override val kind: DiscoveryRouteKind = DiscoveryRouteKind.Lan
    }

    data class Direct(
        val port: Int,
        val peerLocator: DiscoveryOpaqueRouteLocator,
    ) : DiscoveryRouteDescriptor {
        override val kind: DiscoveryRouteKind = DiscoveryRouteKind.Direct
    }
}

/** A route observation is local, untrusted discovery metadata rather than a session path. */
data class DiscoveryRouteObservation(
    val backendRouteKey: String,
    val kind: DiscoveryRouteKind,
    val advertisement: DiscoveryAdvertisement,
    val descriptor: DiscoveryRouteDescriptor,
)

data class DiscoveredPresence(
    val presenceId: DiscoveryPresenceId,
    val displayAlias: DiscoveryDisplayAlias?,
    val offeredRole: SessionRole,
    val availability: DiscoveryAvailability,
    val discoverySchemaVersion: Int,
    val firstSeenMonotonicMs: Long,
    val lastSeenMonotonicMs: Long,
    val availablePathKinds: List<DiscoveryRouteKind>,
    val status: DiscoveryPresenceStatus,
)

data class DiscoveryBackendSnapshot(
    val kind: DiscoveryRouteKind,
    val state: DiscoveryBackendState,
    val lastError: DiscoveryError,
)

data class DiscoverySnapshot(
    val state: DiscoveryControllerState,
    val advertising: Boolean,
    val browsing: Boolean,
    val advertisingRequested: Boolean,
    val browsingRequested: Boolean,
    val ownPresenceId: DiscoveryPresenceId?,
    val effectiveLanServiceName: String?,
    val backendPolicy: DiscoveryBackendPolicy,
    val lanBackend: DiscoveryBackendSnapshot,
    val directBackend: DiscoveryBackendSnapshot,
    val candidateCount: Int,
    val availableCount: Int,
    val unavailableCount: Int,
    val conflictedCount: Int,
    val lanRouteCount: Int,
    val directRouteCount: Int,
    val selfDiscoveryDrops: Long,
    val malformedAdvertisements: Long,
    val unsupportedDiscoveryVersions: Long,
    val conflictingAdvertisements: Long,
    val capacityDrops: Long,
    val expiredRoutes: Long,
    val registrationFailures: Long,
    val discoveryFailures: Long,
    val resolutionFailures: Long,
    val permissionFailures: Long,
    val controllerGeneration: Long?,
    val advertisementGeneration: Long?,
    val candidates: List<DiscoveredPresence>,
    val lastError: DiscoveryError,
)

data class DiscoveryOperationResult(
    val error: DiscoveryError,
    val snapshot: DiscoverySnapshot,
) {
    val isSuccess: Boolean
        get() = error == DiscoveryError.None
}

data class DiscoveryRouteLookupResult(
    val error: DiscoveryError,
    val presenceId: DiscoveryPresenceId? = null,
    val descriptor: DiscoveryRouteDescriptor? = null,
) {
    val isSuccess: Boolean
        get() = error == DiscoveryError.None
}

fun interface DiscoveryMonotonicClock {
    fun nowMs(): Long
}

fun interface HostAvailabilityProvider {
    fun availability(): DiscoveryAvailability
}

/** Read-only RFC-005A capacity bridge; it never mutates SessionManager or creates sessions. */
class SessionManagerHostAvailabilityProvider(
    private val sessionManager: SessionManager,
) : HostAvailabilityProvider {
    override fun availability(): DiscoveryAvailability {
        val snapshot = sessionManager.snapshot()
        // Admission, lifecycle, and recovery ownership all reserve the same bounded Host slot.
        // Advertising a free slot while one of those records exists would race RFC-005D/005H.
        val occupiedHostSlots = snapshot.sessions.count(SessionSnapshot::isLiveHostSession) +
            snapshot.authenticatedReservationCount +
            snapshot.lifecycleAdmissionCount +
            snapshot.recoveryLeaseCount
        return if (occupiedHostSlots < snapshot.policy.maxConcurrentClients) {
            DiscoveryAvailability.Available
        } else {
            DiscoveryAvailability.AtCapacity
        }
    }
}

private fun SessionSnapshot.isLiveHostSession(): Boolean = localRole == SessionRole.Host && state !in setOf(
    io.warpnect.session.SessionState.Stopped,
    io.warpnect.session.SessionState.Failed,
    io.warpnect.session.SessionState.Closed,
)
