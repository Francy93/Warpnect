package io.warpnect.platform.session.lifecycle

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
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.integration.SecureSessionIntegrationError
import io.warpnect.session.integration.SessionPipelineComponent
import io.warpnect.session.integration.SessionPipelineComponentResult
import io.warpnect.session.integration.SessionPipelineRuntime
import io.warpnect.session.integration.SessionPipelineStartPhase
import io.warpnect.session.lifecycle.LifecyclePathBinding
import io.warpnect.session.lifecycle.PathMigrationEntry
import io.warpnect.session.lifecycle.PathMigrationId
import io.warpnect.session.lifecycle.SessionLifecycleError
import io.warpnect.session.security.ProtectionContextIds
import io.warpnect.session.security.SessionProtectionContextResult
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.session.security.SessionProtectionRuntime
import io.warpnect.session.security.SessionProtectionSnapshot
import io.warpnect.session.setup.ChannelDescriptor
import io.warpnect.session.setup.ChannelEndpointAllocationResult
import io.warpnect.session.setup.ChannelEndpointAllocator
import io.warpnect.session.setup.ChannelEndpointLease
import io.warpnect.session.setup.ChannelTransportPreparationRequest
import io.warpnect.session.setup.ChannelTransportPreparationResult
import io.warpnect.session.setup.ChannelTransportPreparer
import io.warpnect.session.setup.PathSocketBinding
import io.warpnect.session.setup.PreparedChannel
import io.warpnect.session.setup.PreparedChannelProtection
import io.warpnect.session.setup.PreparedChannelTransport
import io.warpnect.session.setup.PreparedSessionBootstrap
import io.warpnect.session.setup.SessionPathPlan
import io.warpnect.session.setup.SessionSetupError
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedSessionMigrationAdapterTest {
    @Test
    fun adoptedLiveChannelsRebindInPlaceWithoutPreparingReplacementTransports() {
        val video = channel(ChannelId.requireValid(1u), SessionChannelKind.Video, 41_000)
        val input = channel(ChannelId.requireValid(2u), SessionChannelKind.Input, 41_001)
        val runtime = TestProtectionRuntime()
        val control = TestControl()
        val bootstrap = PreparedSessionBootstrap(
            sessionId = SessionId.requireValid(0u, 1u),
            generation = SessionGeneration.Initial,
            localDeviceId = DeviceId.requireValid(0u, 2u),
            remoteDeviceId = DeviceId.requireValid(0u, 3u),
            localRole = SessionRole.Client,
            remoteRole = SessionRole.Host,
            profile = profile(),
            profileHash = ByteArray(32),
            activePath = SessionPathPlan(
                PathId.requireValid(1u),
                NetworkPathKind.Direct,
                NetworkPathState.Active,
                "10.0.0.2",
                "10.0.0.1",
            ),
            standbyPath = SessionPathPlan(
                PathId.requireValid(2u),
                NetworkPathKind.Lan,
                NetworkPathState.Standby,
                "192.168.1.2",
                "192.168.1.1",
            ),
            channels = listOf(video, input),
            secureSessionControl = control,
            protectionRuntime = runtime,
            admissionReservation = null,
            createdAtMonotonicMs = 0,
            expiresAtMonotonicMs = 30_000,
            preparedConfigurationHash = ByteArray(32) { 7 },
        )
        val originalVideoLease = video.localLease
        val originalInputLease = input.localLease
        val originalVideoTransport = video.transport
        val originalInputTransport = input.transport
        val originalVideoContext = video.protection.contextIds
        val originalInputContext = input.protection.contextIds
        var videoStarts = 0
        var inputStarts = 0
        var videoResyncs = 0
        val videoPipeline = component(
            "video",
            SessionChannelKind.Video,
            onStart = { videoStarts += 1 },
            onMigration = { videoResyncs += 1 },
        )
        val inputPipeline = component("input", SessionChannelKind.Input, onStart = { inputStarts += 1 })
        val pipeline = SessionPipelineRuntime(bootstrap, listOf(videoPipeline, inputPipeline))
        assertEquals(SecureSessionIntegrationError.None, pipeline.start())
        val rebound = mutableListOf<Pair<ChannelId, Int>>()
        video.adoptLiveTransport { lease, _, port ->
            rebound += video.descriptor.channelId to port
            lease.localPort == 45_000
        }
        input.adoptLiveTransport { lease, _, port ->
            rebound += input.descriptor.channelId to port
            lease.localPort == 45_001
        }

        val allocator = TestAllocator()
        val preparer = CountingPreparer()
        var committedControlPath: LifecyclePathBinding? = null
        val adapter = PreparedSessionMigrationAdapter(
            bootstrap,
            allocator,
            preparer,
            TestCandidateIo(),
        ) { binding ->
            committedControlPath = binding
            true
        }
        val target = LifecyclePathBinding(
            requireNotNull(bootstrap.standbyPath),
            endpoint("192.168.1.1", 44_444),
        )

        val preparation = requireNotNull(
            adapter.prepareChannels(target, listOf(SessionChannelKind.Video, SessionChannelKind.Input)),
        )
        val result = adapter.commit(
            target,
            preparation,
            listOf(
                PathMigrationEntry(video.descriptor.channelId, 46_000),
                PathMigrationEntry(input.descriptor.channelId, 46_001),
            ),
        )

        assertEquals(SessionLifecycleError.None, result)
        assertEquals(0, preparer.calls)
        assertEquals(
            listOf(video.descriptor.channelId to 46_000, input.descriptor.channelId to 46_001),
            rebound,
        )
        assertSame(originalVideoTransport, video.transport)
        assertSame(originalInputTransport, input.transport)
        assertEquals(originalVideoContext, video.protection.contextIds)
        assertEquals(originalInputContext, input.protection.contextIds)
        assertEquals(0, runtime.createdChannelContexts)
        assertEquals(2, runtime.reboundChannels.size)
        assertEquals(0, control.rebindCalls)
        assertEquals(target, committedControlPath)
        assertEquals(PathId.requireValid(2u), video.descriptor.pathId)
        assertEquals(PathId.requireValid(2u), input.descriptor.pathId)
        assertTrue((originalVideoLease as TestLease).closed)
        assertTrue((originalInputLease as TestLease).closed)
        assertFalse((video.localLease as TestLease).closed)
        assertFalse((input.localLease as TestLease).closed)
        pipeline.onPathMigrationCommitted()
        assertEquals(1, videoResyncs)
        assertEquals(1, videoStarts)
        assertEquals(1, inputStarts)
    }

    private fun component(
        name: String,
        kind: SessionChannelKind,
        onStart: () -> Unit = {},
        onMigration: () -> Unit = {},
    ): SessionPipelineComponent = object : SessionPipelineComponent {
        override val name = name
        override val phase = SessionPipelineStartPhase.InboundTransport
        override val channelKinds = setOf(kind)
        override fun start(): SessionPipelineComponentResult {
            onStart()
            return SessionPipelineComponentResult()
        }
        override fun stop() = Unit
        override fun onPathMigrationCommitted() = onMigration()
        override fun close() = Unit
    }

    private fun channel(id: ChannelId, kind: SessionChannelKind, clientPort: Int): PreparedChannel = PreparedChannel(
        ChannelDescriptor(
            id,
            kind,
            if (kind == SessionChannelKind.Video) {
                SessionChannelDirection.HostToClient
            } else {
                SessionChannelDirection.ClientToHost
            },
            0,
            PathId.requireValid(1u),
            clientPort - 1_000,
            clientPort,
            1_200,
            0,
        ),
        TestLease(PathSocketBinding(PathId.requireValid(1u), NetworkPathKind.Direct, "10.0.0.2"), clientPort, kind),
        "10.0.0.1",
        emptyList(),
        TestChannelProtection(id, ProtectionContextIds(id.value.toLong(), id.value.toLong() + 100)),
        TestTransport(),
    )

    private fun profile() = NegotiatedCapabilityProfile(
        selectedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
        eligiblePathKinds = CapabilityBits.PATH_DIRECT or CapabilityBits.PATH_LAN,
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

    private fun endpoint(address: String, port: Int): HandshakeTransportEndpoint = requireNotNull(
        HandshakeTransportEndpoint.from(InetAddress.getByName(address).address, port),
    )

    private class TestAllocator : ChannelEndpointAllocator {
        private var next = 45_000
        override fun allocate(
            binding: PathSocketBinding,
            channelKind: SessionChannelKind,
        ): ChannelEndpointAllocationResult =
            ChannelEndpointAllocationResult(SessionSetupError.None, TestLease(binding, next++, channelKind))
    }

    private class CountingPreparer : ChannelTransportPreparer {
        var calls = 0
        override fun prepare(request: ChannelTransportPreparationRequest): ChannelTransportPreparationResult {
            calls += 1
            return ChannelTransportPreparationResult(SessionSetupError.TransportPreparationFailed)
        }
    }

    private class TestCandidateIo : LifecycleCandidateDatagramIo {
        override fun arm(binding: LifecyclePathBinding, migrationId: PathMigrationId, timeoutMs: Long) = false
        override fun disarm(migrationId: PathMigrationId) = Unit
        override fun send(binding: LifecyclePathBinding, protectedDatagram: ByteArray) = false
    }

    private class TestLease(
        override val binding: PathSocketBinding,
        override val localPort: Int,
        override val channelKind: SessionChannelKind,
    ) : ChannelEndpointLease {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private class TestTransport : PreparedChannelTransport {
        override val protectedRequired = true
        override val started = false
        override fun close() = Unit
    }

    private class TestChannelProtection(
        override val channelId: ChannelId,
        override val contextIds: ProtectionContextIds,
    ) : PreparedChannelProtection {
        override fun close() = Unit
    }

    private class TestControl : SecureSessionControlTransport {
        override val maxPayloadBytes = 512
        var rebindCalls = 0
        override fun setPayloadListener(listener: ((ByteArray) -> Unit)?) = Unit
        override fun send(payload: ByteArray) = SecureSessionControlSendResult(SessionProtectionError.None, payload)
        override fun protectCandidate(payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload)
        override fun unprotectCandidate(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = SessionControlUnprotectResult(SessionProtectionError.None, protectedDatagram)
        override fun rebindRemoteEndpoint(endpoint: HandshakeTransportEndpoint): SessionProtectionError {
            rebindCalls += 1
            return SessionProtectionError.None
        }
        override fun close() = Unit
    }

    private class TestProtectionRuntime : SessionProtectionRuntime {
        override val sessionId = SessionId.requireValid(0u, 1u)
        override val sessionControlContext = ProtectionContextIds(10, 11)
        override val maxInnerSclDatagramSize = 1_156
        var createdChannelContexts = 0
        val reboundChannels = mutableListOf<ChannelId>()
        override fun createChannelContext(channelId: ChannelId): SessionProtectionContextResult {
            createdChannelContexts += 1
            return SessionProtectionContextResult(SessionProtectionError.None, ProtectionContextIds(20, 21))
        }
        override fun destroyChannelContext(channelId: ChannelId): SessionProtectionError = SessionProtectionError.None

        override fun rebindChannelEndpoint(
            channelId: ChannelId,
            endpoint: HandshakeTransportEndpoint,
        ): SessionProtectionError {
            reboundChannels += channelId
            return SessionProtectionError.None
        }
        override fun protectSessionControl(sequenceNumber: Long, timestampUs: Long, payload: ByteArray) =
            SecureSessionControlSendResult(SessionProtectionError.None, payload)
        override fun unprotectSessionControl(
            sourceEndpoint: HandshakeTransportEndpoint,
            protectedDatagram: ByteArray,
            nowUs: Long,
        ) = SessionControlUnprotectResult(SessionProtectionError.None, protectedDatagram)
        override fun snapshot() = SessionProtectionSnapshot(
            activeContexts = 0,
            protectedPackets = 0,
            decryptedPackets = 0,
            replayDrops = 0,
            tooOldDrops = 0,
            unknownContextDrops = 0,
            endpointFilterDrops = 0,
            authFailures = 0,
            keyUpdatesSent = 0,
            keyUpdatesAccepted = 0,
            currentSendEpoch = 0,
            currentReceiveEpoch = 0,
            lastError = SessionProtectionError.None,
        )
        override fun close() = Unit
    }
}
