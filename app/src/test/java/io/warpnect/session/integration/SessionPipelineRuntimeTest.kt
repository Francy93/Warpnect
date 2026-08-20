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
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.control.SecureSessionControlSendResult
import io.warpnect.session.control.SecureSessionControlTransport
import io.warpnect.session.control.SessionControlUnprotectResult
import io.warpnect.session.handshake.HandshakeTransportEndpoint
import io.warpnect.session.lifecycle.LifecycleInputSafetyResetReason
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPipelineRuntimeTest {
    @Test
    fun startsSinksAndProcessorsBeforePhysicalSourcesForTheCommittedChannelPlan() {
        val events = mutableListOf<String>()
        val runtime = SessionPipelineRuntime(
            bootstrap(SessionChannelKind.Video, SessionChannelKind.Input),
            listOf(
                component(
                    "video-transport",
                    SessionPipelineStartPhase.OutboundProcessor,
                    setOf(SessionChannelKind.Video),
                    events,
                ),
                component(
                    "input-transport",
                    SessionPipelineStartPhase.OutboundProcessor,
                    setOf(SessionChannelKind.Input),
                    events,
                ),
                component(
                    "video-capture",
                    SessionPipelineStartPhase.PhysicalSource,
                    setOf(SessionChannelKind.Video),
                    events,
                ),
                component(
                    "input-capture",
                    SessionPipelineStartPhase.PhysicalSource,
                    setOf(SessionChannelKind.Input),
                    events,
                ),
            ),
        )

        assertEquals(SecureSessionIntegrationError.None, runtime.start())
        assertEquals(
            listOf("start:video-transport", "start:input-transport", "start:video-capture", "start:input-capture"),
            events,
        )
        assertEquals(SessionPipelineState.Running, runtime.snapshot().state)
    }

    @Test
    fun selectedComponentFailureRollsBackStartedComponentsAndNeverRunsLaterSources() {
        val events = mutableListOf<String>()
        val runtime = SessionPipelineRuntime(
            bootstrap(SessionChannelKind.Video, SessionChannelKind.SystemAudio),
            listOf(
                component(
                    "video-transport",
                    SessionPipelineStartPhase.OutboundProcessor,
                    setOf(SessionChannelKind.Video),
                    events,
                ),
                component(
                    "audio-transport",
                    SessionPipelineStartPhase.OutboundProcessor,
                    setOf(SessionChannelKind.SystemAudio),
                    events,
                    SecureSessionIntegrationError.SystemAudioStartFailed,
                ),
                component(
                    "video-capture",
                    SessionPipelineStartPhase.PhysicalSource,
                    setOf(SessionChannelKind.Video),
                    events,
                ),
            ),
        )

        assertEquals(SecureSessionIntegrationError.SystemAudioStartFailed, runtime.start())
        assertEquals(listOf("start:video-transport", "start:audio-transport", "stop:video-transport"), events)
        assertEquals(SessionPipelineState.Failed, runtime.snapshot().state)
    }

    @Test
    fun suspensionStopsSourcesAndInputSafetyUsesTheExistingInputComponentHookOnce() {
        val events = mutableListOf<String>()
        val input =
            component("input", SessionPipelineStartPhase.PhysicalSource, setOf(SessionChannelKind.Input), events)
        val runtime = SessionPipelineRuntime(bootstrap(SessionChannelKind.Input), listOf(input))
        assertEquals(SecureSessionIntegrationError.None, runtime.start())

        runtime.onSessionSuspended()
        runtime.onInputSafetyReset(LifecycleInputSafetyResetReason.PathUnavailable)
        runtime.onInputSafetyReset(LifecycleInputSafetyResetReason.SessionClosing)

        assertEquals(listOf("start:input", "stop:input", "suspended:input", "reset:input"), events)
        assertTrue(runtime.snapshot().startedComponents.none { it.started })
    }

    @Test
    fun rejectsComponentsThatDoNotCoverEveryCommittedChannel() {
        val runtime = SessionPipelineRuntime(
            bootstrap(SessionChannelKind.Video, SessionChannelKind.Input),
            listOf(
                component(
                    "video",
                    SessionPipelineStartPhase.OutboundProcessor,
                    setOf(SessionChannelKind.Video),
                    mutableListOf(),
                ),
            ),
        )

        assertEquals(SecureSessionIntegrationError.PipelinePlanInvalid, runtime.start())
        assertFalse(runtime.snapshot().state == SessionPipelineState.Running)
    }

    private fun component(
        name: String,
        phase: SessionPipelineStartPhase,
        channels: Set<SessionChannelKind>,
        events: MutableList<String>,
        failure: SecureSessionIntegrationError = SecureSessionIntegrationError.None,
    ): SessionPipelineComponent = object : SessionPipelineComponent {
        override val name: String = name
        override val phase: SessionPipelineStartPhase = phase
        override val channelKinds: Set<SessionChannelKind> = channels

        override fun start(): SessionPipelineComponentResult {
            events += "start:$name"
            return SessionPipelineComponentResult(failure)
        }

        override fun stop() {
            events += "stop:$name"
        }

        override fun onSessionSuspended() {
            events += "suspended:$name"
        }

        override fun onInputSafetyReset() {
            events += "reset:$name"
        }

        override fun close() = Unit
    }

    private fun bootstrap(vararg kinds: SessionChannelKind): PreparedSessionBootstrap {
        val runtime = TestRuntime()
        val channels = kinds.mapIndexed { index, kind ->
            val id = ChannelId.requireValid((index + 1).toUInt())
            PreparedChannel(
                ChannelDescriptor(
                    id,
                    kind,
                    if (kind in setOf(SessionChannelKind.Video, SessionChannelKind.SystemAudio)) {
                        SessionChannelDirection.HostToClient
                    } else {
                        SessionChannelDirection.ClientToHost
                    },
                    0,
                    PathId.requireValid(1u),
                    40_000 + index,
                    41_000 + index,
                    1_200,
                    0,
                ),
                TestLease(kind),
                "127.0.0.1",
                emptyList(),
                TestProtection(id),
                TestTransport(),
            )
        }
        return PreparedSessionBootstrap(
            SessionId.requireValid(0uL, 1uL),
            SessionGeneration.Initial,
            DeviceId.requireValid(0uL, 2uL),
            DeviceId.requireValid(0uL, 3uL),
            SessionRole.Client,
            SessionRole.Host,
            profile(kinds),
            ByteArray(32),
            SessionPathPlan(
                PathId.requireValid(1u),
                NetworkPathKind.Lan,
                NetworkPathState.Active,
                "127.0.0.1",
                "127.0.0.1",
            ),
            null,
            channels,
            TestControl(),
            runtime,
            null,
            0,
            30_000,
        )
    }

    private fun profile(kinds: Array<out SessionChannelKind>): NegotiatedCapabilityProfile {
        val mask = kinds.fold(0) { result, kind ->
            result or when (kind) {
                SessionChannelKind.Video -> CapabilityBits.CHANNEL_VIDEO
                SessionChannelKind.SystemAudio -> CapabilityBits.CHANNEL_SYSTEM_AUDIO
                SessionChannelKind.MicrophoneAudio -> CapabilityBits.CHANNEL_MICROPHONE_AUDIO
                SessionChannelKind.Input -> CapabilityBits.CHANNEL_INPUT
                SessionChannelKind.Telemetry -> CapabilityBits.CHANNEL_TELEMETRY
                SessionChannelKind.Control -> 0
            }
        }
        return NegotiatedCapabilityProfile(
            mask, CapabilityBits.PATH_LAN, 1_200, 32, 0,
            NegotiatedCapabilityProfile.VIDEO_CODEC_NONE, 0, 0, 0, 0, 0, 0,
            NegotiatedCapabilityProfile.AUDIO_CODEC_NONE, 0, 0, 0, 0, 0, MicrophoneRoutingSelection.NotApplicable,
            0, 0, 0, 0,
        )
    }

    private class TestLease(override val channelKind: SessionChannelKind) : ChannelEndpointLease {
        override val binding = PathSocketBinding(PathId.requireValid(1u), NetworkPathKind.Lan, "127.0.0.1")
        override val localPort: Int = 40_000
        override fun close() = Unit
    }

    private class TestProtection(override val channelId: ChannelId) : PreparedChannelProtection {
        override val contextIds = ProtectionContextIds(channelId.value.toLong(), channelId.value.toLong() + 10L)
        override fun close() = Unit
    }

    private class TestTransport : PreparedChannelTransport {
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

    private class TestRuntime : SessionProtectionRuntime {
        override val sessionId = SessionId.requireValid(0uL, 1uL)
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
}
