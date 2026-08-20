package io.warpnect.session.integration

import io.warpnect.session.ChannelId
import io.warpnect.session.DeviceId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.NetworkPathState
import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CapabilityRequest
import io.warpnect.session.capability.FeatureRequirement
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.capability.NegotiatedSessionBootstrap
import io.warpnect.session.capability.SecureSessionCapabilityBootstrap
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryDisplayAlias
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.handshake.AuthenticatedSessionBootstrap
import io.warpnect.session.handshake.AuthenticatedSessionRootSecret
import io.warpnect.session.handshake.ExpectedPeerConstraint
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.handshake.SessionHandshakeAttemptId
import io.warpnect.session.identity.ImmutableBytes
import io.warpnect.session.lifecycle.DisconnectReason
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionSnapshot
import io.warpnect.session.setup.ChannelDescriptor
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.PreparedChannelProtection
import io.warpnect.session.setup.PreparedChannelTransport
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionPathPlan
import io.warpnect.session.setup.SessionSetupPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureSessionCoordinatorTest {
    @Test
    fun trustedDiscoveredPeerReachesRunningThroughOrderedPhaseFiveHandoffWithoutManualEndpoints() {
        val events = mutableListOf<String>()
        val driver = TestPhaseDriver(events)
        val coordinator = SecureSessionCoordinator(
            SessionRole.Client,
            driver,
            RecordingPipelineFactory(events),
            RecordingLifecycleFactory(events),
        )

        assertEquals(SecureSessionIntegrationError.None, coordinator.startDiscovery().error)
        assertEquals(SecureSessionIntegrationError.None, coordinator.connect(request()).error)

        assertEquals(
            listOf("discovery", "005D", "005E", "005F", "005G", "005H", "start:video-processor", "start:video-source"),
            events,
        )
        assertEquals(SecureSessionCoordinatorState.Running, coordinator.snapshot.value.state)
        assertEquals(setOf(SessionChannelKind.Video), coordinator.snapshot.value.runningChannels)
        assertEquals(0, driver.pairingStarts)
        assertEquals(0, driver.legacyManualTransportStarts)
    }

    @Test
    fun untrustedPresenceRequiresExplicitPairingAndAlwaysStartsANewHandshakeAfterApproval() {
        val events = mutableListOf<String>()
        val driver = TestPhaseDriver(events, pairingRequired = true)
        val coordinator = SecureSessionCoordinator(
            SessionRole.Client,
            driver,
            RecordingPipelineFactory(events),
            RecordingLifecycleFactory(events),
        )

        coordinator.connect(request())
        assertEquals(SecureSessionCoordinatorState.PairingRequired, coordinator.snapshot.value.state)
        assertEquals(0, driver.pairingStarts)

        assertEquals(SecureSessionIntegrationError.None, coordinator.beginExplicitPairing().error)
        assertEquals(SecureSessionIntegrationError.None, coordinator.approvePairing().error)

        assertEquals(1, driver.pairingStarts)
        assertEquals(2, driver.connectionStarts)
        assertEquals(SecureSessionCoordinatorState.Running, coordinator.snapshot.value.state)
        assertTrue(events.containsAll(listOf("005C", "005D", "005E", "005F", "005G", "005H")))
    }

    @Test
    fun pipelineFailureRollsBackLifecycleAndNeverPublishesRunningOrFallsBack() {
        val events = mutableListOf<String>()
        val driver = TestPhaseDriver(events)
        val lifecycleFactory = RecordingLifecycleFactory(events)
        val coordinator = SecureSessionCoordinator(
            SessionRole.Client,
            driver,
            RecordingPipelineFactory(events, SecureSessionIntegrationError.VideoPipelineStartFailed),
            lifecycleFactory,
        )

        coordinator.connect(request())

        assertEquals(SecureSessionCoordinatorState.Failed, coordinator.snapshot.value.state)
        assertEquals(SecureSessionIntegrationError.VideoPipelineStartFailed, coordinator.snapshot.value.lastError)
        assertEquals(1, lifecycleFactory.last?.fatalDisconnects)
        assertEquals(1, lifecycleFactory.last?.closes)
        assertEquals(1, driver.cancelCalls)
        assertFalse(events.contains("legacy-manual"))
    }

    @Test
    fun hostRegistryKeepsRuntimesPerSessionAndRejectsASecondRuntimeAtItsConfiguredBound() {
        val registry = HostSessionRuntimeRegistry(maxRuntimes = 1)
        val first = RunningSessionRuntime(TestLifecycle(session(1u), SessionGeneration.Initial), emptyPipeline())
        val second = RunningSessionRuntime(TestLifecycle(session(2u), SessionGeneration.Initial), emptyPipeline())

        assertEquals(SecureSessionIntegrationError.None, registry.register(first))
        assertEquals(SecureSessionIntegrationError.RegistryCapacityExceeded, registry.register(second))
        assertEquals(1, registry.snapshotCount())
        assertNotNull(registry.remove(session(1u), SessionGeneration.Initial))
        assertEquals(SecureSessionIntegrationError.None, registry.register(second))
    }

    @Test
    fun hostPreparedBootstrapUsesTheSameLifecycleAndPipelineRunningTransaction() {
        val events = mutableListOf<String>()
        val registry = HostSessionRuntimeRegistry(maxRuntimes = 1)
        val coordinator = SecureSessionCoordinator(
            SessionRole.Host,
            TestPhaseDriver(events),
            RecordingPipelineFactory(events),
            RecordingLifecycleFactory(events),
            registry,
        )
        val bootstrap = preparedBootstrap(
            NegotiatedSessionBootstrap(
                session(44u),
                SessionGeneration.Initial,
                device(10u),
                device(11u),
                SessionRole.Host,
                SessionRole.Client,
                videoProfile(),
                ByteArray(32),
                TestProtectionRuntime(session(44u)),
                TestControl(),
                endpoint(),
            ),
        )

        assertEquals(SecureSessionIntegrationError.None, coordinator.acceptPreparedHostSession(bootstrap).error)
        assertEquals(SecureSessionCoordinatorState.Running, coordinator.snapshot.value.state)
        assertEquals(1, registry.snapshotCount())
        assertEquals(
            listOf("005H", "start:video-processor", "start:video-source"),
            events,
        )

        coordinator.disconnect()
        assertEquals(0, registry.snapshotCount())
        assertEquals(SecureSessionCoordinatorState.Discovering, coordinator.snapshot.value.state)
    }

    @Test
    fun hostPipelineFailureLeavesItsResponderAvailableForTheNextBoundedSession() {
        val events = mutableListOf<String>()
        val driver = TestPhaseDriver(events)
        val coordinator = SecureSessionCoordinator(
            SessionRole.Host,
            driver,
            RecordingPipelineFactory(events, SecureSessionIntegrationError.InputPipelineStartFailed),
            RecordingLifecycleFactory(events),
            HostSessionRuntimeRegistry(maxRuntimes = 1),
        )
        val bootstrap = preparedBootstrap(
            NegotiatedSessionBootstrap(
                session(45u),
                SessionGeneration.Initial,
                device(10u),
                device(11u),
                SessionRole.Host,
                SessionRole.Client,
                videoProfile(),
                ByteArray(32),
                TestProtectionRuntime(session(45u)),
                TestControl(),
                endpoint(),
            ),
        )

        coordinator.acceptPreparedHostSession(bootstrap)

        assertEquals(SecureSessionCoordinatorState.Discovering, coordinator.snapshot.value.state)
        assertEquals(SecureSessionIntegrationError.InputPipelineStartFailed, coordinator.snapshot.value.lastError)
        assertEquals(0, driver.cancelCalls)
    }

    private fun request() = SecureSessionConnectRequest(
        DiscoveredPresence(
            DiscoveryPresenceId.requireValid(0uL, 50uL),
            DiscoveryDisplayAlias.requireValid("Host"),
            SessionRole.Host,
            DiscoveryAvailability.Available,
            1,
            0,
            0,
            listOf(io.warpnect.session.discovery.DiscoveryRouteKind.Lan),
            DiscoveryPresenceStatus.Usable,
        ),
        CapabilityRequest(
            CapabilityBits.CHANNEL_VIDEO,
            0,
            CapabilityBits.OPTIONAL_CHANNEL_MASK xor CapabilityBits.CHANNEL_VIDEO,
            0,
            0,
            MicrophoneRoutingSelection.NotApplicable,
            MicrophoneRoutingSelection.NotApplicable,
            0,
            0,
            FeatureRequirement.Disabled,
            FeatureRequirement.Disabled,
            0,
        ),
        SessionSetupPreferences(),
        ExpectedPeerConstraint.AnyTrustedPeer,
    )

    private inner class TestPhaseDriver(
        private val events: MutableList<String>,
        private var pairingRequired: Boolean = false,
    ) : SecureSessionPhaseDriver {
        var connectionStarts = 0
        var pairingStarts = 0
        var cancelCalls = 0
        var legacyManualTransportStarts = 0
        private var listener: SecureSessionPhaseListener? = null

        override fun startDiscovery(role: SessionRole): SecureSessionIntegrationError {
            events += "discovery"
            return SecureSessionIntegrationError.None
        }

        override fun stopDiscovery() = Unit

        override fun beginConnection(
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            connectionStarts += 1
            this.listener = listener
            events += "005D"
            if (pairingRequired) {
                pairingRequired = false
                listener.onPairingRequired()
            } else {
                listener.onAuthenticated(authenticatedBootstrap())
            }
            return SecureSessionIntegrationError.None
        }

        override fun beginExplicitPairing(
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            pairingStarts += 1
            this.listener = listener
            events += "005C"
            return SecureSessionIntegrationError.None
        }

        override fun approvePairing(): SecureSessionIntegrationError {
            listener?.onPairingCompleted()
            return SecureSessionIntegrationError.None
        }

        override fun createSecureCapabilityBootstrap(bootstrap: AuthenticatedSessionBootstrap): SecurePhaseResult {
            events += "005E"
            return SecurePhaseResult(
                SecureSessionIntegrationError.None,
                SecureSessionCapabilityBootstrap(
                    bootstrap.sessionId,
                    bootstrap.generation,
                    bootstrap.localDeviceId,
                    bootstrap.remoteDeviceId,
                    bootstrap.localRole,
                    bootstrap.remoteRole,
                    bootstrap.endpoint,
                    TestProtectionRuntime(bootstrap.sessionId),
                ),
            )
        }

        override fun beginCapabilities(
            bootstrap: SecureSessionCapabilityBootstrap,
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            events += "005F"
            listener.onCapabilitiesNegotiated(
                NegotiatedSessionBootstrap(
                    bootstrap.sessionId,
                    bootstrap.generation,
                    bootstrap.localDeviceId,
                    bootstrap.remoteDeviceId,
                    bootstrap.localRole,
                    bootstrap.remoteRole,
                    videoProfile(),
                    ByteArray(32) { 1 },
                    bootstrap.protection,
                    TestControl(),
                    bootstrap.endpoint,
                ),
            )
            return SecureSessionIntegrationError.None
        }

        override fun beginSetup(
            bootstrap: NegotiatedSessionBootstrap,
            request: SecureSessionConnectRequest,
            listener: SecureSessionPhaseListener,
        ): SecureSessionIntegrationError {
            events += "005G"
            listener.onPrepared(preparedBootstrap(bootstrap))
            return SecureSessionIntegrationError.None
        }

        override fun cancel() {
            cancelCalls += 1
        }

        override fun refreshHostAvailability() = Unit
        override fun close() = Unit
    }

    private inner class RecordingPipelineFactory(
        private val events: MutableList<String>,
        private val videoFailure: SecureSessionIntegrationError = SecureSessionIntegrationError.None,
    ) : SessionPipelineFactory {
        override fun create(bootstrap: PreparedSessionBootstrap): SessionPipelineFactoryResult =
            SessionPipelineFactoryResult(
                SecureSessionIntegrationError.None,
                listOf(
                    component("video-processor", SessionPipelineStartPhase.OutboundProcessor, events, videoFailure),
                    component("video-source", SessionPipelineStartPhase.PhysicalSource, events),
                ),
            )
    }

    private class RecordingLifecycleFactory(
        private val events: MutableList<String>,
    ) : SessionLifecycleSessionFactory {
        var last: TestLifecycle? = null

        override fun create(
            bootstrap: PreparedSessionBootstrap,
            pipeline: SessionPipelineRuntime,
            listener: SessionLifecycleRuntimeListener,
        ): SessionLifecycleFactoryResult {
            val lifecycle = TestLifecycle(bootstrap.sessionId, bootstrap.generation, events)
            last = lifecycle
            return SessionLifecycleFactoryResult(SecureSessionIntegrationError.None, lifecycle)
        }
    }

    private class TestLifecycle(
        override val sessionId: SessionId,
        override val generation: SessionGeneration,
        private val events: MutableList<String> = mutableListOf(),
    ) : ManagedLifecycleSession {
        override val activePathKind: NetworkPathKind? = NetworkPathKind.Lan
        var fatalDisconnects = 0
        var closes = 0

        override fun start(): SecureSessionIntegrationError {
            events += "005H"
            return SecureSessionIntegrationError.None
        }

        override fun gracefulDisconnect(reason: DisconnectReason) {
            if (reason == DisconnectReason.FatalError) fatalDisconnects += 1
        }

        override fun close() {
            closes += 1
        }
    }

    private fun component(
        name: String,
        phase: SessionPipelineStartPhase,
        events: MutableList<String>,
        failure: SecureSessionIntegrationError = SecureSessionIntegrationError.None,
    ): SessionPipelineComponent = object : SessionPipelineComponent {
        override val name = name
        override val phase = phase
        override val channelKinds = setOf(SessionChannelKind.Video)
        override fun start(): SessionPipelineComponentResult {
            events += "start:$name"
            return SessionPipelineComponentResult(failure)
        }
        override fun stop() {
            events += "stop:$name"
        }
        override fun close() = Unit
    }

    private fun authenticatedBootstrap(): AuthenticatedSessionBootstrap = AuthenticatedSessionBootstrap(
        session(1u), SessionGeneration.Initial, device(10u), device(11u), SessionRole.Client, SessionRole.Host,
        SessionHandshakeAttemptId.requireValid(0uL, 1uL), ImmutableBytes.copyOf(ByteArray(32)), endpoint(),
        AuthenticatedSessionRootSecret(ByteArray(32) { 8 }),
    )

    private fun preparedBootstrap(negotiated: NegotiatedSessionBootstrap): PreparedSessionBootstrap {
        val channelId = ChannelId.requireValid(1u)
        return PreparedSessionBootstrap(
            negotiated.sessionId, negotiated.generation, negotiated.localDeviceId, negotiated.remoteDeviceId,
            negotiated.localRole, negotiated.remoteRole, negotiated.profile, negotiated.profileHash,
            SessionPathPlan(
                PathId.requireValid(1u),
                NetworkPathKind.Lan,
                NetworkPathState.Active,
                "127.0.0.1",
                "127.0.0.1",
            ),
            null,
            listOf(
                PreparedChannel(
                    ChannelDescriptor(
                        channelId, SessionChannelKind.Video, SessionChannelDirection.HostToClient, 0,
                        PathId.requireValid(
                            1u,
                        ),
                        40_000, 40_001, 1_200, 0,
                    ),
                    TestLease(),
                    "127.0.0.1",
                    emptyList(),
                    TestPreparedProtection(channelId),
                    TestPreparedTransport(),
                ),
            ),
            negotiated.secureSessionControl, negotiated.protection, null, 0, 30_000,
        )
    }

    private fun emptyPipeline() = SessionPipelineRuntime(preparedBootstrapForRuntime(), emptyList())

    private fun preparedBootstrapForRuntime(): PreparedSessionBootstrap = preparedBootstrap(
        NegotiatedSessionBootstrap(
            session(
                99u,
            ),
            SessionGeneration.Initial,
            device(
                1u,
            ),
            device(
                2u,
            ),
            SessionRole.Host, SessionRole.Client, videoProfile(),
            ByteArray(
                32,
            ),
            TestProtectionRuntime(session(99u)), TestControl(),
            endpoint(),
        ),
    )

    private class TestLease : ChannelEndpointLease {
        override val binding = PathSocketBinding(PathId.requireValid(1u), NetworkPathKind.Lan, "127.0.0.1")
        override val localPort = 40_000
        override val channelKind = SessionChannelKind.Video
        override fun close() = Unit
    }

    private class TestPreparedProtection(override val channelId: ChannelId) : PreparedChannelProtection {
        override val contextIds = ProtectionContextIds(10, 11)
        override fun close() = Unit
    }

    private class TestPreparedTransport : PreparedChannelTransport {
        override val protectedRequired = true
        override val started = false
        override fun close() = Unit
    }

    private class TestControl : SecureSessionControlTransport {
        override val maxPayloadBytes = 1_000
        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) = Unit
        override fun send(payload: ByteArray) = SecureSessionControlSendResult(SessionProtectionError.None, payload)
        override fun close() = Unit
    }

    private class TestProtectionRuntime(override val sessionId: SessionId) : SessionProtectionRuntime {
        override val sessionControlContext = ProtectionContextIds(1, 2)
        override val maxInnerSclDatagramSize = 1_156
        override fun createChannelContext(channelId: ChannelId) =
            SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(3, 4))
        override fun destroyChannelContext(channelId: ChannelId) = SessionProtectionError.None
        override fun protectSessionControl(sequenceNumber: Long, timestampUs: Long, payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload)
        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = SessionControlUnprotectResult(SessionProtectionError.None, protectedDatagram)
        override fun snapshot() =
            SessionProtectionSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SessionProtectionError.None)
        override fun close() = Unit
    }

    private companion object {
        fun session(value: ULong) = SessionId.requireValid(0uL, value)
        fun device(value: ULong) = DeviceId.requireValid(0uL, value)
        fun endpoint() = HandshakeTransportEndpoint.requireValid(byteArrayOf(127, 0, 0, 1), 42_000)
        fun videoProfile() = NegotiatedCapabilityProfile(
            CapabilityBits.CHANNEL_VIDEO, CapabilityBits.PATH_LAN, 1_200, 32, 0,
            NegotiatedCapabilityProfile.VIDEO_CODEC_AVC, 0, 1, 1_280, 720, 60, 8_000_000,
            NegotiatedCapabilityProfile.AUDIO_CODEC_NONE, 0, 0, 0, 0, 0, MicrophoneRoutingSelection.NotApplicable,
            0, 0, 0, 0,
        )
    }
}
