@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import io.warpnect.session.SessionRole
import io.warpnect.session.security.SessionProtectionProtocol

/** Pure, deterministic intersection of frozen client/host snapshots and explicit host policy. */
object CapabilityNegotiator {
    /** Client-side semantic validation of an authenticated HostSelection. It never repairs a selection. */
    fun validateClientSelection(
        client: LocalCapabilitySnapshot,
        request: CapabilityRequest,
        host: LocalCapabilitySnapshot,
        profile: NegotiatedCapabilityProfile,
    ): CapabilityNegotiationError {
        if (!client.isValid() || !host.isValid() || !request.isValid() || !profile.isValid()) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (client.role != SessionRole.Client || host.role != SessionRole.Host) return CapabilityNegotiationError.RoleMismatch
        val expectedDatagram = minOf(client.core.maxSecureDatagramBytes, host.core.maxSecureDatagramBytes)
        if (profile.secureDatagramBytes != expectedDatagram ||
            profile.maxSessionChannels != minOf(client.core.maxSessionChannels, host.core.maxSessionChannels) ||
            profile.eligiblePathKinds and (client.paths.availablePathKinds and host.paths.availablePathKinds) != profile.eligiblePathKinds ||
            profile.eligiblePathKinds == 0
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (profile.selectedChannels and request.disabledChannels != 0 ||
            request.requiredChannels and profile.selectedChannels != request.requiredChannels ||
            profile.recoveryFlags and (client.core.recoveryFlags and host.core.recoveryFlags) != profile.recoveryFlags ||
            request.requiredRecoveryFlags and profile.recoveryFlags != request.requiredRecoveryFlags
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        val videoSelected = profile.selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0
        if (videoSelected && (
                !supportsVideo(client.video, host.video) ||
                    profile.videoFlags and (client.video.videoFlags or host.video.videoFlags).inv() != 0 ||
                    profile.videoMaxWidth != minOf(client.video.maxWidth, host.video.maxWidth) ||
                    profile.videoMaxHeight != minOf(client.video.maxHeight, host.video.maxHeight) ||
                    profile.videoMaxFps != minOf(client.video.maxFps, host.video.maxFps) ||
                    profile.videoMaxBitrateBps != minOf(client.video.maxBitrateBps, host.video.maxBitrateBps)
                )
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (request.requires(CapabilityBits.CHANNEL_VIDEO) && !videoSelected) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (request.videoLowLatencyRequirement == FeatureRequirement.Required &&
            (
                profile.videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE == 0 ||
                    client.video.videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE == 0
                )
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (request.videoLowLatencyRequirement == FeatureRequirement.Disabled &&
            profile.videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE != 0
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        val systemAudioSelected = profile.selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0
        val microphoneSelected = profile.selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0
        if ((systemAudioSelected && !supportsSystemAudio(client.audio, host.audio)) ||
            (microphoneSelected && !supportsMicrophone(client.audio, host.audio)) ||
            (request.requires(CapabilityBits.CHANNEL_SYSTEM_AUDIO) && !systemAudioSelected) ||
            (request.requires(CapabilityBits.CHANNEL_MICROPHONE_AUDIO) && !microphoneSelected)
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (systemAudioSelected || microphoneSelected) {
            if (profile.audioFrameDurationMask != (client.audio.frameDurationMask and host.audio.frameDurationMask) ||
                profile.audioSampleRateMask != (client.audio.sampleRateMask and host.audio.sampleRateMask) ||
                (
                    systemAudioSelected && profile.systemAudioMaxChannels !=
                        minOf(host.audio.maxSendChannels, client.audio.maxReceiveChannels)
                    ) ||
                (
                    microphoneSelected && profile.microphoneMaxChannels !=
                        minOf(client.audio.maxSendChannels, host.audio.maxReceiveChannels)
                    )
            ) {
                return CapabilityNegotiationError.SelectionInvalid
            }
        }
        if (microphoneSelected &&
            profile.microphoneRoutingPolicy !in listOf(request.microphonePolicyPrimary, request.microphonePolicyFallback)
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        val inputSelected = profile.selectedChannels and CapabilityBits.CHANNEL_INPUT != 0
        val inputIntersection = client.input.captureKinds and host.input.injectionKinds
        val allowedInputFlags =
            (
                client.input.inputFlags and host.input.inputFlags and
                    (
                        CapabilityBits.INPUT_RELATIVE_POINTER or CapabilityBits.INPUT_MULTI_TOUCH or
                            CapabilityBits.INPUT_STATE_CONVERGENCE
                        )
                ) or
                (
                    host.input.inputFlags and
                        (
                            CapabilityBits.INPUT_PRIVILEGED_INJECTION or CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY or
                                CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD
                            )
                    )
        if ((
                inputSelected && (
                    profile.inputKinds and inputIntersection != profile.inputKinds ||
                        profile.inputFeatureFlags and allowedInputFlags.inv() != 0 ||
                        request.requiredInputKinds and profile.inputKinds != request.requiredInputKinds
                    )
                ) ||
            (request.requires(CapabilityBits.CHANNEL_INPUT) && !inputSelected)
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        if (request.distinctGamepadIdentityRequirement == FeatureRequirement.Required &&
            profile.inputFeatureFlags and CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY == 0
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        val stableAvailable = host.behavior.stablePresenceKinds and inputIntersection
        if (profile.stablePresenceKinds and stableAvailable != profile.stablePresenceKinds ||
            request.stablePresenceRequiredKinds and profile.stablePresenceKinds != request.stablePresenceRequiredKinds
        ) {
            return CapabilityNegotiationError.SelectionInvalid
        }
        return CapabilityNegotiationError.None
    }

    fun negotiate(
        client: LocalCapabilitySnapshot,
        request: CapabilityRequest,
        host: LocalCapabilitySnapshot,
        policy: HostCapabilityPolicy,
    ): CapabilityNegotiationResult {
        if (!client.isValid() || !host.isValid() || !request.isValid() || !policy.isValid()) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.MalformedCapabilities)
        }
        if (client.role != SessionRole.Client || host.role != SessionRole.Host) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RoleMismatch)
        }
        if (client.core.sclProtocolVersion != 1 || host.core.sclProtocolVersion != 1 ||
            client.core.sessionPacketProtectionVersion != SessionProtectionProtocol.VERSION ||
            host.core.sessionPacketProtectionVersion != SessionProtectionProtocol.VERSION ||
            client.core.sessionFeatureFlags and CapabilityBits.FEATURE_PROTECTED_SESSION_CONTROL == 0 ||
            host.core.sessionFeatureFlags and CapabilityBits.FEATURE_PROTECTED_SESSION_CONTROL == 0
        ) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.CoreProtocolIncompatible)
        }
        val secureDatagramBytes = minOf(client.core.maxSecureDatagramBytes, host.core.maxSecureDatagramBytes)
        if (secureDatagramBytes < CapabilityNegotiationProtocol.MAX_PAYLOAD_BYTES + 21 + SessionProtectionProtocol.OVERHEAD_BYTES) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.SecureDatagramIncompatible)
        }
        val eligiblePaths = client.paths.availablePathKinds and host.paths.availablePathKinds and policy.allowedPathKinds
        if (eligiblePaths == 0) return CapabilityNegotiationResult(CapabilityNegotiationError.NoEligiblePath)

        val recoveryFlags = client.core.recoveryFlags and host.core.recoveryFlags and policy.allowedRecoveryFlags
        if (request.requiredRecoveryFlags and recoveryFlags != request.requiredRecoveryFlags) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredRecoveryFeatureUnavailable)
        }

