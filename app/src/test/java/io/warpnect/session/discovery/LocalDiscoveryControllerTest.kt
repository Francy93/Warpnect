package io.warpnect.session.discovery

import io.warpnect.session.DeviceId
import io.warpnect.session.PeerReference
import io.warpnect.session.SessionCreateRequest
import io.warpnect.session.SessionId
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import io.warpnect.session.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiscoveryControllerTest {
    @Test
    fun presenceIdentityIsTypedNonZeroAndFreshForEachFullAdvertisingEpoch() {
        assertNull(DiscoveryPresenceId.fromParts(0uL, 0uL))
        val presence = presence(1uL)
        assertNotEquals(presence, DeviceId.requireValid(0uL, 1uL))
        assertEquals("00000000000000000000000000000001", presence.encodedValue())

        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val ids = SequencePresenceIdGenerator(presence(10uL), presence(11uL))
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
            ),
            backends = listOf(lan),
            presenceIds = ids,
        )

        assertTrue(controller.start().isSuccess)
        val first = requireNotNull(controller.snapshot().ownPresenceId)
        controller.stopAdvertising()
        assertTrue(controller.startAdvertising().isSuccess)
        val second = requireNotNull(controller.snapshot().ownPresenceId)

        assertNotEquals(first, second)
        assertEquals(listOf(first, second), lan.advertisementRequests.map { it.advertisement.presenceId })
    }

    @Test
    fun codecUsesOnlySmallUntrustedDiscoveryMetadata() {
        val advertisement = DiscoveryAdvertisement(
            presenceId = presence(42uL),
            offeredRole = SessionRole.Host,
            availability = DiscoveryAvailability.Available,
            displayAlias = DiscoveryDisplayAlias.requireValid("Warpnect Host"),
            bootstrapPort = 50_000,
        )

        val lan = DiscoveryAdvertisementCodec.encode(advertisement, includeBootstrapPort = false)
        val direct = DiscoveryAdvertisementCodec.encode(advertisement, includeBootstrapPort = true)

        assertEquals("1", lan[DiscoveryAdvertisementCodec.KEY_SCHEMA_VERSION])
        assertEquals("h", lan[DiscoveryAdvertisementCodec.KEY_ROLE])
        assertEquals("1", lan[DiscoveryAdvertisementCodec.KEY_AVAILABILITY])
        assertFalse(lan.containsKey(DiscoveryAdvertisementCodec.KEY_BOOTSTRAP_PORT))
        assertEquals("50000", direct[DiscoveryAdvertisementCodec.KEY_BOOTSTRAP_PORT])
        assertTrue(DiscoveryAdvertisementCodec.txtWireSizeBytes(direct) < 256)
        assertEquals(
            setOf("dv", "pid", "role", "av", "name", "port"),
            direct.keys,
        )
        assertEquals(
            advertisement,
            (DiscoveryAdvertisementCodec.decode(direct) as DiscoveryAdvertisementDecodeResult.Decoded).advertisement,
        )
        assertEquals(
            "Warpnect-${advertisement.presenceId.shortValue()}",
            DiscoveryAdvertisementCodec.serviceInstanceName(advertisement.presenceId),
        )
        assertFalse(direct.values.any { it.contains(DeviceId.requireValid(0uL, 2uL).toString()) })
        assertFalse(direct.values.any { it.contains(SessionId.requireValid(0uL, 3uL).toString()) })
    }

    @Test
    fun codecRejectsMalformedRequiredFieldsButIgnoresUnknownFields() {
        val valid = DiscoveryAdvertisementCodec.encode(
            DiscoveryAdvertisement(
                presenceId = presence(4uL),
                offeredRole = SessionRole.Client,
                availability = DiscoveryAvailability.Unavailable,
                bootstrapPort = 12_345,
            ),
            includeBootstrapPort = true,
        )
        assertDecodedError(
            valid - DiscoveryAdvertisementCodec.KEY_SCHEMA_VERSION,
            DiscoveryAdvertisementError.MissingSchemaVersion,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_SCHEMA_VERSION to "x"),
            DiscoveryAdvertisementError.UnsupportedSchemaVersion,
        )
        assertDecodedError(
            valid - DiscoveryAdvertisementCodec.KEY_PRESENCE_ID,
            DiscoveryAdvertisementError.MissingPresenceId,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_PRESENCE_ID to "0".repeat(32)),
            DiscoveryAdvertisementError.InvalidPresenceId,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_ROLE to "host"),
            DiscoveryAdvertisementError.InvalidRole,
        )
        assertDecodedError(
            valid - DiscoveryAdvertisementCodec.KEY_AVAILABILITY,
            DiscoveryAdvertisementError.MissingAvailability,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_AVAILABILITY to "2"),
            DiscoveryAdvertisementError.InvalidAvailability,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_BOOTSTRAP_PORT to "0"),
            DiscoveryAdvertisementError.InvalidPort,
        )
        assertDecodedError(
            valid + (DiscoveryAdvertisementCodec.KEY_BOOTSTRAP_PORT to "invalid"),
            DiscoveryAdvertisementError.InvalidPort,
        )
        assertDecodedError(
            valid + ("futurePayload" to "x".repeat(256)),
            DiscoveryAdvertisementError.TxtTooLarge,
        )

        val decoded = DiscoveryAdvertisementCodec.decode(
            valid + ("futureKey" to "x") + (DiscoveryAdvertisementCodec.KEY_ALIAS to "\u0001bad"),
        ) as DiscoveryAdvertisementDecodeResult.Decoded
        assertNull(decoded.advertisement.displayAlias)
        assertEquals(SessionRole.Client, decoded.advertisement.offeredRole)
    }

    @Test
    fun matchingLanAndDirectObservationsMergeIntoOneUntrustedPresence() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val controller = controller(backends = listOf(lan, direct))
        assertTrue(controller.start().isSuccess)

        val advertisement = remoteAdvertisement(presence(20uL))
        lan.emit(advertisement, lanDescriptor("10.0.0.2", 40_000), "lan-service")
        direct.emit(advertisement, directDescriptor(40_000, "aa:bb:cc"), "direct-service")

        val snapshot = controller.snapshot()
        assertEquals(1, snapshot.candidateCount)
        assertEquals(
            listOf(DiscoveryRouteKind.Lan, DiscoveryRouteKind.Direct),
            snapshot.candidates.single().availablePathKinds,
        )
        assertEquals(1, snapshot.lanRouteCount)
        assertEquals(1, snapshot.directRouteCount)
        assertEquals(DiscoveryPresenceStatus.Usable, snapshot.candidates.single().status)
    }

    @Test
    fun samePresenceWithConflictingRequiredMetadataIsNotSilentlyMerged() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val controller = controller(backends = listOf(lan, direct))
        controller.start()

        val id = presence(21uL)
        lan.emit(remoteAdvertisement(id, role = SessionRole.Host), lanDescriptor("10.0.0.3", 40_001), "lan")
        direct.emit(remoteAdvertisement(id, role = SessionRole.Client), directDescriptor(40_001, "peer"), "direct")

        val snapshot = controller.snapshot()
        assertEquals(1, snapshot.candidateCount)
        assertEquals(DiscoveryPresenceStatus.Conflicted, snapshot.candidates.single().status)
        assertEquals(listOf(DiscoveryRouteKind.Lan), snapshot.candidates.single().availablePathKinds)
        assertEquals(1L, snapshot.conflictingAdvertisements)
    }

    @Test
    fun samePresenceWithConflictingAvailabilityIsNotSilentlyMerged() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val controller = controller(backends = listOf(lan, direct))
        controller.start()

        val id = presence(22uL)
        lan.emit(remoteAdvertisement(id), lanDescriptor("10.0.0.31", 40_021), "lan")
        direct.emit(
            remoteAdvertisement(id, availability = DiscoveryAvailability.Unavailable),
            directDescriptor(40_021, "peer"),
            "direct",
        )

        val snapshot = controller.snapshot()
        assertEquals(DiscoveryPresenceStatus.Conflicted, snapshot.candidates.single().status)
        assertEquals(listOf(DiscoveryRouteKind.Lan), snapshot.candidates.single().availablePathKinds)
        assertEquals(1L, snapshot.conflictingAdvertisements)
    }

    @Test
    fun selfObservationsAreDroppedAndRoutesDisappearIndependently() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val ids = SequencePresenceIdGenerator(presence(30uL))
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseAndBrowse,
                backendPolicy = DiscoveryBackendPolicy.DirectAndLan,
            ),
            backends = listOf(lan, direct),
            presenceIds = ids,
        )
        controller.start()

        val own = requireNotNull(controller.snapshot().ownPresenceId)
        lan.emit(remoteAdvertisement(own), lanDescriptor("10.0.0.4", 40_002), "self")
        assertEquals(1L, controller.snapshot().selfDiscoveryDrops)
        assertEquals(0, controller.snapshot().candidateCount)

        val remote = remoteAdvertisement(presence(31uL))
        lan.emit(remote, lanDescriptor("10.0.0.5", 40_003), "lan")
        direct.emit(remote, directDescriptor(40_003, "direct"), "direct")
        lan.lose("lan")
        assertEquals(listOf(DiscoveryRouteKind.Direct), controller.snapshot().candidates.single().availablePathKinds)
        direct.lose("direct")
        assertEquals(0, controller.snapshot().candidateCount)
    }

    @Test
    fun staleRoutesExpireAtTheDocumentedBoundaryWithoutSleeps() {
        val clock = TestClock()
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                presenceStaleAfterMs = 30_000L,
                expiryCheckIntervalMs = 5_000L,
            ),
            backends = listOf(lan),
            clock = clock,
        )
        controller.start()
        lan.emit(remoteAdvertisement(presence(32uL)), lanDescriptor("10.0.0.6", 40_004), "lan")
        clock.nowMs = 29_999L
        controller.expireStaleRoutes()
        assertEquals(1, controller.snapshot().candidateCount)
        clock.nowMs = 30_000L
        controller.expireStaleRoutes()
        assertEquals(0, controller.snapshot().candidateCount)
        assertEquals(1L, controller.snapshot().expiredRoutes)
    }

    @Test
    fun presenceCacheRejectsNewCandidatesAtItsFixedCapacity() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                maxDiscoveredPresences = 64,
            ),
            backends = listOf(lan),
        )
        controller.start()
        repeat(64) { index ->
            lan.emit(
                remoteAdvertisement(presence((100 + index).toULong())),
                lanDescriptor("10.0.1.${index + 1}", 41_000 + index),
                "lan-$index",
            )
        }
        lan.emit(remoteAdvertisement(presence(999uL)), lanDescriptor("10.0.9.9", 42_000), "overflow")

        assertEquals(64, controller.snapshot().candidateCount)
        assertEquals(1L, controller.snapshot().capacityDrops)
    }

    @Test
    fun availabilityPoliciesKeepEpochStableAcrossCapacitySuspension() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val ids = SequencePresenceIdGenerator(presence(40uL))
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                visibilityPolicy = DiscoveryVisibilityPolicy.HideWhenUnavailable,
            ),
            backends = listOf(lan),
            presenceIds = ids,
        )
        controller.start()
        val epoch = requireNotNull(controller.snapshot().ownPresenceId)
        controller.updateAvailability(DiscoveryAvailability.AtCapacity)
        assertFalse(controller.snapshot().advertising)
        assertEquals(epoch, controller.snapshot().ownPresenceId)
        assertEquals(1, lan.stopAdvertisingCalls)
        controller.updateAvailability(DiscoveryAvailability.Available)
        assertEquals(epoch, controller.snapshot().ownPresenceId)
        assertEquals(listOf(epoch, epoch), lan.advertisementRequests.map { it.advertisement.presenceId })

        val unavailableLan = FakeBackend(DiscoveryRouteKind.Lan)
        val unavailableController = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
                visibilityPolicy = DiscoveryVisibilityPolicy.AdvertiseUnavailable,
            ),
            backends = listOf(unavailableLan),
        )
        unavailableController.start()
        unavailableController.updateAvailability(DiscoveryAvailability.AtCapacity)
        assertTrue(unavailableController.snapshot().advertising)
        assertEquals(
            DiscoveryAvailability.AtCapacity,
            unavailableLan.advertisementRequests.last().advertisement.availability,
        )
    }

    @Test
    fun sessionManagerAvailabilityIsReadOnlyAndRespectsMultiClientCapacity() {
        val manager = SessionManager(
            SessionManagerConfig(
                localDeviceId = DeviceId.requireValid(0uL, 1uL),
                initialPolicy = io.warpnect.session.SessionBehaviorPolicy(maxConcurrentClients = 2),
            ),
        )
        val provider = SessionManagerHostAvailabilityProvider(manager)
        assertEquals(DiscoveryAvailability.Available, provider.availability())
        assertTrue(manager.createSession(hostSession(1uL, 11uL)).isSuccess)
        assertEquals(DiscoveryAvailability.Available, provider.availability())
        assertTrue(manager.createSession(hostSession(2uL, 12uL)).isSuccess)
        assertEquals(DiscoveryAvailability.AtCapacity, provider.availability())
    }

    @Test
    fun dualBackendAllowsTypedDegradationWhileDirectOnlyFailsExplicitly() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val deniedDirect = FakeBackend(
            kind = DiscoveryRouteKind.Direct,
            prepareResult = DiscoveryBackendCommandResult.rejected(DiscoveryError.DirectPermissionRequired),
        )
        val degraded = controller(backends = listOf(lan, deniedDirect))
        assertTrue(degraded.prepare().isSuccess)
        assertTrue(degraded.start().isSuccess)
        assertEquals(DiscoveryControllerState.RunningDegraded, degraded.snapshot().state)
        assertEquals(DiscoveryBackendState.PermissionRequired, degraded.snapshot().directBackend.state)

        val directOnly = controller(
            config = DiscoveryConfig(backendPolicy = DiscoveryBackendPolicy.DirectOnly),
            backends = listOf(deniedDirect),
        )
        assertFalse(directOnly.prepare().isSuccess)
        assertEquals(DiscoveryError.RequiredBackendUnavailable, directOnly.snapshot().lastError)
    }

    @Test
    fun lanRegistrationCallbackFailurePropagatesIntoTheHostDiscoverySnapshot() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
            ),
            backends = listOf(lan),
        )

        assertTrue(controller.startAdvertising().isSuccess)
        lan.emitAdvertisingState(DiscoveryBackendState.Failed, DiscoveryError.RegistrationFailed)

        assertEquals(DiscoveryControllerState.Error, controller.snapshot().state)
        assertEquals(DiscoveryError.RegistrationFailed, controller.snapshot().lastError)
        assertFalse(controller.snapshot().advertising)
    }

    @Test
    fun lanDiscoveryCallbackFailurePropagatesIntoTheClientDiscoverySnapshot() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.BrowseOnly,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
            ),
            backends = listOf(lan),
        )

        assertTrue(controller.startBrowsing().isSuccess)
        lan.emitBrowsingState(DiscoveryBackendState.Failed, DiscoveryError.DiscoveryFailed)

        assertEquals(DiscoveryControllerState.Error, controller.snapshot().state)
        assertEquals(DiscoveryError.DiscoveryFailed, controller.snapshot().lastError)
        assertFalse(controller.snapshot().browsing)
    }

    @Test
    fun oneAsyncBackendFailureKeepsDirectAndLanDiscoveryRunningDegraded() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val controller = controller(backends = listOf(lan, direct))

        assertTrue(controller.startBrowsing().isSuccess)
        direct.emitBrowsingState(DiscoveryBackendState.Failed, DiscoveryError.DiscoveryFailed)
        lan.emitBrowsingState(DiscoveryBackendState.Running)

        assertEquals(DiscoveryControllerState.RunningDegraded, controller.snapshot().state)
        assertEquals(DiscoveryError.DiscoveryFailed, controller.snapshot().directBackend.lastError)
        assertEquals(DiscoveryBackendState.Running, controller.snapshot().lanBackend.state)
    }

    @Test
    fun bothAsyncBackendFailuresLeaveNoApparentlyHealthyDiscoveryState() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val direct = FakeBackend(DiscoveryRouteKind.Direct)
        val controller = controller(backends = listOf(lan, direct))

        assertTrue(controller.startBrowsing().isSuccess)
        lan.emitBrowsingState(DiscoveryBackendState.Failed, DiscoveryError.DiscoveryFailed)
        direct.emitBrowsingState(DiscoveryBackendState.Failed, DiscoveryError.DirectPermissionDenied)

        assertEquals(DiscoveryControllerState.Error, controller.snapshot().state)
        assertFalse(controller.snapshot().browsing)
    }

    @Test
    fun staleCallbacksAndOldRouteTokensCannotResurrectAStoppedGeneration() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val controller = controller(
            config = DiscoveryConfig(mode = DiscoveryMode.BrowseOnly, backendPolicy = DiscoveryBackendPolicy.LanOnly),
            backends = listOf(lan),
        )
        controller.start()
        val oldGeneration = requireNotNull(controller.snapshot().controllerGeneration)
        lan.emit(remoteAdvertisement(presence(50uL)), lanDescriptor("10.0.0.7", 40_005), "old", oldGeneration)
        assertEquals(1, controller.snapshot().candidateCount)

        controller.close()
        lan.emit(remoteAdvertisement(presence(51uL)), lanDescriptor("10.0.0.8", 40_006), "late", oldGeneration)
        assertEquals(0, controller.snapshot().candidateCount)
    }

    @Test
    fun fullStopRotatesPresenceAndRejectsCallbacksFromThePriorGeneration() {
        val lan = FakeBackend(DiscoveryRouteKind.Lan)
        val ids = SequencePresenceIdGenerator(presence(60uL), presence(61uL))
        val controller = controller(
            config = DiscoveryConfig(
                mode = DiscoveryMode.AdvertiseAndBrowse,
                backendPolicy = DiscoveryBackendPolicy.LanOnly,
            ),
            backends = listOf(lan),
            presenceIds = ids,
        )
        controller.start()
        val firstPresence = requireNotNull(controller.snapshot().ownPresenceId)
        val firstGeneration = requireNotNull(controller.snapshot().controllerGeneration)
        lan.emit(
            advertisement = remoteAdvertisement(presence(62uL)),
            descriptor = lanDescriptor("10.0.0.10", 40_008),
            key = "old",
            generation = firstGeneration,
        )
        assertEquals(1, controller.snapshot().candidateCount)

        controller.stop()
        controller.start()
        assertNotEquals(firstPresence, controller.snapshot().ownPresenceId)
        lan.emit(
            advertisement = remoteAdvertisement(presence(63uL)),
            descriptor = lanDescriptor("10.0.0.11", 40_009),
            key = "late",
            generation = firstGeneration,
        )
        assertEquals(0, controller.snapshot().candidateCount)
    }

    @Test
    fun routeTokensAreBoundToTheirDiscoveryControllerGeneration() {
        val cache = DiscoveryPresenceCache(maxPresences = 1, staleAfterMs = 30_000L)
        cache.reset(controllerGeneration = 7L)
        val token = requireNotNull(
            cache.observe(
                DiscoveryRouteObservation(
                    backendRouteKey = "lan",
                    kind = DiscoveryRouteKind.Lan,
                    advertisement = remoteAdvertisement(presence(52uL)),
                    descriptor = lanDescriptor("10.0.0.9", 40_007),
                ),
                ownPresenceId = null,
                nowMonotonicMs = 0L,
            ).routeToken,
        )
        assertTrue(cache.resolve(token, 7L).isSuccess)
        cache.reset(controllerGeneration = 8L)
        assertEquals(DiscoveryError.RouteNotFound, cache.resolve(token, 8L).error)
    }

    private fun assertDecodedError(values: Map<String, String>, expected: DiscoveryAdvertisementError) {
        assertEquals(
            expected,
            (DiscoveryAdvertisementCodec.decode(values) as DiscoveryAdvertisementDecodeResult.Rejected).error,
        )
    }

    private fun controller(
        config: DiscoveryConfig = DiscoveryConfig(
            mode = DiscoveryMode.BrowseOnly,
            backendPolicy = DiscoveryBackendPolicy.DirectAndLan,
        ),
        backends: List<FakeBackend> = listOf(
            FakeBackend(DiscoveryRouteKind.Lan),
            FakeBackend(DiscoveryRouteKind.Direct),
        ),
        clock: TestClock = TestClock(),
        presenceIds: DiscoveryPresenceIdGenerator = SequencePresenceIdGenerator(presence(1_000uL)),
    ): DefaultLocalDiscoveryController = DefaultLocalDiscoveryController(
        config = config,
        backends = backends,
        contactEndpointLeaseFactory = FakeLeaseFactory(),
        clock = clock,
        presenceIdGenerator = presenceIds,
    )

    private fun remoteAdvertisement(
        id: DiscoveryPresenceId,
        role: SessionRole = SessionRole.Host,
        availability: DiscoveryAvailability = DiscoveryAvailability.Available,
    ): DiscoveryAdvertisement = DiscoveryAdvertisement(
        presenceId = id,
        offeredRole = role,
        availability = availability,
        displayAlias = DiscoveryDisplayAlias.DEFAULT_HOST,
        bootstrapPort = 40_000,
    )

    private fun lanDescriptor(address: String, port: Int): DiscoveryRouteDescriptor.Lan =
        DiscoveryRouteDescriptor.Lan(listOf(DiscoveryAddressCandidate(address)), port)

    private fun directDescriptor(port: Int, locator: String): DiscoveryRouteDescriptor.Direct =
        DiscoveryRouteDescriptor.Direct(port, DiscoveryOpaqueRouteLocator(locator))

    private fun presence(low: ULong): DiscoveryPresenceId = DiscoveryPresenceId.requireValid(0uL, low)

    private fun hostSession(sessionLow: ULong, peerLow: ULong): SessionCreateRequest = SessionCreateRequest(
        sessionId = SessionId.requireValid(0uL, sessionLow),
        remotePeer = PeerReference(DeviceId.requireValid(0uL, peerLow)),
        localRole = SessionRole.Host,
        remoteRole = SessionRole.Client,
    )

    private class TestClock(
        var nowMs: Long = 0L,
    ) : DiscoveryMonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private class SequencePresenceIdGenerator(
        private vararg val values: DiscoveryPresenceId,
    ) : DiscoveryPresenceIdGenerator {
        private var index = 0

        override fun next(): DiscoveryPresenceId = values.getOrElse(index++) {
            DiscoveryPresenceId.requireValid(0uL, 10_000uL + index.toULong())
        }
    }

    private class FakeLeaseFactory : DiscoveryContactEndpointLeaseFactory {
        private var nextPort = 45_000

        override fun acquire(): DiscoveryContactEndpointLeaseResult = DiscoveryContactEndpointLeaseResult(
            lease = object : DiscoveryContactEndpointLease {
                override val port: Int = nextPort++
                override fun close() = Unit
            },
        )
    }

    private class FakeBackend(
        override val kind: DiscoveryRouteKind,
        private val prepareResult: DiscoveryBackendCommandResult = DiscoveryBackendCommandResult.Accepted,
    ) : DiscoveryBackend {
        private lateinit var observer: DiscoveryBackendObserver
        private var browseGeneration: Long? = null
        private var advertisingRequest: DiscoveryBackendAdvertisingRequest? = null
        val advertisementRequests = mutableListOf<DiscoveryBackendAdvertisingRequest>()
        var stopAdvertisingCalls = 0

        override fun prepare(observer: DiscoveryBackendObserver): DiscoveryBackendCommandResult {
            this.observer = observer
            return prepareResult
        }

        override fun startAdvertising(request: DiscoveryBackendAdvertisingRequest): DiscoveryBackendCommandResult {
            advertisementRequests += request
            advertisingRequest = request
            return DiscoveryBackendCommandResult.Accepted
        }

        override fun stopAdvertising(controllerGeneration: Long, advertisementGeneration: Long?) {
            stopAdvertisingCalls += 1
        }

        override fun startBrowsing(controllerGeneration: Long): DiscoveryBackendCommandResult {
            browseGeneration = controllerGeneration
            return DiscoveryBackendCommandResult.Accepted
        }

        override fun stopBrowsing(controllerGeneration: Long) = Unit

        override fun close() = Unit

        fun emit(
            advertisement: DiscoveryAdvertisement,
            descriptor: DiscoveryRouteDescriptor,
            key: String,
            generation: Long = requireNotNull(browseGeneration),
        ) {
            observer.onRouteObserved(
                generation,
                DiscoveryRouteObservation(key, kind, advertisement, descriptor),
            )
        }

        fun lose(key: String, generation: Long = requireNotNull(browseGeneration)) {
            observer.onRouteLost(generation, kind, key)
        }

        fun emitAdvertisingState(state: DiscoveryBackendState, error: DiscoveryError = DiscoveryError.None) {
            val request = requireNotNull(advertisingRequest)
            observer.onBackendState(
                request.controllerGeneration,
                kind,
                DiscoveryBackendOperation.Advertising,
                request.advertisementGeneration,
                state,
                error,
            )
        }

        fun emitBrowsingState(state: DiscoveryBackendState, error: DiscoveryError = DiscoveryError.None) {
            observer.onBackendState(
                requireNotNull(browseGeneration),
                kind,
                DiscoveryBackendOperation.Browsing,
                null,
                state,
                error,
            )
        }
    }
}
