package io.warpnect.session.discovery

import io.warpnect.session.handshake.SessionHandshakeTransport
import io.warpnect.session.pairing.PairingTransport

/**
 * Bounded discovery lifecycle. It owns no pairing, trust, Session creation, sockets readers, or
 * Android framework types; platform adapters provide the actual DNS-SD operations.
 */
interface LocalDiscoveryController : AutoCloseable {
    fun prepare(): DiscoveryOperationResult

    fun start(): DiscoveryOperationResult

    fun stop(): DiscoveryOperationResult

    fun startAdvertising(): DiscoveryOperationResult

    fun stopAdvertising(): DiscoveryOperationResult

    fun startBrowsing(): DiscoveryOperationResult

    fun stopBrowsing(): DiscoveryOperationResult

    fun updateAvailability(availability: DiscoveryAvailability): DiscoveryOperationResult

    fun refreshAvailability(): DiscoveryOperationResult

    fun expireStaleRoutes(): DiscoveryOperationResult

    fun resolveRoute(token: DiscoveryRouteToken): DiscoveryRouteLookupResult

    /** Resolves a current candidate by ephemeral PresenceId; it never creates a Session or trust binding. */
    fun resolveRoute(presenceId: DiscoveryPresenceId, kind: DiscoveryRouteKind): DiscoveryRouteLookupResult

    /** Borrows the exact advertised contact endpoint only when a platform lease supports pairing. */
    fun borrowPairingTransport(): PairingTransport?

    fun borrowSessionHandshakeTransport(): SessionHandshakeTransport?

    /** Current local RFC-005B advertising epoch only; it is not a DeviceId or trust assertion. */
    fun currentAdvertisingPresenceId(): DiscoveryPresenceId?

    /** Bounded, ephemeral RFC-005B observations for an application chooser; never peer identity. */
    fun discoveredPresences(): List<DiscoveredPresence> = emptyList()

    fun snapshot(): DiscoverySnapshot
}

