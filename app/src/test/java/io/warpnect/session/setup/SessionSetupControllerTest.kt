package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.handshake.AuthenticatedSessionAdmissionReservation
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetupControllerTest {
    @Test
    fun lanSetupProducesOnePreparedBootstrapPerPeerWithoutStartingTransports() {
        val fixture = Fixture()

        fixture.start()

        assertEquals(1, fixture.hostCompletions.size)
        assertEquals(1, fixture.clientCompletions.size)
        assertEquals(NetworkPathKind.Lan, fixture.hostCompletions.single().activePath.kind)
        assertEquals(2, fixture.hostCompletions.single().channels.size)
        assertTrue(fixture.hostCompletions.single().channels.all { it.transport.protectedRequired })
        assertTrue(fixture.hostCompletions.single().channels.none { it.transport.started })
        assertEquals(1, fixture.reservation.renewCount)
        assertEquals(2, fixture.hostRuntime.createdChannels.size)
        assertEquals(2, fixture.clientRuntime.createdChannels.size)
        assertEquals(0, fixture.host.snapshot().activeSetups)
        assertEquals(1, fixture.host.snapshot().completionCacheSize)
    }

    @Test
    fun lanSetupUsesNegotiatedInputFlagsForBothPeers() {
        val profile = profile().copy(
            inputFeatureFlags = CapabilityBits.INPUT_STATE_CONVERGENCE or CapabilityBits.INPUT_PRIVILEGED_INJECTION,
        )
        val fixture = Fixture(profile = profile)

        fixture.start()

        val hostInput = fixture.hostCompletions.single().channels.flatMap { it.configuration }
            .filterIsInstance<SetupConfiguration.Input>()
            .single()
            .config
        val clientInput = fixture.clientCompletions.single().channels.flatMap { it.configuration }
            .filterIsInstance<SetupConfiguration.Input>()
            .single()
            .config
        assertEquals(profile.inputFeatureFlags, hostInput.featureFlags)
        assertEquals(profile.inputFeatureFlags, clientInput.featureFlags)
    }

    @Test
    fun debugObserverReportsBoundedLanSetupMilestonesWithoutPayloadData() {
        val fixture = Fixture()

        fixture.start()

        assertTrue(SessionSetupDebugEventKind.OfferBuildStarted in fixture.clientDebugKinds)
        assertTrue(SessionSetupDebugEventKind.OfferBuilt in fixture.clientDebugKinds)
        assertTrue(SessionSetupDebugEventKind.OfferSent in fixture.clientDebugKinds)
        assertTrue(SessionSetupDebugEventKind.OfferReceived in fixture.hostDebugKinds)
        assertTrue(SessionSetupDebugEventKind.PathSelectionSent in fixture.hostDebugKinds)
        assertTrue(SessionSetupDebugEventKind.PathSelectionReceived in fixture.clientDebugKinds)
        assertTrue(SessionSetupDebugEventKind.SocketPathBindingSucceeded in fixture.hostDebugKinds)
        assertTrue(SessionSetupDebugEventKind.ChannelPlanValidationSucceeded in fixture.hostDebugKinds)
        assertTrue(SessionSetupDebugEventKind.Committed in fixture.hostDebugKinds)
        assertTrue(SessionSetupDebugEventKind.Committed in fixture.clientDebugKinds)
    }

    @Test
    fun debugObserverReportsTheTypedPreconditionFailureExactlyOnce() {
        val fixture = Fixture()

        assertEquals(
            SessionSetupError.None,
            fixture.host.registerHost(
                fixture.hostBootstrap,
                fixture.hostSetupRuntime,
                HostSessionSetupPolicy(fixture.preferences),
            ),
        )
        assertEquals(
            SessionSetupError.InvalidConfig,
            fixture.client.beginClient(
                fixture.clientBootstrap,
                fixture.clientSetupRuntime,
                SessionSetupPreferences(),
            ),
        )

        assertEquals(1, fixture.clientDebugKinds.count { it == SessionSetupDebugEventKind.Failed })
    }

    @Test
    fun duplicateAcceptUsesCompletionCacheWithoutAllocatingOrRenewingAgain() {
        val fixture = Fixture()
        fixture.start()
        val accept = fixture.clientTransport.sent.single { bytes ->
            SessionSetupCodec.decode(bytes)?.message is SessionSetupMessage.ClientConfigurationAccept
        }
        val hostAllocations = fixture.hostAllocator.allocations
        val hostContexts = fixture.hostRuntime.createdChannels.size

        fixture.host.receive(fixture.sessionId, accept)

        assertEquals(1, fixture.reservation.renewCount)
        assertEquals(hostAllocations, fixture.hostAllocator.allocations)
        assertEquals(hostContexts, fixture.hostRuntime.createdChannels.size)
        assertEquals(1, fixture.hostCompletions.size)
        assertEquals(1, fixture.host.snapshot().completionCacheSize)
    }

    @Test
    fun lostHostCommitIsRecoveredByFreshSemanticAcceptRetry() {
        val fixture = Fixture()
        fixture.hostTransport.dropNext(SessionSetupMessageType.HostCommit)

        fixture.start()
        assertEquals(1, fixture.hostCompletions.size)
        assertEquals(0, fixture.clientCompletions.size)
        val initialAccept = fixture.clientTransport.sent.last()

        fixture.clock.nowMs = SessionSetupProtocol.RETRY_DELAYS_MS.first()
        fixture.client.advance()

        assertEquals(1, fixture.clientCompletions.size)
        assertEquals(1, fixture.reservation.renewCount)
        assertEquals(1, fixture.hostCompletions.size)
        assertTrue(initialAccept.contentEquals(fixture.clientTransport.sent.last()))
        assertEquals(1, fixture.host.snapshot().completionCacheSize)
    }

    @Test
    fun sameSetupIdWithChangedClientRequestFailsClosed() {
        val fixture = Fixture(hostToClientDelivery = false)
        fixture.start()
        val original = fixture.clientTransport.sent.single()
        val request = requireNotNull(
            SessionSetupCodec.decode(original),
        ).message as SessionSetupMessage.ClientSetupRequest
        val changed = requireNotNull(
            SessionSetupCodec.encode(
                request.copy(
                    preferences = request.preferences.copy(pathPreference = PathPreferencePolicy.PreferLan),
                ),
            ),
        )

        fixture.host.receive(fixture.sessionId, changed)

        assertEquals(SessionSetupError.SetupConflict, fixture.host.snapshot().lastError)
        assertEquals(0, fixture.host.snapshot().activeSetups)
        assertTrue(fixture.reservation.closed)
    }

    @Test
    fun timeoutClosesAllocatedStateAndAuthenticatedAdmission() {
        val fixture = Fixture(hostToClientDelivery = false)
        fixture.start()

        fixture.clock.nowMs = SessionSetupProtocol.DEFAULT_LAN_TIMEOUT_MS
        fixture.host.advance()

        assertEquals(SessionSetupError.SetupTimeout, fixture.host.snapshot().lastError)
        assertEquals(0, fixture.host.snapshot().activeSetups)
        assertTrue(fixture.reservation.closed)
        assertTrue(fixture.hostCompletions.isEmpty())
    }

    @Test
    fun explicitDirectThenLanFallbackCompletesOnLan() {
        val preferences = preferences().copy(
            pathPreference = PathPreferencePolicy.PreferDirectThenLan,
            secondaryPathPolicy = SecondaryPathPolicy.Disabled,
        )
        val fixture = Fixture(
            profile = profile(eligiblePaths = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT),
            preferences = preferences,
            hostDirect = FailingDirectCoordinator,
            clientDirect = FailingDirectCoordinator,
        )

        fixture.start()

        assertEquals(NetworkPathKind.Lan, fixture.hostCompletions.single().activePath.kind)
        assertEquals(1, fixture.host.snapshot().directFallbacks)
        assertEquals(SessionSetupError.None, fixture.host.snapshot().lastError)
    }

    @Test
    fun directOnlyDoesNotFallBackAndBothPeersFail() {
        val preferences = preferences().copy(
            pathPreference = PathPreferencePolicy.DirectOnly,
            secondaryPathPolicy = SecondaryPathPolicy.Disabled,
        )
        val fixture = Fixture(
            profile = profile(eligiblePaths = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT),
            preferences = preferences,
            hostDirect = FailingDirectCoordinator,
            clientDirect = FailingDirectCoordinator,
        )

        fixture.start()

        assertTrue(fixture.hostCompletions.isEmpty())
        assertTrue(fixture.clientCompletions.isEmpty())
        assertEquals(SessionSetupError.DirectUnavailable, fixture.host.snapshot().lastError)
        assertEquals(SessionSetupError.DirectUnavailable, fixture.client.snapshot().lastError)
        assertTrue(fixture.reservation.closed)
    }

    @Test
    fun lanOnlyNeverInvokesDirectPathBackend() {
        val direct = CountingDirectCoordinator()
        val fixture = Fixture(
            profile = profile(eligiblePaths = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT),
            preferences = preferences().copy(
                pathPreference = PathPreferencePolicy.LanOnly,
                secondaryPathPolicy = SecondaryPathPolicy.KeepValidatedStandby,
            ),
            hostDirect = direct,
            clientDirect = direct,
        )

        fixture.start()

        assertEquals(0, direct.hostCalls)
        assertEquals(0, direct.clientCalls)
        assertEquals(NetworkPathKind.Lan, fixture.hostCompletions.single().activePath.kind)
        assertEquals(null, fixture.hostCompletions.single().standbyPath)
    }

    @Test
    fun directActiveRetainsLanStandbyWithoutDuplicatingChannels() {
        val direct = SucceedingDirectCoordinator()
        val fixture = Fixture(
            profile = profile(eligiblePaths = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT),
            preferences = preferences().copy(
                pathPreference = PathPreferencePolicy.PreferDirectThenLan,
                secondaryPathPolicy = SecondaryPathPolicy.KeepValidatedStandby,
            ),
            hostDirect = direct,
            clientDirect = direct,
        )

        fixture.start()

        val prepared = fixture.hostCompletions.single()
        assertEquals(NetworkPathKind.Direct, prepared.activePath.kind)
        assertEquals(NetworkPathKind.Lan, prepared.standbyPath?.kind)
        assertTrue(prepared.activePath.pathId in prepared.pathControlEndpoints)
        assertTrue(requireNotNull(prepared.standbyPath).pathId in prepared.pathControlEndpoints)
        assertEquals(2, prepared.channels.size)
        assertEquals(1, direct.hostCalls)
        assertEquals(1, direct.clientCalls)
    }

    @Test
    fun orderedVideoAlternativeAdvancesProposalOnceAndReusesEndpointLeases() {
        val first = VideoStreamMode(1280, 720, 60, 8_000_000, CapabilityBits.VIDEO_LOW_LATENCY_DECODE)
        val second = VideoStreamMode(960, 540, 60, 5_000_000, CapabilityBits.VIDEO_LOW_LATENCY_DECODE)
        val preferences = preferences().copy(
            video = VideoStreamPreference(VideoPreferencePolicy.OrderedAllowedModes, listOf(first, second)),
        )
        val fixture = Fixture(
            preferences = preferences,
            clientValidator = ExactStreamConfigurationValidator { role, _, configurations ->
                val video = configurations.filterIsInstance<SetupConfiguration.Video>().singleOrNull()
                if (role == SessionRole.Client && video?.mode == first) {
                    SessionSetupError.ExactVideoConfigurationUnavailable
                } else {
                    SessionSetupError.None
                }
            },
        )

        fixture.start()

        assertEquals(1, fixture.hostCompletions.size)
        assertEquals(1, fixture.clientCompletions.size)
        assertEquals(2, fixture.host.snapshot().lastProposalGeneration)
        assertEquals(2, fixture.hostAllocator.allocations)
        assertEquals(2, fixture.clientAllocator.allocations)
        assertEquals(2, fixture.hostRuntime.createdChannels.size)
        assertTrue(fixture.hostRuntime.destroyedChannels.isEmpty())
    }

    @Test
    fun preparedBootstrapCloseIsIdempotentAndReleasesAllOwnedResources() {
        val fixture = Fixture()
        fixture.start()
        val prepared = fixture.hostCompletions.single()

        prepared.close()
        prepared.close()

        assertTrue(fixture.reservation.closed)
        assertTrue(fixture.hostAllocator.leases.all { it.closed })
        assertTrue(fixture.hostPreparer.transports.all { it.closed })
        assertTrue(fixture.hostRuntime.destroyedChannels.containsAll(fixture.hostRuntime.createdChannels))
        assertTrue(prepared.isExpired(fixture.clock.nowMs))
    }

    @Test
    fun preparedBootstrapExpiresAndReleasesItsBoundedResources() {
        val timer = TestTimer()
        val fixture = Fixture(hostTimer = timer)
        fixture.start()
        val prepared = fixture.hostCompletions.single()

        assertEquals(SessionSetupProtocol.PREPARED_BOOTSTRAP_TTL_MS, timer.nextDelay())
        fixture.clock.nowMs = SessionSetupProtocol.PREPARED_BOOTSTRAP_TTL_MS
        timer.runNext()

        assertTrue(prepared.isExpired(fixture.clock.nowMs))
        assertTrue(fixture.reservation.closed)
        assertTrue(fixture.hostAllocator.leases.all { it.closed })
        assertTrue(fixture.hostPreparer.transports.all { it.closed })
        assertTrue(fixture.hostRuntime.destroyedChannels.containsAll(fixture.hostRuntime.createdChannels))
    }

    @Test
    fun scheduledRetrySendsSameWnsnSemanticsAsFreshControlRecord() {
        val timer = TestTimer()
        val fixture = Fixture(hostToClientDelivery = false, clientTimer = timer)
        fixture.start()
        val first = fixture.clientTransport.sent.single().copyOf()

        assertEquals(100L, timer.nextDelay())
        fixture.clock.nowMs = 100L
        timer.runNext()

        assertEquals(2, fixture.clientTransport.sent.size)
        assertTrue(first.contentEquals(fixture.clientTransport.sent.last()))
        assertEquals(250L, timer.nextDelay())
    }

    private class Fixture(
        val profile: NegotiatedCapabilityProfile = profile(),
        val preferences: SessionSetupPreferences = preferences(),
        hostToClientDelivery: Boolean = true,
        hostDirect: DirectPathCoordinator? = null,
        clientDirect: DirectPathCoordinator? = null,
        clientValidator: ExactStreamConfigurationValidator = PASS_VALIDATOR,
        hostTimer: SessionSetupTimer? = null,
        clientTimer: SessionSetupTimer? = null,
    ) {
        val sessionId = SessionId.requireValid(0u, 301u)
        val clock = TestClock()
        val reservation = TestReservation(sessionId)
        val hostRuntime = TestProtectionRuntime(sessionId)
        val clientRuntime = TestProtectionRuntime(sessionId)
        val hostTransport = TestControlTransport()
        val clientTransport = TestControlTransport()
        val hostAllocator = TestEndpointAllocator(42_000)
        val clientAllocator = TestEndpointAllocator(41_000)
        val hostPreparer = TestTransportPreparer()
        val clientPreparer = TestTransportPreparer()
        val hostCompletions = mutableListOf<PreparedSessionBootstrap>()
        val clientCompletions = mutableListOf<PreparedSessionBootstrap>()
        val hostDebugKinds = mutableListOf<SessionSetupDebugEventKind>()
        val clientDebugKinds = mutableListOf<SessionSetupDebugEventKind>()
        val profileHash = ByteArray(32) { (it + 3).toByte() }
        val hostBootstrap = bootstrap(SessionRole.Host, hostRuntime, hostTransport, reservation)
        val clientBootstrap = bootstrap(SessionRole.Client, clientRuntime, clientTransport, null)
        val host = SessionSetupController(
            clock,
            SessionSetupIdGenerator { SessionSetupId.requireValid(71u) },
            PathAttemptIdGenerator { PathAttemptId.requireValid(81u) },
            timer = hostTimer,
            onCompleted = hostCompletions::add,
            debugObserver = SessionSetupDebugObserver { event -> hostDebugKinds += event.kind },
        )
        val client = SessionSetupController(
            clock,
            SessionSetupIdGenerator { SessionSetupId.requireValid(71u) },
            PathAttemptIdGenerator { PathAttemptId.requireValid(81u) },
            timer = clientTimer,
            onCompleted = clientCompletions::add,
            debugObserver = SessionSetupDebugObserver { event -> clientDebugKinds += event.kind },
        )
        val hostSetupRuntime = setupRuntime(hostAllocator, hostPreparer, hostDirect, PASS_VALIDATOR)
        val clientSetupRuntime = setupRuntime(clientAllocator, clientPreparer, clientDirect, clientValidator)

        init {
            hostTransport.peer = clientTransport
            clientTransport.peer = hostTransport
            hostTransport.deliver = hostToClientDelivery
        }

        fun start() {
            assertEquals(
                SessionSetupError.None,
                host.registerHost(hostBootstrap, hostSetupRuntime, HostSessionSetupPolicy(preferences)),
            )
            assertEquals(
                SessionSetupError.None,
                client.beginClient(
                    clientBootstrap,
                    clientSetupRuntime,
                    preferences.retainOnlySelectedChannels(profile),
                ),
            )
        }

        private fun bootstrap(
            role: SessionRole,
            runtime: SessionProtectionRuntime,
            transport: SecureSessionControlTransport,
            admission: AuthenticatedSessionAdmissionReservation?,
        ) = NegotiatedSessionBootstrap(
            sessionId,
            SessionGeneration.Initial,
            DeviceId.requireValid(0u, if (role == SessionRole.Host) 1u else 2u),
            DeviceId.requireValid(0u, if (role == SessionRole.Host) 2u else 1u),
            role,
            if (role == SessionRole.Host) SessionRole.Client else SessionRole.Host,
            profile,
            profileHash.copyOf(),
            runtime,
            transport,
            HandshakeTransportEndpoint.requireValid(
                byteArrayOf(
                    192.toByte(),
                    168.toByte(),
                    10,
                    if (role == SessionRole.Host) 2 else 1,
                ),
                42_000,
            ),
            admission,
        )

        private fun setupRuntime(
            allocator: ChannelEndpointAllocator,
            preparer: ChannelTransportPreparer,
            direct: DirectPathCoordinator?,
            validator: ExactStreamConfigurationValidator,
        ) = SessionSetupRuntime(
            lanCandidate = SetupPathCandidate(
                PathId.requireValid(1u),
                NetworkPathKind.Lan,
                PathSocketBinding(
                    PathId.requireValid(1u),
                    NetworkPathKind.Lan,
                    if (allocator === hostAllocator) "192.168.10.1" else "192.168.10.2",
                ),
                if (allocator === hostAllocator) "192.168.10.2" else "192.168.10.1",
            ),
            endpointAllocator = allocator,
            transportPreparer = preparer,
            exactValidator = validator,
            directCoordinator = direct,
            directRouteToken = direct?.let { "direct-route" },
        )
    }

    private class TestControlTransport : SecureSessionControlTransport {
        override val maxPayloadBytes = SessionSetupProtocol.MAX_PAYLOAD_BYTES
        val sent = mutableListOf<ByteArray>()
        var peer: TestControlTransport? = null
        var deliver = true
        private val drops = mutableMapOf<SessionSetupMessageType, Int>()
        private var listener: ((ByteArray) -> Unit)? = null

        fun dropNext(type: SessionSetupMessageType) {
            drops[type] = (drops[type] ?: 0) + 1
        }

        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) {
            this.listener = listener
        }

        override fun send(payload: ByteArray): SecureSessionControlSendResult {
            sent += payload.copyOf()
            val type = SessionSetupCodec.decode(payload)?.message?.header?.messageType
            val remaining = type?.let(drops::get) ?: 0
            if (remaining > 0 && type != null) {
                drops[type] = remaining - 1
            } else if (deliver) {
                peer?.listener?.invoke(payload.copyOf())
            }
            return SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())
        }

        override fun rebindRemoteEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError =
            SessionProtectionError.None

        override fun close() = Unit
    }

    private class TestClock(var nowMs: Long = 0L) : SessionSetupMonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private class TestTimer : SessionSetupTimer {
        private val tasks = mutableListOf<Scheduled>()

        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
            val scheduled = Scheduled(delayMs, task)
            tasks += scheduled
            return AutoCloseable { scheduled.cancelled = true }
        }

        fun nextDelay(): Long = tasks.filterNot(Scheduled::cancelled).minOf(Scheduled::delayMs)

        fun runNext() {
            val scheduled = tasks.filterNot(Scheduled::cancelled).minBy(Scheduled::delayMs)
            scheduled.cancelled = true
            scheduled.task()
        }

        private data class Scheduled(
            val delayMs: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class TestReservation(override val sessionId: SessionId) : AuthenticatedSessionAdmissionReservation {
        override val peerDeviceId = DeviceId.requireValid(0u, 2u)
        override val expiresAtMonotonicMs = Long.MAX_VALUE
        var renewCount = 0
        var closed = false

        override fun renew(lifetimeMs: Long): Boolean {
            renewCount += 1
            return !closed && lifetimeMs == SessionSetupProtocol.PREPARED_BOOTSTRAP_TTL_MS
        }

        override fun close() {
            closed = true
        }
    }

    private class TestEndpointAllocator(private val firstPort: Int) : ChannelEndpointAllocator {
        var allocations = 0
        val leases = mutableListOf<TestEndpointLease>()

        override fun allocate(
            binding: PathSocketBinding,
            channelKind: SessionChannelKind,
        ): ChannelEndpointAllocationResult {
            val lease = TestEndpointLease(binding, firstPort + allocations, channelKind)
            allocations += 1
            leases += lease
            return ChannelEndpointAllocationResult(SessionSetupError.None, lease)
        }
    }

    private class TestEndpointLease(
        override val binding: PathSocketBinding,
        override val localPort: Int,
        override val channelKind: SessionChannelKind,
    ) : ChannelEndpointLease {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class TestTransportPreparer : ChannelTransportPreparer {
        val transports = mutableListOf<TestPreparedTransport>()
        override fun prepare(request: ChannelTransportPreparationRequest): ChannelTransportPreparationResult {
            val transport = TestPreparedTransport()
            transports += transport
            return ChannelTransportPreparationResult(SessionSetupError.None, transport)
        }
    }

    private class TestPreparedTransport : PreparedChannelTransport {
        override val protectedRequired = true
        override val started = false
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class TestProtectionRuntime(override val sessionId: SessionId) : SessionProtectionRuntime {
        override val sessionControlContext = ProtectionContextIds(1, 2)
        override val maxInnerSclDatagramSize = 1_156
        val createdChannels = mutableListOf<ChannelId>()
        val destroyedChannels = mutableListOf<ChannelId>()

        override fun createChannelContext(channelId: ChannelId): SessionProtectionContextResult {
            createdChannels += channelId
            return SessionProtectionContextResult(
                SessionProtectionError.None,
                ProtectionContextIds(100 + channelId.value.toLong(), 200 + channelId.value.toLong()),
            )
        }

        override fun destroyChannelContext(channelId: ChannelId): SessionProtectionError {
            destroyedChannels += channelId
            return SessionProtectionError.None
        }

        override fun protectSessionControl(sequenceNumber: Long, timestampUs: Long, payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload.copyOf())

        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = io.warpnect.session.control.SessionControlUnprotectResult(
            SessionProtectionError.None,
            protectedDatagram.copyOf(),
        )

        override fun snapshot() =
            SessionProtectionSnapshot(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionProtectionError.None)

        override fun close() = Unit
    }

    private object FailingDirectCoordinator : DirectPathCoordinator {
        override fun prepareHost(request: DirectPathHostRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
        }

        override fun connectClient(request: DirectPathClientRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
        }

        override fun cancel(setupId: SessionSetupId) = Unit
        override fun close() = Unit
    }

    private open class CountingDirectCoordinator : DirectPathCoordinator {
        var hostCalls = 0
        var clientCalls = 0

        override fun prepareHost(request: DirectPathHostRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            hostCalls += 1
            listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
        }

        override fun connectClient(request: DirectPathClientRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            clientCalls += 1
            listener(DirectPathPreparationEvent.Failure(PathFailureReason.DirectUnavailable))
        }

        override fun cancel(setupId: SessionSetupId) = Unit
        override fun close() = Unit
    }

    private class SucceedingDirectCoordinator : CountingDirectCoordinator() {
        private var hostRequest: DirectPathHostRequest? = null
        private var hostListener: ((DirectPathPreparationEvent) -> Unit)? = null

        override fun prepareHost(request: DirectPathHostRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            hostCalls += 1
            hostRequest = request
            hostListener = listener
            listener(DirectPathPreparationEvent.HostReady(45_000))
        }

        override fun connectClient(request: DirectPathClientRequest, listener: (DirectPathPreparationEvent) -> Unit) {
            clientCalls += 1
            val host = requireNotNull(hostRequest)
            hostListener?.invoke(
                DirectPathPreparationEvent.Validated(
                    candidate(host.targetPathId, "192.168.49.1", "192.168.49.2", endpoint("192.168.49.2", 45_001)),
                    TestDirectLease(),
                ),
            )
            listener(
                DirectPathPreparationEvent.Validated(
                    candidate(request.targetPathId, "192.168.49.2", "192.168.49.1", endpoint("192.168.49.1", 45_000)),
                    TestDirectLease(),
                ),
            )
        }

        private fun candidate(
            pathId: io.warpnect.session.PathId,
            localAddress: String,
            remoteAddress: String,
            controlEndpoint: HandshakeTransportEndpoint,
        ) = SetupPathCandidate(
            pathId,
            NetworkPathKind.Direct,
            PathSocketBinding(pathId, NetworkPathKind.Direct, localAddress),
            remoteAddress,
            controlEndpoint,
        )

        private fun endpoint(address: String, port: Int): HandshakeTransportEndpoint = requireNotNull(
            HandshakeTransportEndpoint.from(java.net.InetAddress.getByName(address).address, port),
        )
    }

    private class TestDirectLease : DirectPathLease {
        override fun close() = Unit
    }

    private companion object {
        val PASS_VALIDATOR = ExactStreamConfigurationValidator { _, _, _ -> SessionSetupError.None }

        fun profile(eligiblePaths: Int = CapabilityBits.PATH_LAN) = NegotiatedCapabilityProfile(
            selectedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            eligiblePathKinds = eligiblePaths,
            secureDatagramBytes = 1_200,
            maxSessionChannels = 32,
            recoveryFlags = 0,
            videoCodec = NegotiatedCapabilityProfile.VIDEO_CODEC_AVC,
            videoFlags = CapabilityBits.VIDEO_LOW_LATENCY_DECODE,
            videoPayloadVersion = 1,
            videoMaxWidth = 1_920,
            videoMaxHeight = 1_080,
            videoMaxFps = 60,
            videoMaxBitrateBps = 20_000_000,
            audioCodec = NegotiatedCapabilityProfile.AUDIO_CODEC_NONE,
            audioFrameDurationMask = 0,
            audioPayloadVersion = 0,
            audioSampleRateMask = 0,
            systemAudioMaxChannels = 0,
            microphoneMaxChannels = 0,
            microphoneRoutingPolicy = MicrophoneRoutingSelection.NotApplicable,
            inputPayloadVersion = 1,
            inputKinds = CapabilityBits.INPUT_KEYBOARD,
            inputFeatureFlags = CapabilityBits.INPUT_STATE_CONVERGENCE,
            stablePresenceKinds = 0,
        )

        fun preferences() = SessionSetupPreferences(
            pathPreference = PathPreferencePolicy.LanOnly,
            secondaryPathPolicy = SecondaryPathPolicy.Disabled,
            video = VideoStreamPreference(
                VideoPreferencePolicy.Exact,
                listOf(VideoStreamMode(1_280, 720, 60, 8_000_000, CapabilityBits.VIDEO_LOW_LATENCY_DECODE)),
            ),
            input = InputStreamConfiguration(
                CapabilityBits.INPUT_KEYBOARD,
                0,
                CapabilityBits.INPUT_STATE_CONVERGENCE,
            ),
        )
    }
}