        var selectedChannels = 0
        fun select(channel: Int, supported: Boolean): CapabilityNegotiationError? {
            if (request.requires(channel)) {
                if (!supported || policy.allowedChannels and channel == 0) return CapabilityNegotiationError.RequiredChannelUnavailable
                selectedChannels = selectedChannels or channel
            } else if (request.wants(channel) && supported && policy.allowedChannels and channel != 0) {
                selectedChannels = selectedChannels or channel
            }
            return null
        }

        val videoSupported = supportsVideo(client.video, host.video)
        select(CapabilityBits.CHANNEL_VIDEO, videoSupported)?.let { return CapabilityNegotiationResult(it) }
        val systemAudioSupported = supportsSystemAudio(client.audio, host.audio)
        select(
            CapabilityBits.CHANNEL_SYSTEM_AUDIO,
            systemAudioSupported,
        )?.let { return CapabilityNegotiationResult(it) }
        val microphoneTransportSupported = supportsMicrophone(client.audio, host.audio)
        val negotiatedMicrophonePolicy = selectMicrophonePolicy(client.behavior, host.behavior, request, policy)
        if (request.requires(CapabilityBits.CHANNEL_MICROPHONE_AUDIO) && microphoneTransportSupported && negotiatedMicrophonePolicy == null) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredBehaviorUnavailable)
        }
        select(
            CapabilityBits.CHANNEL_MICROPHONE_AUDIO,
            microphoneTransportSupported && negotiatedMicrophonePolicy != null,
        )
            ?.let { return CapabilityNegotiationResult(it) }
        val supportedInputKinds = client.input.captureKinds and host.input.injectionKinds and policy.allowedInputKinds
        val inputSupported = supportedInputKinds != 0
        select(CapabilityBits.CHANNEL_INPUT, inputSupported)?.let { return CapabilityNegotiationResult(it) }
        select(CapabilityBits.CHANNEL_TELEMETRY, true)?.let { return CapabilityNegotiationResult(it) }

        if (policy.mandatoryChannels and selectedChannels != policy.mandatoryChannels) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.HostPolicyConflict)
        }
        if (selectedChannels and request.disabledChannels != 0) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.SelectionInvalid)
        }

        if (selectedChannels and CapabilityBits.CHANNEL_INPUT == 0 &&
            (request.requiredInputKinds != 0 || request.stablePresenceRequiredKinds != 0)
        ) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredInputKindUnavailable)
        }
        if (selectedChannels and CapabilityBits.CHANNEL_INPUT != 0 &&
            request.requiredInputKinds and supportedInputKinds != request.requiredInputKinds
        ) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredInputKindUnavailable)
        }

        val videoFlags = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
            client.video.videoFlags or host.video.videoFlags
        } else {
            0
        }
        val lowLatencyAvailable = client.video.videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE != 0
        when (request.videoLowLatencyRequirement) {
            FeatureRequirement.Required -> if (!lowLatencyAvailable) {
                return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredBehaviorUnavailable)
            }
            FeatureRequirement.Disabled -> Unit
            FeatureRequirement.Preferred -> Unit
        }
        val selectedVideoFlags = if (request.videoLowLatencyRequirement == FeatureRequirement.Disabled || !lowLatencyAvailable) {
            videoFlags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE.inv()
        } else {
            videoFlags
        }

        val distinctIdentityAvailable = host.input.inputFlags and CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY != 0
        when (request.distinctGamepadIdentityRequirement) {
            FeatureRequirement.Required -> if (!distinctIdentityAvailable) {
                return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredBehaviorUnavailable)
            }
            FeatureRequirement.Disabled,
            FeatureRequirement.Preferred,
            -> Unit
        }

        val microphoneSelected = selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0
        val microphonePolicy = if (microphoneSelected) {
            requireNotNull(negotiatedMicrophonePolicy)
        } else {
            MicrophoneRoutingSelection.NotApplicable
        }

        val stableSupported = host.behavior.stablePresenceKinds and policy.allowedStablePresenceKinds and supportedInputKinds
        if (request.stablePresenceRequiredKinds and stableSupported != request.stablePresenceRequiredKinds) {
            return CapabilityNegotiationResult(CapabilityNegotiationError.RequiredBehaviorUnavailable)
        }
        val stablePresenceKinds = request.stablePresenceRequiredKinds or
            (request.stablePresencePreferredKinds and stableSupported)

        val selectedInputKinds = if (selectedChannels and CapabilityBits.CHANNEL_INPUT != 0) {
            request.requiredInputKinds or (request.preferredInputKinds and supportedInputKinds) or
                (supportedInputKinds and request.requiredInputKinds.inv() and request.preferredInputKinds.inv())
        } else {
            0
        }
        val selectedInputFlags = if (selectedChannels and CapabilityBits.CHANNEL_INPUT != 0) {
            var flags = client.input.inputFlags and host.input.inputFlags and
                (CapabilityBits.INPUT_RELATIVE_POINTER or CapabilityBits.INPUT_MULTI_TOUCH or CapabilityBits.INPUT_STATE_CONVERGENCE)
            flags = flags or (host.input.inputFlags and CapabilityBits.INPUT_PRIVILEGED_INJECTION)
            if (request.distinctGamepadIdentityRequirement == FeatureRequirement.Disabled || !distinctIdentityAvailable) {
                flags = flags and CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY.inv()
            } else if (selectedInputKinds and CapabilityBits.INPUT_GAMEPAD != 0) {
                flags = flags or CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY
            }
            if (stablePresenceKinds and CapabilityBits.INPUT_GAMEPAD != 0 &&
                host.input.inputFlags and CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD != 0
            ) {
                flags = flags or CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD
            }
            flags
        } else {
            0
        }

        val profile = NegotiatedCapabilityProfile(
            selectedChannels = selectedChannels,
            eligiblePathKinds = eligiblePaths,
            secureDatagramBytes = secureDatagramBytes,
            maxSessionChannels = minOf(client.core.maxSessionChannels, host.core.maxSessionChannels),
            recoveryFlags = recoveryFlags,
            videoCodec = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
                NegotiatedCapabilityProfile.VIDEO_CODEC_AVC
            } else {
                NegotiatedCapabilityProfile.VIDEO_CODEC_NONE
            },
            videoFlags = selectedVideoFlags,
            videoPayloadVersion = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) 1 else 0,
            videoMaxWidth = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
                minOf(
                    client.video.maxWidth,
                    host.video.maxWidth,
                )
            } else {
                0
            },
            videoMaxHeight = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
                minOf(
                    client.video.maxHeight,
                    host.video.maxHeight,
                )
            } else {
                0
            },
            videoMaxFps = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
                minOf(
                    client.video.maxFps,
                    host.video.maxFps,
                )
            } else {
                0
            },
            videoMaxBitrateBps = if (selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0) {
                minOf(client.video.maxBitrateBps, host.video.maxBitrateBps)
            } else {
                0L
            },
            audioCodec = if (selectedChannels and (CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO) != 0) {
                NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS
            } else {
                NegotiatedCapabilityProfile.AUDIO_CODEC_NONE
            },
            audioFrameDurationMask = if (selectedChannels and (CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO) != 0) {
                client.audio.frameDurationMask and host.audio.frameDurationMask
            } else {
                0
            },
            audioPayloadVersion = if (selectedChannels and (CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO) != 0) 1 else 0,
            audioSampleRateMask = if (selectedChannels and (CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO) != 0) {
                client.audio.sampleRateMask and host.audio.sampleRateMask
            } else {
                0
            },
            systemAudioMaxChannels = if (selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0) {
                minOf(host.audio.maxSendChannels, client.audio.maxReceiveChannels)
            } else {
                0
            },
            microphoneMaxChannels = if (microphoneSelected) {
                minOf(
                    client.audio.maxSendChannels,
                    host.audio.maxReceiveChannels,
                )
            } else {
                0
            },
            microphoneRoutingPolicy = microphonePolicy,
            inputPayloadVersion = if (selectedChannels and CapabilityBits.CHANNEL_INPUT != 0) 1 else 0,
            inputKinds = selectedInputKinds,
            inputFeatureFlags = selectedInputFlags,
            stablePresenceKinds = stablePresenceKinds,
        )
        return if (profile.isValid()) {
            CapabilityNegotiationResult(CapabilityNegotiationError.None, profile)
        } else {
            CapabilityNegotiationResult(CapabilityNegotiationError.SelectionInvalid)
        }
    }

    private fun supportsVideo(client: VideoCapabilities, host: VideoCapabilities): Boolean =
        client.videoPayloadVersion == 1 && host.videoPayloadVersion == 1 &&
            client.codecMask and CapabilityBits.VIDEO_AVC != 0 && host.codecMask and CapabilityBits.VIDEO_AVC != 0 &&
            client.videoFlags and (CapabilityBits.VIDEO_HARDWARE_DECODE or CapabilityBits.VIDEO_SURFACE_OUTPUT_DECODE) ==
            (CapabilityBits.VIDEO_HARDWARE_DECODE or CapabilityBits.VIDEO_SURFACE_OUTPUT_DECODE) &&
            host.videoFlags and (CapabilityBits.VIDEO_HARDWARE_ENCODE or CapabilityBits.VIDEO_SURFACE_INPUT_ENCODE) ==
            (CapabilityBits.VIDEO_HARDWARE_ENCODE or CapabilityBits.VIDEO_SURFACE_INPUT_ENCODE) &&
            minOf(client.maxWidth, host.maxWidth) > 0 && minOf(client.maxHeight, host.maxHeight) > 0 &&
            minOf(client.maxFps, host.maxFps) > 0 && minOf(client.maxBitrateBps, host.maxBitrateBps) > 0L

    private fun supportsSystemAudio(client: AudioCapabilities, host: AudioCapabilities): Boolean =
        commonAudio(client, host) && host.audioFlags and (CapabilityBits.AUDIO_SYSTEM_CAPTURE or CapabilityBits.AUDIO_OPUS_ENCODE) ==
            (CapabilityBits.AUDIO_SYSTEM_CAPTURE or CapabilityBits.AUDIO_OPUS_ENCODE) &&
            client.audioFlags and (CapabilityBits.AUDIO_OPUS_DECODE or CapabilityBits.AUDIO_LOW_LATENCY_PLAYBACK) ==
            (CapabilityBits.AUDIO_OPUS_DECODE or CapabilityBits.AUDIO_LOW_LATENCY_PLAYBACK) &&
            minOf(host.maxSendChannels, client.maxReceiveChannels) > 0

    private fun supportsMicrophone(client: AudioCapabilities, host: AudioCapabilities): Boolean =
        commonAudio(client, host) && client.audioFlags and (CapabilityBits.AUDIO_MICROPHONE_CAPTURE or CapabilityBits.AUDIO_OPUS_ENCODE) ==
            (CapabilityBits.AUDIO_MICROPHONE_CAPTURE or CapabilityBits.AUDIO_OPUS_ENCODE) &&
            host.audioFlags and CapabilityBits.AUDIO_OPUS_DECODE != 0 &&
            minOf(client.maxSendChannels, host.maxReceiveChannels) > 0

    private fun commonAudio(client: AudioCapabilities, host: AudioCapabilities): Boolean =
        client.audioPayloadVersion == 1 && host.audioPayloadVersion == 1 &&
            client.codecMask and CapabilityBits.AUDIO_OPUS != 0 && host.codecMask and CapabilityBits.AUDIO_OPUS != 0 &&
            client.frameDurationMask and host.frameDurationMask != 0 && client.sampleRateMask and host.sampleRateMask != 0

    private fun selectMicrophonePolicy(
        client: BehaviorCapabilities,
        host: BehaviorCapabilities,
        request: CapabilityRequest,
        policy: HostCapabilityPolicy,
    ): MicrophoneRoutingSelection? {
        val available = client.microphoneRoutingMask and host.microphoneRoutingMask and policy.allowedMicrophoneRoutingMask
        fun allowed(selection: MicrophoneRoutingSelection): Boolean =
            selection != MicrophoneRoutingSelection.NotApplicable && available and selection.mask != 0
        return when {
            allowed(request.microphonePolicyPrimary) -> request.microphonePolicyPrimary
            allowed(request.microphonePolicyFallback) -> request.microphonePolicyFallback
            else -> null
        }
    }
}
