package io.warpnect.session.discovery

enum class DiscoveryCacheChange {
    Accepted,
    SelfSuppressed,
    Conflicted,
    CapacityDropped,
    InvalidRoute,
}

data class DiscoveryCacheObserveResult(
    val change: DiscoveryCacheChange,
    val routeToken: DiscoveryRouteToken? = null,
)

data class DiscoveryCacheExpiryResult(
    val expiredRoutes: Int,
)

data class DiscoveryPresenceCacheSnapshot(
    val candidates: List<DiscoveredPresence>,
    val lanRouteCount: Int,
    val directRouteCount: Int,
)

/**
 * Bounded, no-wait cache for untrusted discovery observations. It stores one route per route kind
 * per presence and deliberately never queues future work or treats a route locator as identity.
 */
class DiscoveryPresenceCache(
    private val maxPresences: Int,
    private val staleAfterMs: Long,
) {
    private val presences = LinkedHashMap<DiscoveryPresenceId, MutablePresence>()
    private val routesByBackendKey = HashMap<BackendRouteKey, RouteOwner>()
    private var controllerGeneration: Long = 0L
    private var nextRouteTokenValue: Int = 1

    @Synchronized
    fun reset(controllerGeneration: Long) {
        presences.clear()
        routesByBackendKey.clear()
        this.controllerGeneration = controllerGeneration
        nextRouteTokenValue = 1
    }

    @Synchronized
    fun clear() {
        presences.clear()
        routesByBackendKey.clear()
    }

    @Synchronized
    fun observe(
        observation: DiscoveryRouteObservation,
        ownPresenceId: DiscoveryPresenceId?,
        nowMonotonicMs: Long,
    ): DiscoveryCacheObserveResult {
        if (!observation.isValid()) return DiscoveryCacheObserveResult(DiscoveryCacheChange.InvalidRoute)
        if (observation.advertisement.presenceId == ownPresenceId) {
            return DiscoveryCacheObserveResult(DiscoveryCacheChange.SelfSuppressed)
        }

        val backendKey = BackendRouteKey(observation.kind, observation.backendRouteKey)
        val previousOwner = routesByBackendKey[backendKey]
        if (previousOwner != null && previousOwner.presenceId != observation.advertisement.presenceId) {
            removeRouteLocked(previousOwner.presenceId, previousOwner.kind, backendKey)
        }

        var presence = presences[observation.advertisement.presenceId]
        if (presence == null) {
            if (presences.size >= maxPresences) {
                return DiscoveryCacheObserveResult(DiscoveryCacheChange.CapacityDropped)
            }
            presence = MutablePresence.from(observation.advertisement, nowMonotonicMs)
            presences[observation.advertisement.presenceId] = presence
        } else if (!presence.isCompatibleWith(observation.advertisement)) {
            presence.status = DiscoveryPresenceStatus.Conflicted
            return DiscoveryCacheObserveResult(DiscoveryCacheChange.Conflicted)
        }

        val previousRoute = presence.routes[observation.kind]
        if (previousRoute != null && previousRoute.backendKey != backendKey) {
            routesByBackendKey.remove(previousRoute.backendKey)
        }

        val routeToken = previousRoute?.token ?: DiscoveryRouteToken(
            controllerGeneration = controllerGeneration,
            value = nextRouteTokenValue++,
        )
        val route = MutableRoute(
            backendKey = backendKey,
            token = routeToken,
            descriptor = observation.descriptor,
            lastSeenMonotonicMs = nowMonotonicMs,
        )
        presence.routes[observation.kind] = route
        routesByBackendKey[backendKey] = RouteOwner(presence.id, observation.kind)
        presence.updateFrom(observation.advertisement, nowMonotonicMs)
        return DiscoveryCacheObserveResult(DiscoveryCacheChange.Accepted, routeToken)
    }

    @Synchronized
    fun markRouteLost(kind: DiscoveryRouteKind, backendRouteKey: String): Boolean {
        val backendKey = BackendRouteKey(kind, backendRouteKey)
        val owner = routesByBackendKey[backendKey] ?: return false
        return removeRouteLocked(owner.presenceId, owner.kind, backendKey)
    }

    @Synchronized
    fun expire(nowMonotonicMs: Long): DiscoveryCacheExpiryResult {
        var expired = 0
        presences.values.toList().forEach { presence ->
            presence.routes.values.toList().forEach { route ->
                if (nowMonotonicMs >= route.lastSeenMonotonicMs &&
                    nowMonotonicMs - route.lastSeenMonotonicMs >= staleAfterMs
                ) {
                    if (removeRouteLocked(presence.id, route.descriptor.kind, route.backendKey)) {
                        expired += 1
                    }
                }
            }
        }
        return DiscoveryCacheExpiryResult(expired)
    }

    @Synchronized
    fun resolve(token: DiscoveryRouteToken, activeControllerGeneration: Long?): DiscoveryRouteLookupResult {
        if (activeControllerGeneration == null || token.controllerGeneration != activeControllerGeneration) {
            return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
        }
        presences.values.forEach { presence ->
            presence.routes.values.firstOrNull { it.token == token }?.let { route ->
                return DiscoveryRouteLookupResult(
                    error = DiscoveryError.None,
                    presenceId = presence.id,
                    descriptor = route.descriptor,
                )
            }
        }
        return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
    }

    @Synchronized
    fun resolve(
        presenceId: DiscoveryPresenceId,
        kind: DiscoveryRouteKind,
        activeControllerGeneration: Long?,
    ): DiscoveryRouteLookupResult {
        if (activeControllerGeneration == null) return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
        val presence = presences[presenceId] ?: return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
        val route = presence.routes[kind] ?: return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
        if (route.token.controllerGeneration != activeControllerGeneration) {
            return DiscoveryRouteLookupResult(DiscoveryError.RouteNotFound)
        }
        return DiscoveryRouteLookupResult(DiscoveryError.None, presence.id, route.descriptor)
    }

    @Synchronized
    fun snapshot(): DiscoveryPresenceCacheSnapshot {
        var lanRoutes = 0
        var directRoutes = 0
        val candidates = presences.values.map { presence ->
            presence.routes.keys.forEach { kind ->
                when (kind) {
                    DiscoveryRouteKind.Lan -> lanRoutes += 1
                    DiscoveryRouteKind.Direct -> directRoutes += 1
                }
            }
            DiscoveredPresence(
                presenceId = presence.id,
                displayAlias = presence.displayAlias,
                offeredRole = presence.offeredRole,
                availability = presence.availability,
                discoverySchemaVersion = presence.schemaVersion,
                firstSeenMonotonicMs = presence.firstSeenMonotonicMs,
                lastSeenMonotonicMs = presence.lastSeenMonotonicMs,
                availablePathKinds = DiscoveryRouteKind.entries.filter(presence.routes::containsKey),
                status = presence.status,
            )
        }
        return DiscoveryPresenceCacheSnapshot(candidates, lanRoutes, directRoutes)
    }

    private fun removeRouteLocked(
        presenceId: DiscoveryPresenceId,
        kind: DiscoveryRouteKind,
        backendKey: BackendRouteKey,
    ): Boolean {
        val presence = presences[presenceId] ?: return false
        val route = presence.routes[kind] ?: return false
        if (route.backendKey != backendKey) return false
        presence.routes.remove(kind)
        routesByBackendKey.remove(backendKey)
        if (presence.routes.isEmpty()) presences.remove(presenceId)
        return true
    }

    private data class BackendRouteKey(
        val kind: DiscoveryRouteKind,
        val value: String,
    )

    private data class RouteOwner(
        val presenceId: DiscoveryPresenceId,
        val kind: DiscoveryRouteKind,
    )

    private data class MutableRoute(
        val backendKey: BackendRouteKey,
        val token: DiscoveryRouteToken,
        val descriptor: DiscoveryRouteDescriptor,
        val lastSeenMonotonicMs: Long,
    )

    private class MutablePresence(
        val id: DiscoveryPresenceId,
        var schemaVersion: Int,
        var displayAlias: DiscoveryDisplayAlias?,
        var offeredRole: io.warpnect.session.SessionRole,
        var availability: DiscoveryAvailability,
        val firstSeenMonotonicMs: Long,
        var lastSeenMonotonicMs: Long,
        var status: DiscoveryPresenceStatus,
        val routes: MutableMap<DiscoveryRouteKind, MutableRoute> = LinkedHashMap(),
    ) {
        fun isCompatibleWith(advertisement: DiscoveryAdvertisement): Boolean =
            schemaVersion == advertisement.schemaVersion &&
                offeredRole == advertisement.offeredRole &&
                availability == advertisement.availability

        fun updateFrom(advertisement: DiscoveryAdvertisement, nowMonotonicMs: Long) {
            displayAlias = advertisement.displayAlias
            availability = advertisement.availability
            lastSeenMonotonicMs = nowMonotonicMs
            if (status != DiscoveryPresenceStatus.Conflicted) {
                status = when (availability) {
                    DiscoveryAvailability.Available -> DiscoveryPresenceStatus.Usable
                    DiscoveryAvailability.Unavailable,
                    DiscoveryAvailability.AtCapacity,
                    -> DiscoveryPresenceStatus.Unavailable
                }
            }
        }

        companion object {
            fun from(advertisement: DiscoveryAdvertisement, nowMonotonicMs: Long): MutablePresence = MutablePresence(
                id = advertisement.presenceId,
                schemaVersion = advertisement.schemaVersion,
                displayAlias = advertisement.displayAlias,
                offeredRole = advertisement.offeredRole,
                availability = advertisement.availability,
                firstSeenMonotonicMs = nowMonotonicMs,
                lastSeenMonotonicMs = nowMonotonicMs,
                status = when (advertisement.availability) {
                    DiscoveryAvailability.Available -> DiscoveryPresenceStatus.Usable
                    DiscoveryAvailability.Unavailable,
                    DiscoveryAvailability.AtCapacity,
                    -> DiscoveryPresenceStatus.Unavailable
                },
            )
        }
    }
}

private fun DiscoveryRouteObservation.isValid(): Boolean {
    if (backendRouteKey.isBlank() || kind != descriptor.kind) return false
    if (advertisement.schemaVersion != DiscoveryPresenceSchema.VERSION) return false
    return when (descriptor) {
        is DiscoveryRouteDescriptor.Lan -> descriptor.addressCandidates.isNotEmpty() && isValidPort(descriptor.port)
        is DiscoveryRouteDescriptor.Direct -> isValidPort(descriptor.port) && descriptor.peerLocator.value.isNotBlank()
    }
}
