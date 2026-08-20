package io.warpnect.session.setup

import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetupPlannerTest {
    @Test
    fun preferDirectThenLanUsesValidatedStandbyWithoutDuplicatingChannelPlan() {
        val selection = SessionSetupPlanner.selectPaths(
            profile(),
            PathPreferencePolicy.PreferDirectThenLan,
            SecondaryPathPolicy.KeepValidatedStandby,
            lan(),
            direct(),
        ) as SetupPathSelectionResult.Success
        assertEquals(NetworkPathKind.Direct, selection.active.kind)
        assertEquals(NetworkPathKind.Lan, selection.standby?.kind)
        assertTrue(selection.directAttempted)
    }

    @Test
    fun directOnlyFailsButExplicitDirectThenLanFallbackReportsFailure() {
        val directOnly = SessionSetupPlanner.selectPaths(
            profile(),
            PathPreferencePolicy.DirectOnly,
            SecondaryPathPolicy.Disabled,
            lan(),
            null,
        ) as SetupPathSelectionResult.Failure
        assertEquals(SessionSetupError.DirectUnavailable, directOnly.error)

        val fallback = SessionSetupPlanner.selectPaths(
            profile(),
            PathPreferencePolicy.PreferDirectThenLan,
            SecondaryPathPolicy.Disabled,
            lan(),
            null,
        ) as SetupPathSelectionResult.Success
        assertEquals(NetworkPathKind.Lan, fallback.active.kind)
        assertEquals(PathFailureReason.DirectUnavailable, fallback.directAttemptFailure)
    }

    @Test
    fun exactModesMustRemainBelowCapabilityCeilingsAndInputProfileMustMatch() {
        val valid = SessionSetupPlanner.validateExactPreferences(profile(), preferences())
        assertEquals(null, valid)
        val tooLarge = preferences().copy(
            video = VideoStreamPreference(
                VideoPreferencePolicy.Exact,
                listOf(VideoStreamMode(2560, 1440, 60, 8_000_000)),
            ),
        )
        assertEquals(
            SessionSetupError.ExactVideoConfigurationUnavailable,
            SessionSetupPlanner.validateExactPreferences(profile(), tooLarge),
        )
        val changedInput = preferences().copy(input = preferences().input!!.copy(criticalCopies = 1))
        assertEquals(
            SessionSetupError.InputConfigurationUnavailable,
            SessionSetupPlanner.validateExactPreferences(profile(), changedInput),
        )
    }

    @Test
    fun channelIdsUseCanonicalKindsAndEndpointOfferMustExactlyMatchProfile() {
        val ids = requireNotNull(SessionSetupPlanner.allocateChannelIds(profile().selectedChannels))
        assertEquals(listOf(1u, 2u), ids.map { it.second.value })
        assertEquals(
            null,
            SessionSetupPlanner.validateEndpointOffer(
                profile(),
                NetworkPathKind.Lan,
                listOf(
                    ChannelEndpointOffer(ids[0].first, 0, 41000),
                    ChannelEndpointOffer(ids[1].first, 0, 41001),
                ),
            ),
        )
        assertEquals(
            SessionSetupError.EndpointMismatch,
            SessionSetupPlanner.validateEndpointOffer(
                profile(),
                NetworkPathKind.Lan,
                listOf(ChannelEndpointOffer(ids[1].first, 0, 41001)),
            ),
        )
    }

    @Test
    fun audioChannelCeilingsAreValidatedPerDirection() {
        val audioProfile = profile().copy(
            selectedChannels = CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO,
            videoCodec = 0,
            videoFlags = 0,
            videoPayloadVersion = 0,
            videoMaxWidth = 0,
            videoMaxHeight = 0,
            videoMaxFps = 0,
            videoMaxBitrateBps = 0,
            audioCodec = NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS,
            audioFrameDurationMask = CapabilityBits.AUDIO_FRAME_5_MS,
            audioPayloadVersion = 1,
            audioSampleRateMask = CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ,
            systemAudioMaxChannels = 1,
            microphoneMaxChannels = 2,
            microphoneRoutingPolicy = MicrophoneRoutingSelection.SeparatePerPeer,
            inputPayloadVersion = 0,
            inputKinds = 0,
            inputFeatureFlags = 0,
        )
        val preferences = SessionSetupPreferences(
            systemAudio = AudioStreamPreference(listOf(AudioStreamMode(channelCount = 2))),
            microphoneAudio = AudioStreamPreference(listOf(AudioStreamMode(channelCount = 2))),
        )

        assertEquals(
            SessionSetupError.ExactAudioConfigurationUnavailable,
            SessionSetupPlanner.validateExactPreferences(audioProfile, preferences),
        )
    }

    @Test
    fun optionalPreferencesArePrunedAfterCapabilityNegotiationButSelectedChannelsRemainExact() {
        val systemAudio = AudioStreamPreference(
            listOf(AudioStreamMode(channelCount = 2, bitrateBps = 128_000)),
        )
        val requested = preferences().copy(systemAudio = systemAudio)

        val withoutSystemAudio = requested.retainOnlySelectedChannels(profile())
        assertNull(withoutSystemAudio.systemAudio)
        assertTrue(withoutSystemAudio.isValidFor(profile()))

        val withSystemAudio = profile().copy(
            selectedChannels = profile().selectedChannels or CapabilityBits.CHANNEL_SYSTEM_AUDIO,
            audioCodec = NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS,
            audioFrameDurationMask = CapabilityBits.AUDIO_FRAME_5_MS,
            audioPayloadVersion = 1,
            audioSampleRateMask = CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ,
            systemAudioMaxChannels = 2,
        )
        val retained = requested.retainOnlySelectedChannels(withSystemAudio)
        assertEquals(systemAudio, retained.systemAudio)
        assertTrue(retained.isValidFor(withSystemAudio))
    }

    private fun profile() = NegotiatedCapabilityProfile(
        selectedChannels = CapabilityBits.CHANNEL_VIDEO or CapabilityBits.CHANNEL_INPUT,
        eligiblePathKinds = CapabilityBits.PATH_LAN or CapabilityBits.PATH_DIRECT,
        secureDatagramBytes = 1200,
        maxSessionChannels = 32,
        recoveryFlags = CapabilityBits.RECOVERY_NACK,
        videoCodec = NegotiatedCapabilityProfile.VIDEO_CODEC_AVC,
        videoFlags = CapabilityBits.VIDEO_LOW_LATENCY_DECODE,
        videoPayloadVersion = 1,
        videoMaxWidth = 1920,
        videoMaxHeight = 1080,
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

    private fun preferences() = SessionSetupPreferences(
        video = VideoStreamPreference(
            VideoPreferencePolicy.Exact,
            listOf(VideoStreamMode(1280, 720, 60, 8_000_000, CapabilityBits.VIDEO_LOW_LATENCY_DECODE)),
        ),
        input = InputStreamConfiguration(CapabilityBits.INPUT_KEYBOARD, 0, CapabilityBits.INPUT_STATE_CONVERGENCE),
    )

    private fun lan() = SetupPathCandidate(
        PathId.requireValid(1u),
        NetworkPathKind.Lan,
        PathSocketBinding(PathId.requireValid(1u), NetworkPathKind.Lan, "192.168.1.2"),
        "192.168.1.3",
    )

    private fun direct() = SetupPathCandidate(
        PathId.requireValid(2u),
        NetworkPathKind.Direct,
        PathSocketBinding(PathId.requireValid(2u), NetworkPathKind.Direct, "192.168.49.1"),
        "192.168.49.2",
    )
}