class DefaultLocalDiscoveryController(
    private val config: DiscoveryConfig,
    backends: Collection<DiscoveryBackend>,
    private val contactEndpointLeaseFactory: DiscoveryContactEndpointLeaseFactory,
    private val clock: DiscoveryMonotonicClock,
    private val presenceIdGenerator: DiscoveryPresenceIdGenerator = SecureRandomDiscoveryPresenceIdGenerator(),
    private val availabilityProvider: HostAvailabilityProvider? = null,
) : LocalDiscoveryController, DiscoveryBackendObserver {
    private val backendsByKind = backends.associateBy(DiscoveryBackend::kind)
    private val cache = DiscoveryPresenceCache(
        maxPresences = config.maxDiscoveredPresences.coerceIn(
            1,
            DiscoveryBounds.HARD_MAX_DISCOVERED_PRESENCES,
        ),
        staleAfterMs = config.presenceStaleAfterMs.coerceAtLeast(1L),
    )
    private val backendStatus = DiscoveryRouteKind.entries.associateWith { kind ->
        MutableBackendStatus(
            state = if (kind in selectedKinds()) DiscoveryBackendState.Stopped else DiscoveryBackendState.Disabled,
        )
    }.toMutableMap()
    private val advertisingBackends = linkedSetOf<DiscoveryRouteKind>()
    private val browsingBackends = linkedSetOf<DiscoveryRouteKind>()
    private val counters = DiscoveryCounters()

    private var state = DiscoveryControllerState.Stopped
    private var prepared = false
    private var closed = false
    private var activeControllerGeneration: Long? = null
    private var nextControllerGeneration = 1L
    private var activeAdvertisementGeneration: Long? = null
    private var nextAdvertisementGeneration = 1L
    private var ownPresenceId: DiscoveryPresenceId? = null
    private var contactEndpointLease: DiscoveryContactEndpointLease? = null
    private var effectiveLanServiceName: String? = null
    private var availability = DiscoveryAvailability.Available
    private var advertisingRequested = false
    private var browsingRequested = false
    private var advertisingPublished = false
    private var lastError = DiscoveryError.None

    @Synchronized
    override fun prepare(): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        val configError = config.validate()
        if (configError != DiscoveryError.None) {
            state = DiscoveryControllerState.Error
            recordErrorLocked(configError)
            return resultLocked(configError)
        }
        if (prepared) return resultLocked(DiscoveryError.None)

        state = DiscoveryControllerState.Preparing
        var acceptedCount = 0
        selectedKinds().forEach { kind ->
            val backend = backendsByKind[kind]
            if (backend == null) {
                setBackendFailureLocked(kind, DiscoveryError.BackendNotConfigured)
                return@forEach
            }
            backendStatus.getValue(kind).state = DiscoveryBackendState.Preparing
            val command = backend.prepare(this)
            if (command.accepted) {
                if (!backendStatus.getValue(kind).state.isFailureState()) {
                    backendStatus.getValue(kind).state = DiscoveryBackendState.Prepared
                    backendStatus.getValue(kind).lastError = DiscoveryError.None
                }
                acceptedCount += 1
            } else {
                setBackendFailureLocked(kind, command.error.orOperationRejected())
            }
        }

        val error = readinessErrorLocked(acceptedCount)
        if (error == DiscoveryError.None) {
            prepared = true
            state = DiscoveryControllerState.Prepared
        } else {
            state = DiscoveryControllerState.Error
            recordErrorLocked(error)
        }
        return resultLocked(error)
    }

    @Synchronized
    override fun start(): DiscoveryOperationResult {
        val preparationError = ensurePreparedLocked()
        if (preparationError != DiscoveryError.None) return resultLocked(preparationError)
        activateGenerationLocked()
        state = DiscoveryControllerState.Starting
        advertisingRequested = config.advertise
        browsingRequested = config.browse

        var error = DiscoveryError.None
        if (advertisingRequested) error = publishAdvertisementLocked()
        if (error == DiscoveryError.None && browsingRequested) error = beginBrowsingLocked()
        reconcileStateLocked()
        return resultLocked(error)
    }

    @Synchronized
    override fun stop(): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        state = DiscoveryControllerState.Stopping
        stopAllLocked()
        return resultLocked(DiscoveryError.None)
    }

    @Synchronized
    override fun startAdvertising(): DiscoveryOperationResult {
        val preparationError = ensurePreparedLocked()
        if (preparationError != DiscoveryError.None) return resultLocked(preparationError)
        activateGenerationLocked()
        advertisingRequested = true
        val error = publishAdvertisementLocked()
        reconcileStateLocked()
        return resultLocked(error)
    }

    @Synchronized
    override fun stopAdvertising(): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        stopAdvertisingLocked(releaseEpoch = true)
        reconcileStateLocked()
        return resultLocked(DiscoveryError.None)
    }

    @Synchronized
    override fun startBrowsing(): DiscoveryOperationResult {
        val preparationError = ensurePreparedLocked()
        if (preparationError != DiscoveryError.None) return resultLocked(preparationError)
        activateGenerationLocked()
        browsingRequested = true
        val error = beginBrowsingLocked()
        reconcileStateLocked()
        return resultLocked(error)
    }

    @Synchronized
    override fun stopBrowsing(): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        stopBrowsingLocked()
        reconcileStateLocked()
        return resultLocked(DiscoveryError.None)
    }

    @Synchronized
    override fun updateAvailability(availability: DiscoveryAvailability): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        this.availability = availability
        if (!advertisingRequested || activeControllerGeneration == null) return resultLocked(DiscoveryError.None)

        val error = if (isAdvertisementVisibleLocked()) {
            if (advertisingPublished) stopAdvertisingBackendsLocked()
            publishAdvertisementLocked()
        } else {
            stopAdvertisingBackendsLocked()
            DiscoveryError.None
        }
        reconcileStateLocked()
        return resultLocked(error)
    }

    @Synchronized
    override fun refreshAvailability(): DiscoveryOperationResult {
        val provider = availabilityProvider ?: return resultLocked(DiscoveryError.None)
        return updateAvailability(provider.availability())
    }

    @Synchronized
    override fun expireStaleRoutes(): DiscoveryOperationResult {
        if (closed) return resultLocked(DiscoveryError.Closed)
        if (activeControllerGeneration != null && browsingRequested) {
            counters.expiredRoutes += cache.expire(clock.nowMs()).expiredRoutes
        }
        return resultLocked(DiscoveryError.None)
    }

    @Synchronized
    override fun resolveRoute(token: DiscoveryRouteToken): DiscoveryRouteLookupResult =
        cache.resolve(token, activeControllerGeneration)

    @Synchronized
    override fun resolveRoute(presenceId: DiscoveryPresenceId, kind: DiscoveryRouteKind): DiscoveryRouteLookupResult =
        cache.resolve(presenceId, kind, activeControllerGeneration)

    @Synchronized
    override fun borrowPairingTransport(): PairingTransport? =
        (contactEndpointLease as? PairingBootstrapContactEndpointLease)?.borrowPairingTransport()

    @Synchronized
    override fun borrowSessionHandshakeTransport(): SessionHandshakeTransport? =
        (contactEndpointLease as? SessionHandshakeBootstrapContactEndpointLease)?.borrowSessionHandshakeTransport()

    @Synchronized
    override fun currentAdvertisingPresenceId(): DiscoveryPresenceId? = ownPresenceId

    @Synchronized
    override fun discoveredPresences(): List<DiscoveredPresence> = cache.snapshot().candidates

    @Synchronized
    override fun snapshot(): DiscoverySnapshot = snapshotLocked()

    @Synchronized
    override fun close() {
        if (closed) return
        state = DiscoveryControllerState.Stopping
        stopAllLocked()
        closed = true
        state = DiscoveryControllerState.Closed
        backendStatus.values.forEach { it.state = DiscoveryBackendState.Closed }
        backendsByKind.values.forEach(DiscoveryBackend::close)
    }

    @Synchronized
    override fun onBackendDiagnostic(controllerGeneration: Long, kind: DiscoveryRouteKind, error: DiscoveryError) {
        if (closed || controllerGeneration != activeControllerGeneration) return
        recordErrorLocked(error)
    }

    @Synchronized
    override fun onBackendState(
        controllerGeneration: Long,
        kind: DiscoveryRouteKind,
        operation: DiscoveryBackendOperation,
        operationGeneration: Long?,
        state: DiscoveryBackendState,
        error: DiscoveryError,
    ) {
        if (!acceptsCallbackLocked(controllerGeneration, operation, operationGeneration)) return
        val status = backendStatus.getValue(kind)
        status.state = state
        status.lastError = error
        if (error != DiscoveryError.None) {
            recordErrorLocked(error)
            if (operation == DiscoveryBackendOperation.Advertising) {
                advertisingBackends.remove(kind)
                advertisingPublished = advertisingBackends.isNotEmpty()
            } else {
                browsingBackends.remove(kind)
            }
        }
        reconcileStateLocked()
    }

    @Synchronized
    override fun onEffectiveLanServiceName(
        controllerGeneration: Long,
        advertisementGeneration: Long,
        effectiveServiceName: String,
    ) {
        if (!acceptsCallbackLocked(
                controllerGeneration,
                DiscoveryBackendOperation.Advertising,
                advertisementGeneration,
            )
        ) {
            return
        }
        effectiveLanServiceName = effectiveServiceName
    }

    @Synchronized
    override fun onRouteObserved(controllerGeneration: Long, observation: DiscoveryRouteObservation) {
        if (closed || controllerGeneration != activeControllerGeneration || !browsingRequested) return
        when (cache.observe(observation, ownPresenceId, clock.nowMs()).change) {
            DiscoveryCacheChange.Accepted -> Unit
            DiscoveryCacheChange.SelfSuppressed -> counters.selfDiscoveryDrops += 1
            DiscoveryCacheChange.Conflicted -> counters.conflictingAdvertisements += 1
            DiscoveryCacheChange.CapacityDropped -> counters.capacityDrops += 1
            DiscoveryCacheChange.InvalidRoute -> counters.malformedAdvertisements += 1
        }
    }

    @Synchronized
    override fun onRouteLost(controllerGeneration: Long, kind: DiscoveryRouteKind, backendRouteKey: String) {
        if (closed || controllerGeneration != activeControllerGeneration || !browsingRequested) return
        cache.markRouteLost(kind, backendRouteKey)
    }

    @Synchronized
    override fun onRouteCapacityDropped(controllerGeneration: Long, kind: DiscoveryRouteKind) {
        if (closed || controllerGeneration != activeControllerGeneration || !browsingRequested) return
        counters.capacityDrops += 1
    }

    @Synchronized
    override fun onMalformedAdvertisement(
        controllerGeneration: Long,
        kind: DiscoveryRouteKind,
        error: DiscoveryAdvertisementError,
    ) {
        if (closed || controllerGeneration != activeControllerGeneration || !browsingRequested) return
        if (error == DiscoveryAdvertisementError.UnsupportedSchemaVersion) {
            counters.unsupportedDiscoveryVersions += 1
        } else {
            counters.malformedAdvertisements += 1
        }
    }

    private fun ensurePreparedLocked(): DiscoveryError {
        if (closed) return DiscoveryError.Closed
        return if (prepared) DiscoveryError.None else prepare().error
    }

    private fun activateGenerationLocked() {
        if (activeControllerGeneration != null) return
        activeControllerGeneration = nextControllerGeneration++
        activeAdvertisementGeneration = null
        cache.reset(requireNotNull(activeControllerGeneration))
        counters.reset()
        selectedKinds().forEach { kind ->
            backendStatus.getValue(kind).apply {
                if (!state.isFailureState()) {
                    state = DiscoveryBackendState.Prepared
                    lastError = DiscoveryError.None
                }
            }
        }
    }

    private fun publishAdvertisementLocked(): DiscoveryError {
        if (!advertisingRequested || !isAdvertisementVisibleLocked()) return DiscoveryError.None
        val controllerGeneration = activeControllerGeneration ?: return DiscoveryError.NotPrepared
        val leaseError = ensureContactEndpointLocked()
        if (leaseError != DiscoveryError.None) return leaseError

        val advertisementGeneration = nextAdvertisementGeneration++
        activeAdvertisementGeneration = advertisementGeneration
        effectiveLanServiceName = null
        val advertisement = DiscoveryAdvertisement(
            presenceId = requireNotNull(ownPresenceId),
            offeredRole = config.offeredRole,
            availability = availability,
            displayAlias = config.displayAlias,
            bootstrapPort = requireNotNull(contactEndpointLease).port,
        )
        val request = DiscoveryBackendAdvertisingRequest(
            controllerGeneration = controllerGeneration,
            advertisementGeneration = advertisementGeneration,
            serviceInstanceName = DiscoveryAdvertisementCodec.serviceInstanceName(advertisement.presenceId),
            advertisement = advertisement,
        )

        advertisingBackends.clear()
        selectedKinds().forEach { kind ->
            val backend = backendsByKind[kind]
            if (backend == null || backendStatus.getValue(kind).state.isFailureState()) return@forEach
            val command = backend.startAdvertising(request)
            if (command.accepted) {
                advertisingBackends += kind
                backendStatus.getValue(kind).apply {
                    state = DiscoveryBackendState.Starting
                    lastError = DiscoveryError.None
                }
            } else {
                setBackendFailureLocked(kind, command.error.orOperationRejected())
            }
        }
        advertisingPublished = advertisingBackends.isNotEmpty()
        return if (advertisingPublished || config.backendPolicy == DiscoveryBackendPolicy.DirectAndLan &&
            selectedKinds().any { !backendStatus.getValue(it).state.isFailureState() }
        ) {
            DiscoveryError.None
        } else {
            val error = DiscoveryError.RequiredBackendUnavailable
            recordErrorLocked(error)
            error
        }
    }

    private fun beginBrowsingLocked(): DiscoveryError {
        if (!browsingRequested) return DiscoveryError.None
        val controllerGeneration = activeControllerGeneration ?: return DiscoveryError.NotPrepared
        browsingBackends.clear()
        selectedKinds().forEach { kind ->
            val backend = backendsByKind[kind]
            if (backend == null || backendStatus.getValue(kind).state.isFailureState()) return@forEach
            val command = backend.startBrowsing(controllerGeneration)
            if (command.accepted) {
                browsingBackends += kind
                backendStatus.getValue(kind).apply {
                    state = DiscoveryBackendState.Starting
                    lastError = DiscoveryError.None
                }
            } else {
                setBackendFailureLocked(kind, command.error.orOperationRejected())
            }
        }
        return if (browsingBackends.isNotEmpty() || config.backendPolicy == DiscoveryBackendPolicy.DirectAndLan &&
            selectedKinds().any { !backendStatus.getValue(it).state.isFailureState() }
        ) {
            DiscoveryError.None
        } else {
            val error = DiscoveryError.RequiredBackendUnavailable
            recordErrorLocked(error)
            error
        }
    }

    private fun ensureContactEndpointLocked(): DiscoveryError {
        if (contactEndpointLease != null) return DiscoveryError.None
        if (ownPresenceId == null) ownPresenceId = presenceIdGenerator.next()
        val result = contactEndpointLeaseFactory.acquire()
        val lease = result.lease
        if (lease == null || !result.isSuccess || !isValidPort(lease.port)) {
            val error = result.error.takeUnless { it == DiscoveryError.None }
                ?: DiscoveryError.ContactEndpointUnavailable
            recordErrorLocked(error)
            lease?.close()
            return error
        }
        contactEndpointLease = lease
        return DiscoveryError.None
    }

    private fun stopAdvertisingLocked(releaseEpoch: Boolean) {
        stopAdvertisingBackendsLocked()
        advertisingRequested = false
        if (releaseEpoch) {
            activeAdvertisementGeneration = null
            ownPresenceId = null
            effectiveLanServiceName = null
            contactEndpointLease?.close()
            contactEndpointLease = null
        }
    }

    private fun stopAdvertisingBackendsLocked() {
        val controllerGeneration = activeControllerGeneration
        if (controllerGeneration != null) {
            advertisingBackends.forEach { kind ->
                backendsByKind[kind]?.stopAdvertising(controllerGeneration, activeAdvertisementGeneration)
                backendStatus.getValue(kind).state = DiscoveryBackendState.Stopping
            }
        }
        advertisingBackends.clear()
        advertisingPublished = false
        effectiveLanServiceName = null
    }

    private fun stopBrowsingLocked() {
        val controllerGeneration = activeControllerGeneration
        if (controllerGeneration != null) {
            browsingBackends.forEach { kind ->
                backendsByKind[kind]?.stopBrowsing(controllerGeneration)
                backendStatus.getValue(kind).state = DiscoveryBackendState.Stopping
            }
        }
        browsingBackends.clear()
        browsingRequested = false
        cache.clear()
    }

    private fun stopAllLocked() {
        stopBrowsingLocked()
        stopAdvertisingLocked(releaseEpoch = true)
        activeControllerGeneration = null
        prepared = false
        cache.clear()
        backendStatus.values.forEach { status ->
            if (status.state != DiscoveryBackendState.Disabled) status.state = DiscoveryBackendState.Stopped
        }
        state = DiscoveryControllerState.Stopped
    }

    private fun isAdvertisementVisibleLocked(): Boolean = availability == DiscoveryAvailability.Available ||
        config.visibilityPolicy == DiscoveryVisibilityPolicy.AdvertiseUnavailable

    private fun acceptsCallbackLocked(
        controllerGeneration: Long,
        operation: DiscoveryBackendOperation,
        operationGeneration: Long?,
    ): Boolean {
        if (closed || controllerGeneration != activeControllerGeneration) return false
        return operation != DiscoveryBackendOperation.Advertising ||
            operationGeneration == activeAdvertisementGeneration
    }

    private fun readinessErrorLocked(acceptedCount: Int): DiscoveryError = when {
        acceptedCount == selectedKinds().size -> DiscoveryError.None
        config.backendPolicy == DiscoveryBackendPolicy.DirectAndLan && acceptedCount > 0 -> DiscoveryError.None
        else -> DiscoveryError.RequiredBackendUnavailable
    }

    private fun reconcileStateLocked() {
        if (closed || state == DiscoveryControllerState.Stopping) return
        if (activeControllerGeneration == null) {
            state = if (prepared) DiscoveryControllerState.Prepared else DiscoveryControllerState.Stopped
            return
        }
        val selectedStates = selectedKinds().map { backendStatus.getValue(it).state }
        val failed = selectedStates.count(DiscoveryBackendState::isFailureState)
        val usable = selectedStates.size - failed
        state = when {
            usable == 0 && (advertisingRequested || browsingRequested) -> DiscoveryControllerState.Error
            failed > 0 && usable > 0 -> DiscoveryControllerState.RunningDegraded
            else -> DiscoveryControllerState.Running
        }
    }

    private fun setBackendFailureLocked(kind: DiscoveryRouteKind, error: DiscoveryError) {
        backendStatus.getValue(kind).apply {
            state = error.toBackendState()
            lastError = error
        }
        recordErrorLocked(error)
    }

    private fun recordErrorLocked(error: DiscoveryError) {
        if (error == DiscoveryError.None) return
        lastError = error
        when (error) {
            DiscoveryError.RegistrationFailed -> counters.registrationFailures += 1
            DiscoveryError.DiscoveryFailed,
            DiscoveryError.P2pChannelLost,
            DiscoveryError.P2pDisabled,
            -> counters.discoveryFailures += 1
            DiscoveryError.ResolutionFailed -> counters.resolutionFailures += 1
            DiscoveryError.LanPermissionRequired,
            DiscoveryError.LanPermissionDenied,
            DiscoveryError.DirectPermissionRequired,
            DiscoveryError.DirectPermissionDenied,
            -> counters.permissionFailures += 1
            else -> Unit
        }
    }

    private fun resultLocked(error: DiscoveryError): DiscoveryOperationResult =
        DiscoveryOperationResult(error, snapshotLocked())

    private fun snapshotLocked(): DiscoverySnapshot {
        val cacheSnapshot = cache.snapshot()
        val available = cacheSnapshot.candidates.count {
            it.status == DiscoveryPresenceStatus.Usable && it.availability == DiscoveryAvailability.Available
        }
        val conflicted = cacheSnapshot.candidates.count { it.status == DiscoveryPresenceStatus.Conflicted }
        return DiscoverySnapshot(
            state = state,
            advertising = advertisingPublished,
            browsing = browsingBackends.isNotEmpty(),
            advertisingRequested = advertisingRequested,
            browsingRequested = browsingRequested,
            ownPresenceId = ownPresenceId,
            effectiveLanServiceName = effectiveLanServiceName,
            backendPolicy = config.backendPolicy,
            lanBackend = backendStatus.getValue(DiscoveryRouteKind.Lan).snapshot(DiscoveryRouteKind.Lan),
            directBackend = backendStatus.getValue(DiscoveryRouteKind.Direct).snapshot(DiscoveryRouteKind.Direct),
            candidateCount = cacheSnapshot.candidates.size,
            availableCount = available,
            unavailableCount = cacheSnapshot.candidates.size - available - conflicted,
            conflictedCount = conflicted,
            lanRouteCount = cacheSnapshot.lanRouteCount,
            directRouteCount = cacheSnapshot.directRouteCount,
            selfDiscoveryDrops = counters.selfDiscoveryDrops,
            malformedAdvertisements = counters.malformedAdvertisements,
            unsupportedDiscoveryVersions = counters.unsupportedDiscoveryVersions,
            conflictingAdvertisements = counters.conflictingAdvertisements,
            capacityDrops = counters.capacityDrops,
            expiredRoutes = counters.expiredRoutes,
            registrationFailures = counters.registrationFailures,
            discoveryFailures = counters.discoveryFailures,
            resolutionFailures = counters.resolutionFailures,
            permissionFailures = counters.permissionFailures,
            controllerGeneration = activeControllerGeneration,
            advertisementGeneration = activeAdvertisementGeneration,
            candidates = cacheSnapshot.candidates,
            lastError = lastError,
        )
    }

    private fun selectedKinds(): Set<DiscoveryRouteKind> = when (config.backendPolicy) {
        DiscoveryBackendPolicy.LanOnly -> setOf(DiscoveryRouteKind.Lan)
        DiscoveryBackendPolicy.DirectOnly -> setOf(DiscoveryRouteKind.Direct)
        DiscoveryBackendPolicy.DirectAndLan -> setOf(DiscoveryRouteKind.Lan, DiscoveryRouteKind.Direct)
    }

    private data class MutableBackendStatus(
        var state: DiscoveryBackendState,
        var lastError: DiscoveryError = DiscoveryError.None,
    ) {
        fun snapshot(kind: DiscoveryRouteKind): DiscoveryBackendSnapshot =
            DiscoveryBackendSnapshot(kind, state, lastError)
    }

    private data class DiscoveryCounters(
        var selfDiscoveryDrops: Long = 0L,
        var malformedAdvertisements: Long = 0L,
        var unsupportedDiscoveryVersions: Long = 0L,
        var conflictingAdvertisements: Long = 0L,
        var capacityDrops: Long = 0L,
        var expiredRoutes: Long = 0L,
        var registrationFailures: Long = 0L,
        var discoveryFailures: Long = 0L,
        var resolutionFailures: Long = 0L,
        var permissionFailures: Long = 0L,
    ) {
        fun reset() {
            selfDiscoveryDrops = 0L
            malformedAdvertisements = 0L
            unsupportedDiscoveryVersions = 0L
            conflictingAdvertisements = 0L
            capacityDrops = 0L
            expiredRoutes = 0L
            registrationFailures = 0L
            discoveryFailures = 0L
            resolutionFailures = 0L
            permissionFailures = 0L
        }
    }
}

private fun DiscoveryError.orOperationRejected(): DiscoveryError =
    if (this == DiscoveryError.None) DiscoveryError.OperationRejected else this

private fun DiscoveryError.toBackendState(): DiscoveryBackendState = when (this) {
    DiscoveryError.LanPermissionRequired,
    DiscoveryError.DirectPermissionRequired,
    -> DiscoveryBackendState.PermissionRequired
    DiscoveryError.LanPermissionDenied,
    DiscoveryError.DirectPermissionDenied,
    -> DiscoveryBackendState.PermissionDenied
    DiscoveryError.LocationServicesDisabled -> DiscoveryBackendState.LocationServicesDisabled
    DiscoveryError.DirectDiscoveryUnsupported -> DiscoveryBackendState.Unsupported
    else -> DiscoveryBackendState.Failed
}

private fun DiscoveryBackendState.isFailureState(): Boolean = this in setOf(
    DiscoveryBackendState.PermissionRequired,
    DiscoveryBackendState.PermissionDenied,
    DiscoveryBackendState.LocationServicesDisabled,
    DiscoveryBackendState.Unsupported,
    DiscoveryBackendState.Failed,
    DiscoveryBackendState.Closed,
)
