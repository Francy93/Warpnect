package io.warpnect.session.setup

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.NetworkPathState
import io.warpnect.session.PathId
import io.warpnect.session.PathPreferencePolicy
import io.warpnect.session.SecondaryPathPolicy
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.handshake.HandshakeTransportEndpoint

/** Pure deterministic planning for RFC-005G. Platform code supplies only already validated route facts. */
object SessionSetupPlanner {
    fun selectedChannelKinds(selectedChannels: Int): List<SessionChannelKind>? {
        if (selectedChannels and CapabilityBits.OPTIONAL_CHANNEL_MASK.inv() != 0) return null
        return buildList {
            if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) add(SessionChannelKind.Video)
            if (selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0) add(SessionChannelKind.SystemAudio)
            if (selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0) {
                add(
                    SessionChannelKind.MicrophoneAudio,
                )
            }
            if (selectedChannels and CapabilityBits.CHANNEL_INPUT != 0) add(SessionChannelKind.Input)
            if (selectedChannels and CapabilityBits.CHANNEL_TELEMETRY != 0) add(SessionChannelKind.Telemetry)
        }.takeIf { it.isNotEmpty() }
    }

    fun selectPaths(
        profile: NegotiatedCapabilityProfile,
        preference: PathPreferencePolicy,
        secondaryPolicy: SecondaryPathPolicy,
        lan: SetupPathCandidate?,
        direct: SetupPathCandidate?,
        directAttemptFailure: PathFailureReason? = null,
    ): SetupPathSelectionResult {
        if (!profile.isValid()) return SetupPathSelectionResult.Failure(SessionSetupError.CapabilityProfileMismatch)
        if (lan != null && (lan.kind != NetworkPathKind.Lan || !lan.isValid()) ||
            direct != null && (direct.kind != NetworkPathKind.Direct || !direct.isValid())
        ) {
            return SetupPathSelectionResult.Failure(SessionSetupError.InvalidConfig)
        }
        val lanAllowed = profile.eligiblePathKinds and CapabilityBits.PATH_LAN != 0 && lan != null
        val directAllowed = profile.eligiblePathKinds and CapabilityBits.PATH_DIRECT != 0 && direct != null
        fun path(candidate: SetupPathCandidate, state: NetworkPathState) = SessionPathPlan(
            candidate.pathId,
            candidate.kind,
            state,
            candidate.binding.localAddress,
            candidate.remoteAddress,
        )
        return when (preference) {
            PathPreferencePolicy.DirectOnly -> if (directAllowed) {
                SetupPathSelectionResult.Success(
                    path(requireNotNull(direct), NetworkPathState.Active),
                    null,
                    directAttempted = true,
                )
            } else {
                SetupPathSelectionResult.Failure(
                    SessionSetupError.DirectUnavailable,
                    directAttempted = true,
                    directAttemptFailure,
                )
            }
            PathPreferencePolicy.LanOnly -> if (lanAllowed) {
                SetupPathSelectionResult.Success(
                    path(requireNotNull(lan), NetworkPathState.Active),
                    null,
                    directAttempted = false,
                )
            } else {
                SetupPathSelectionResult.Failure(SessionSetupError.NoUsablePath)
            }
            PathPreferencePolicy.PreferDirectThenLan -> when {
                directAllowed -> SetupPathSelectionResult.Success(
                    path(requireNotNull(direct), NetworkPathState.Active),
                    lan?.takeIf { secondaryPolicy == SecondaryPathPolicy.KeepValidatedStandby && lanAllowed }
                        ?.let { path(it, NetworkPathState.Standby) },
                    directAttempted = true,
                )
                lanAllowed -> SetupPathSelectionResult.Success(
                    path(requireNotNull(lan), NetworkPathState.Active),
                    null,
                    directAttempted = true,
                    directAttemptFailure = directAttemptFailure ?: PathFailureReason.DirectUnavailable,
                )
                else -> SetupPathSelectionResult.Failure(
                    SessionSetupError.NoUsablePath,
                    directAttempted = true,
                    directAttemptFailure = directAttemptFailure ?: PathFailureReason.DirectUnavailable,
                )
            }
            PathPreferencePolicy.PreferLan -> when {
                lanAllowed -> SetupPathSelectionResult.Success(
                    path(requireNotNull(lan), NetworkPathState.Active),
                    direct?.takeIf { secondaryPolicy == SecondaryPathPolicy.KeepValidatedStandby && directAllowed }
                        ?.let { path(it, NetworkPathState.Standby) },
                    directAttempted = secondaryPolicy == SecondaryPathPolicy.KeepValidatedStandby && direct != null,
                    directAttemptFailure = directAttemptFailure,
                )
                directAllowed -> SetupPathSelectionResult.Success(
                    path(requireNotNull(direct), NetworkPathState.Active),
                    null,
                    directAttempted = true,
                )
                else -> SetupPathSelectionResult.Failure(
                    SessionSetupError.NoUsablePath,
                    directAttempted = true,
                    directAttemptFailure,
                )
            }
        }
    }

    fun validateEndpointOffer(
        profile: NegotiatedCapabilityProfile,
        activePathKind: NetworkPathKind,
        offers: List<ChannelEndpointOffer>,
    ): SessionSetupError? {
        val expected = selectedChannelKinds(profile.selectedChannels) ?: return SessionSetupError.ChannelPlanInvalid
        if (!profile.isValid() || offers.size != expected.size || offers.any { !it.isValid() } ||
            offers.map(ChannelEndpointOffer::kind) != expected || activePathKind !in profilePathKinds(profile)
        ) {
            return SessionSetupError.EndpointMismatch
        }
        return null
    }

    fun allocateChannelIds(selectedChannels: Int): List<Pair<SessionChannelKind, ChannelId>>? {
        val kinds = selectedChannelKinds(selectedChannels) ?: return null
        return kinds.mapIndexed { index, kind -> kind to ChannelId.requireValid((index + 1).toUInt()) }
    }

    fun validateExactPreferences(
        profile: NegotiatedCapabilityProfile,
        preferences: SessionSetupPreferences,
    ): SessionSetupError? {
        if (!profile.isValid() || !preferences.isValidFor(profile)) return SessionSetupError.InvalidConfig
        preferences.video?.let { preference ->
            if (profile.videoCodec != NegotiatedCapabilityProfile.VIDEO_CODEC_AVC ||
                preference.modes.any { mode -> !videoWithinProfile(mode, profile) }
            ) {
                return SessionSetupError.ExactVideoConfigurationUnavailable
            }
        }
        preferences.systemAudio?.let { preference ->
            if (!audioPreferenceWithinProfile(preference, profile, profile.systemAudioMaxChannels)) {
                return SessionSetupError.ExactAudioConfigurationUnavailable
            }
        }
        preferences.microphoneAudio?.let { preference ->
            if (!audioPreferenceWithinProfile(preference, profile, profile.microphoneMaxChannels)) {
                return SessionSetupError.ExactAudioConfigurationUnavailable
            }
        }
        preferences.input?.let { input ->
            if (input.inputKinds != profile.inputKinds ||
                input.stablePresenceKinds != profile.stablePresenceKinds ||
                input.featureFlags != profile.inputFeatureFlags ||
                input.criticalCopies != 2 ||
                input.resetCopies != 3 ||
                input.networkReorderWaitUs != 0L ||
                input.transportDuplicateWindow != 64 ||
                input.semanticIdentityCache != 32
            ) {
                return SessionSetupError.InputConfigurationUnavailable
            }
        }
        return null
    }

    fun buildConfigurations(
        profile: NegotiatedCapabilityProfile,
        preferences: SessionSetupPreferences,
        descriptors: List<ChannelDescriptor>,
        recoveryPolicy: SessionRecoveryPolicy = SessionRecoveryPolicy(),
    ): SetupPlanResult {
        validateExactPreferences(profile, preferences)?.let { return SetupPlanResult.Failure(it) }
        val expectedKinds = selectedChannelKinds(profile.selectedChannels)
            ?: return SetupPlanResult.Failure(SessionSetupError.ChannelPlanInvalid)
        if (descriptors.map(ChannelDescriptor::kind) != expectedKinds || descriptors.any { !it.isValid() }) {
            return SetupPlanResult.Failure(SessionSetupError.ChannelPlanInvalid)
        }
        val byKind = descriptors.associateBy(ChannelDescriptor::kind)
        val configurations = buildList {
            preferences.video?.let {
                add(SetupConfiguration.Video(byKind.getValue(SessionChannelKind.Video).channelId, it.modes.first()))
            }
            preferences.systemAudio?.let {
                add(
                    SetupConfiguration.SystemAudio(
                        byKind.getValue(SessionChannelKind.SystemAudio).channelId,
                        it.modes.first(),
                    ),
                )
            }
            preferences.microphoneAudio?.let {
                add(
                    SetupConfiguration.MicrophoneAudio(
                        byKind.getValue(SessionChannelKind.MicrophoneAudio).channelId,
                        it.modes.first(),
                    ),
                )
            }
            preferences.input?.let {
                add(
                    SetupConfiguration.Input(byKind.getValue(SessionChannelKind.Input).channelId, it),
                )
            }
            if (SessionChannelKind.Telemetry in expectedKinds) {
                add(SetupConfiguration.Telemetry(byKind.getValue(SessionChannelKind.Telemetry).channelId))
            }
            descriptors.filter { it.recoveryFlags != 0 }.forEach { descriptor ->
                val configured = recoveryPolicy.forKind(descriptor.kind).bind(descriptor.channelId)
                if (!configured.isValid() || configured.recoveryFlags != descriptor.recoveryFlags) {
                    return SetupPlanResult.Failure(SessionSetupError.RecoveryConfigurationInvalid)
                }
                add(SetupConfiguration.Recovery(descriptor.channelId, configured))
            }
        }
        return if (configurations.size > SessionSetupProtocol.MAX_CONFIGURATION_TLVS) {
            SetupPlanResult.Failure(SessionSetupError.ChannelPlanInvalid)
        } else {
            SetupPlanResult.Success(configurations)
        }
    }

    fun validateProposal(
        profile: NegotiatedCapabilityProfile,
        clientOffer: SessionSetupMessage.ClientEndpointOffer,
        proposal: SessionSetupMessage.HostConfigurationProposal,
        expectedActivePath: SessionPathPlan,
    ): SessionSetupError? {
        if (!profile.isValid() || !proposal.profileHash.contentEquals(clientOffer.profileHash) ||
            proposal.activePathKind != expectedActivePath.kind || proposal.activePathId != expectedActivePath.pathId ||
            proposal.descriptors.size != clientOffer.endpoints.size ||
            proposal.descriptors.map(ChannelDescriptor::kind) != clientOffer.endpoints.map(ChannelEndpointOffer::kind)
        ) {
            return SessionSetupError.ChannelPlanInvalid
        }
        if (proposal.descriptors.any { descriptor ->
                descriptor.pathId != expectedActivePath.pathId || descriptor.clientLocalPort !=
                    clientOffer.endpoints.first { it.kind == descriptor.kind }.localPort ||
                    descriptor.maxSecureDatagramBytes != profile.secureDatagramBytes ||
                    descriptor.recoveryFlags and profile.recoveryFlags != descriptor.recoveryFlags
            }
        ) {
            return SessionSetupError.EndpointMismatch
        }
        val proposalPreferences = preferenceFromProposal(profile, proposal)
            ?: return SessionSetupError.ChannelPlanInvalid
        val recovery = recoveryPolicyFromProposal(proposal)
            ?: return SessionSetupError.RecoveryConfigurationInvalid
        val rebuild = buildConfigurations(profile, proposalPreferences, proposal.descriptors, recovery)
        return when (rebuild) {
            is SetupPlanResult.Failure -> rebuild.error
            is SetupPlanResult.Success -> if (rebuild.configurations == proposal.configurations) {
                null
            } else {
                SessionSetupError.ChannelPlanInvalid
            }
        }
    }

    private fun preferenceFromProposal(
        profile: NegotiatedCapabilityProfile,
        proposal: SessionSetupMessage.HostConfigurationProposal,
    ): SessionSetupPreferences? {
        fun configuration(kind: SessionChannelKind) = proposal.configurations.firstOrNull { config ->
            proposal.descriptors.firstOrNull { it.channelId == config.channelId }?.kind == kind
        }
        return SessionSetupPreferences(
            video = (configuration(SessionChannelKind.Video) as? SetupConfiguration.Video)?.let {
                VideoStreamPreference(VideoPreferencePolicy.Exact, listOf(it.mode))
            },
            systemAudio = (configuration(SessionChannelKind.SystemAudio) as? SetupConfiguration.SystemAudio)?.let {
                AudioStreamPreference(listOf(it.mode))
            },
            microphoneAudio = (
                configuration(
                    SessionChannelKind.MicrophoneAudio,
                ) as? SetupConfiguration.MicrophoneAudio
                )?.let {
                AudioStreamPreference(listOf(it.mode))
            },
            input = (configuration(SessionChannelKind.Input) as? SetupConfiguration.Input)?.config,
        ).takeIf { it.isValidFor(profile) }
    }

    private fun recoveryPolicyFromProposal(
        proposal: SessionSetupMessage.HostConfigurationProposal,
    ): SessionRecoveryPolicy? {
        fun template(kind: SessionChannelKind): RecoveryConfigurationTemplate {
            val descriptor = proposal.descriptors.firstOrNull { it.kind == kind }
                ?: return RecoveryConfigurationTemplate()
            val recovery = proposal.configurations.filterIsInstance<SetupConfiguration.Recovery>()
                .firstOrNull { it.channelId == descriptor.channelId }
                ?.config
                ?: return RecoveryConfigurationTemplate()
            return RecoveryConfigurationTemplate(
                recovery.recoveryFlags,
                recovery.fecDataShards,
                recovery.fecParityShards,
                recovery.retransmissionCacheSlots,
            )
        }
        val result = SessionRecoveryPolicy(
            video = template(SessionChannelKind.Video),
            systemAudio = template(SessionChannelKind.SystemAudio),
            microphoneAudio = template(SessionChannelKind.MicrophoneAudio),
            input = template(SessionChannelKind.Input),
            telemetry = template(SessionChannelKind.Telemetry),
        )
        return result.takeIf { policy ->
            proposal.descriptors.all { descriptor ->
                policy.forKind(descriptor.kind).recoveryFlags == descriptor.recoveryFlags
            }
        }
    }

    private fun profilePathKinds(profile: NegotiatedCapabilityProfile): Set<NetworkPathKind> = buildSet {
        if (profile.eligiblePathKinds and CapabilityBits.PATH_LAN != 0) add(NetworkPathKind.Lan)
        if (profile.eligiblePathKinds and CapabilityBits.PATH_DIRECT != 0) add(NetworkPathKind.Direct)
    }

    private fun videoWithinProfile(mode: VideoStreamMode, profile: NegotiatedCapabilityProfile): Boolean =
        mode.isValid() && mode.width <= profile.videoMaxWidth && mode.height <= profile.videoMaxHeight &&
            mode.fps <= profile.videoMaxFps && mode.bitrateBps <= profile.videoMaxBitrateBps &&
            mode.flags and profile.videoFlags == mode.flags

    private fun audioPreferenceWithinProfile(
        preference: AudioStreamPreference,
        profile: NegotiatedCapabilityProfile,
        maxChannels: Int,
    ): Boolean = profile.audioCodec == NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS &&
        preference.modes.all { mode -> audioWithinProfile(mode, profile, maxChannels) }

    private fun audioWithinProfile(
        mode: AudioStreamMode,
        profile: NegotiatedCapabilityProfile,
        maxChannels: Int,
    ): Boolean = mode.isValid() && mode.channelCount <= maxChannels &&
        profile.audioSampleRateMask and CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ != 0 &&
        profile.audioFrameDurationMask and frameMask(mode.frameDurationUs) != 0

    private fun frameMask(frameDurationUs: Int): Int = when (frameDurationUs) {
        2_500 -> CapabilityBits.AUDIO_FRAME_2_5_MS
        5_000 -> CapabilityBits.AUDIO_FRAME_5_MS
        10_000 -> CapabilityBits.AUDIO_FRAME_10_MS
        20_000 -> CapabilityBits.AUDIO_FRAME_20_MS
        else -> 0
    }
}

data class SetupPathCandidate(
    val pathId: PathId,
    val kind: NetworkPathKind,
    val binding: PathSocketBinding,
    val remoteAddress: String,
    val controlEndpoint: HandshakeTransportEndpoint? = null,
) {
    fun isValid(): Boolean =
        binding.isValid() && binding.pathId == pathId && binding.kind == kind && remoteAddress.isNotBlank()
}

sealed interface SetupPathSelectionResult {
    data class Success(
        val active: SessionPathPlan,
        val standby: SessionPathPlan?,
        val directAttempted: Boolean,
        val directAttemptFailure: PathFailureReason? = null,
    ) : SetupPathSelectionResult

    data class Failure(
        val error: SessionSetupError,
        val directAttempted: Boolean = false,
        val directAttemptFailure: PathFailureReason? = null,
    ) : SetupPathSelectionResult
}

sealed interface SetupPlanResult {
    data class Success(val configurations: List<SetupConfiguration>) : SetupPlanResult
    data class Failure(val error: SessionSetupError) : SetupPlanResult
}
