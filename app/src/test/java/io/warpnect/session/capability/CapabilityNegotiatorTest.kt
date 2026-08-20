@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import io.warpnect.session.SessionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityNegotiatorTest {
    @Test
    fun deterministicIntersectionBuildsAnExplicitProfile() {
        val result = negotiate()
        assertTrue(result.isSuccess)
        val profile = requireNotNull(result.profile)
        assertEquals(CapabilityBits.PATH_LAN, profile.eligiblePathKinds)
        assertEquals(1_200, profile.secureDatagramBytes)
        assertTrue(profile.selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0)
        assertTrue(profile.selectedChannels and CapabilityBits.CHANNEL_INPUT != 0)
        assertEquals(1_920, profile.videoMaxWidth)
        assertEquals(60, profile.videoMaxFps)
        assertEquals(20_000_000L, profile.videoMaxBitrateBps)
        assertEquals(
            CapabilityNegotiationError.None,
            CapabilityNegotiator.validateClientSelection(client(), request(), host(), profile),
        )
    }

    @Test
    fun productionLikePolicySelectsPreferredSystemAudioWhenTheHostAndClientRolesSupportIt() {
        val request = request(
            required = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
            preferred = CapabilityBits.CHANNEL_SYSTEM_AUDIO,
            disabled = CapabilityBits.CHANNEL_MICROPHONE_AUDIO or CapabilityBits.CHANNEL_TELEMETRY,
        )
        val policy = HostCapabilityPolicy(
            allowedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_SYSTEM_AUDIO or
                CapabilityBits.CHANNEL_INPUT,
            mandatoryChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
        )

        val result = CapabilityNegotiator.negotiate(client(), request, host(), policy)

        assertTrue(result.isSuccess)
        val profile = requireNotNull(result.profile)
        assertTrue(profile.selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0)
        assertEquals(2, profile.systemAudioMaxChannels)
        assertEquals(NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS, profile.audioCodec)
    }

    @Test
    fun directDiscoveryCannotOverclaimADataPath() {
        val host = host().copy(paths = PathCapabilities(CapabilityBits.PATH_LAN, CapabilityBits.PATH_LAN, 1, 0))
        val client = client().copy(
            paths = PathCapabilities(CapabilityBits.PATH_DIRECT, CapabilityBits.PATH_DIRECT, 1, 0),
        )
        assertEquals(
            CapabilityNegotiationError.NoEligiblePath,
            CapabilityNegotiator.negotiate(client, request(), host, HostCapabilityPolicy()).error,
        )
    }

    @Test
    fun requiredHardwareVideoRejectsWithoutHardwareDecoder() {
        val missingDecoder = client().copy(video = client(videoHardware = false).video)
        assertEquals(
            CapabilityNegotiationError.RequiredChannelUnavailable,
            CapabilityNegotiator.negotiate(missingDecoder, request(), host(), HostCapabilityPolicy()).error,
        )
    }

    @Test
    fun preferredVideoMayBeOmittedButDisabledVideoNeverAppears() {
        val preferred = request(
            required = CapabilityBits.CHANNEL_INPUT,
            preferred = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_TELEMETRY,
            disabled = CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO,
        )
        val unavailable = client().copy(video = client(videoHardware = false).video)
        val preferredResult = CapabilityNegotiator.negotiate(unavailable, preferred, host(), HostCapabilityPolicy())
        assertTrue(preferredResult.isSuccess)
        assertEquals(0, requireNotNull(preferredResult.profile).selectedChannels and CapabilityBits.CHANNEL_VIDEO)

        val disabled = request(
            required = CapabilityBits.CHANNEL_INPUT,
            preferred =
            CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO or
                CapabilityBits.CHANNEL_TELEMETRY,
            disabled = CapabilityBits.CHANNEL_VIDEO,
        )
        val disabledResult = CapabilityNegotiator.negotiate(client(), disabled, host(), HostCapabilityPolicy())
        assertTrue(disabledResult.isSuccess)
        assertEquals(0, requireNotNull(disabledResult.profile).selectedChannels and CapabilityBits.CHANNEL_VIDEO)
    }

    @Test
    fun mixRequiresAnExplicitSupportedFallback() {
        val mixRequired = request(
            required = CapabilityBits.CHANNEL_MICROPHONE_AUDIO,
            preferred =
            CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_SYSTEM_AUDIO or
                CapabilityBits.CHANNEL_INPUT or CapabilityBits.CHANNEL_TELEMETRY,
            microphonePrimary = MicrophoneRoutingSelection.MixToSingleHostStream,
            microphoneFallback = MicrophoneRoutingSelection.NotApplicable,
        )
        assertEquals(CapabilityNegotiationError.RequiredBehaviorUnavailable, negotiate(mixRequired).error)

        val fallback = mixRequired.copy(microphonePolicyFallback = MicrophoneRoutingSelection.SeparatePerPeer)
        val result = negotiate(fallback)
        assertTrue(result.isSuccess)
        assertEquals(MicrophoneRoutingSelection.SeparatePerPeer, requireNotNull(result.profile).microphoneRoutingPolicy)
    }

    @Test
    fun gamepadIdentityAndStablePresenceNeedARealTargetBackend() {
        val distinctRequired = request(distinctGamepad = FeatureRequirement.Required)
        assertEquals(CapabilityNegotiationError.RequiredBehaviorUnavailable, negotiate(distinctRequired).error)

        val stableRequired = request(stableRequired = CapabilityBits.INPUT_GAMEPAD)
        assertEquals(CapabilityNegotiationError.RequiredBehaviorUnavailable, negotiate(stableRequired).error)

        val capableHost = host(
            stableKinds = CapabilityBits.INPUT_GAMEPAD,
            hostInputFlags = CapabilityBits.INPUT_PRIVILEGED_INJECTION or CapabilityBits.INPUT_STATE_CONVERGENCE or
                CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD or CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY,
        )
        val capableRequest = request(
            stableRequired = CapabilityBits.INPUT_GAMEPAD,
            distinctGamepad = FeatureRequirement.Required,
        )
        val result = CapabilityNegotiator.negotiate(client(), capableRequest, capableHost, HostCapabilityPolicy())
        assertTrue(result.isSuccess)
        val profile = requireNotNull(result.profile)
        assertTrue(profile.stablePresenceKinds and CapabilityBits.INPUT_GAMEPAD != 0)
        assertTrue(profile.inputFeatureFlags and CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD != 0)
        assertTrue(profile.inputFeatureFlags and CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY != 0)
    }

    @Test
    fun inputAndRecoveryRequirementsFailClosed() {
        val gamepadRequired = request(requiredInput = CapabilityBits.INPUT_GAMEPAD)
        val hostWithoutGamepad = host().copy(input = host().input.copy(injectionKinds = CapabilityBits.INPUT_KEYBOARD))
        assertEquals(
            CapabilityNegotiationError.RequiredInputKindUnavailable,
            CapabilityNegotiator.negotiate(client(), gamepadRequired, hostWithoutGamepad, HostCapabilityPolicy()).error,
        )

        val recoveryRequired = request().copy(requiredRecoveryFlags = CapabilityBits.RECOVERY_VIDEO_RESYNC)
        val hostWithoutResync = host().copy(core = host().core.copy(recoveryFlags = CapabilityBits.RECOVERY_NACK))
        assertEquals(
            CapabilityNegotiationError.RequiredRecoveryFeatureUnavailable,
            CapabilityNegotiator.negotiate(client(), recoveryRequired, hostWithoutResync, HostCapabilityPolicy()).error,
        )
    }

    @Test
    fun tamperedHostProfileIsNotRepairedByClient() {
        val profile = requireNotNull(negotiate().profile)
        val tampered = profile.copy(selectedChannels = profile.selectedChannels and CapabilityBits.CHANNEL_VIDEO.inv())
        assertFalse(tampered.selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0)
        assertEquals(
            CapabilityNegotiationError.SelectionInvalid,
            CapabilityNegotiator.validateClientSelection(client(), request(), host(), tampered),
        )
    }

    @Test
    fun clientRejectsUnsupportedDirectionalFlagsAndChannelBounds() {
        val profile = requireNotNull(negotiate().profile)
        val unsupportedLowLatency = profile.copy(
            videoFlags = profile.videoFlags or CapabilityBits.VIDEO_LOW_LATENCY_DECODE,
        )
        val clientWithoutLowLatency = client().copy(
            video = client().video.copy(
                videoFlags = client().video.videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE.inv(),
            ),
        )
        assertEquals(
            CapabilityNegotiationError.SelectionInvalid,
            CapabilityNegotiator.validateClientSelection(
                clientWithoutLowLatency,
                request(videoLowLatency = FeatureRequirement.Required),
                host(),
                unsupportedLowLatency,
            ),
        )
        assertEquals(
            CapabilityNegotiationError.RequiredBehaviorUnavailable,
            CapabilityNegotiator.negotiate(
                clientWithoutLowLatency,
                request(videoLowLatency = FeatureRequirement.Required),
                host(),
                HostCapabilityPolicy(),
            ).error,
        )
        val zeroSystemAudioChannels = profile.copy(systemAudioMaxChannels = 0)
        assertFalse(zeroSystemAudioChannels.isValid())
    }

    private fun negotiate(capabilityRequest: CapabilityRequest = request()): CapabilityNegotiationResult =
        CapabilityNegotiator.negotiate(client(), capabilityRequest, host(), HostCapabilityPolicy())

    private fun client(videoHardware: Boolean = true) = CapabilityNegotiationCodecTest.snapshot(
        SessionRole.Client,
        videoHardware = videoHardware,
    )

    private fun host(
        stableKinds: Int = 0,
        hostInputFlags: Int = CapabilityBits.INPUT_PRIVILEGED_INJECTION or CapabilityBits.INPUT_STATE_CONVERGENCE,
    ) = CapabilityNegotiationCodecTest.snapshot(
        SessionRole.Host,
        stableKinds = stableKinds,
        hostInputFlags = hostInputFlags,
    )

    private fun request(
        required: Int = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
        preferred: Int =
            CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO or
                CapabilityBits.CHANNEL_TELEMETRY,
        disabled: Int = 0,
        microphonePrimary: MicrophoneRoutingSelection = MicrophoneRoutingSelection.SeparatePerPeer,
        microphoneFallback: MicrophoneRoutingSelection = MicrophoneRoutingSelection.NotApplicable,
        videoLowLatency: FeatureRequirement = FeatureRequirement.Preferred,
        requiredInput: Int = CapabilityBits.INPUT_KEYBOARD,
        stableRequired: Int = 0,
        distinctGamepad: FeatureRequirement = FeatureRequirement.Disabled,
    ) = CapabilityNegotiationCodecTest.request(
        required, preferred, disabled, microphonePrimary, microphoneFallback, requiredInput,
        CapabilityBits.INPUT_MOUSE, stableRequired, 0, videoLowLatency, distinctGamepad,
    )
}
