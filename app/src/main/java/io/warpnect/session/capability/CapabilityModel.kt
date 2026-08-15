@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.session.capability

import io.warpnect.session.SessionRole
import java.security.SecureRandom

/** Capability Negotiation V1 is encrypted application control inside RFC-005E SessionControl. */
object CapabilityNegotiationProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_PAYLOAD_BYTES = 1_024
    const val MAX_TLVS = 16
    const val MAX_TLV_VALUE_BYTES = 512
    const val CLIENT_CONFIRM_BYTES = 96
    const val HOST_COMPLETE_BYTES = 96
    const val REJECT_BYTES = 36
    const val DEFAULT_MAX_ACTIVE_NEGOTIATIONS = 4
    const val HARD_MAX_ACTIVE_NEGOTIATIONS = 8
    const val COMPLETION_CACHE_CAPACITY = 64
    const val COMPLETION_CACHE_RETENTION_MS = 30_000L
    const val DEFAULT_TIMEOUT_MS = 8_000L
    const val POST_NEGOTIATION_RESERVATION_MS = 30_000L
    val RETRY_DELAYS_MS = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L)
    val MAGIC = byteArrayOf('W'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'P'.code.toByte())
}

@JvmInline
value class CapabilityNegotiationId(val value: ULong) {
    val isValid: Boolean get() = value != 0uL

    override fun toString(): String = "CapabilityNegotiationId($value)"

    companion object {
        fun from(value: ULong): CapabilityNegotiationId? = value.takeIf { it != 0uL }?.let(::CapabilityNegotiationId)

        fun requireValid(value: ULong): CapabilityNegotiationId =
            requireNotNull(from(value)) { "CapabilityNegotiationId cannot be zero" }
    }
}

fun interface CapabilityNegotiationIdGenerator {
    fun next(): CapabilityNegotiationId
}

object SecureCapabilityNegotiationIdGenerator : CapabilityNegotiationIdGenerator {
    private val random = SecureRandom()

    override fun next(): CapabilityNegotiationId {
        do {
            val value = random.nextLong().toULong()
            CapabilityNegotiationId.from(value)?.let { return it }
        } while (true)
    }
}

enum class CapabilityNegotiationMessageType(val wireId: Int) {
    ClientOffer(1),
    HostSelection(2),
    ClientConfirm(3),
    HostComplete(4),
    NegotiationReject(5),
    ;

    companion object {
        fun fromWireId(wireId: Int): CapabilityNegotiationMessageType? = entries.firstOrNull { it.wireId == wireId }
    }
}

enum class CapabilityNegotiationRejectStage(val wireId: Int) {
    Offer(1),
    Selection(2),
    Confirm(3),
    ;

    companion object {
        fun fromWireId(wireId: Int): CapabilityNegotiationRejectStage? = entries.firstOrNull { it.wireId == wireId }
    }
}

enum class CapabilityNegotiationRejectReason(val wireId: Int) {
    Incompatible(1),
    Busy(2),
    Malformed(3),
    Conflict(4),
    AuthenticationFailure(5),
    ;

    companion object {
        fun fromWireId(wireId: Int): CapabilityNegotiationRejectReason? = entries.firstOrNull { it.wireId == wireId }
    }
}

enum class CapabilityNegotiationError {
    None,
    InvalidConfig,
    CapabilityPayloadTooLarge,
    MalformedCapabilities,
    CoreProtocolIncompatible,
    SecureDatagramIncompatible,
    NoEligiblePath,
    RequiredChannelUnavailable,
    RequiredInputKindUnavailable,
    RequiredBehaviorUnavailable,
    RequiredRecoveryFeatureUnavailable,
    RoleMismatch,
    HostPolicyConflict,
    SelectionInvalid,
    NegotiationConflict,
    AdmissionExpired,
    Busy,
    Timeout,
    SecureControlFailure,
    Closed,
}

enum class CapabilityTlvType(val wireType: Int) {
    CoreTransportCapabilities(0x8001),
    PathCapabilities(0x0002),
    VideoCapabilities(0x0003),
    AudioCapabilities(0x0004),
    InputCapabilities(0x0005),
    BehaviorCapabilities(0x0006),
    CapabilityRequest(0x8007),
    NegotiatedCapabilityProfile(0x8008),
    ;

    companion object {
        fun fromWireType(wireType: Int): CapabilityTlvType? = entries.firstOrNull { it.wireType == wireType }
    }
}

object CapabilityBits {
    const val PATH_LAN = 1 shl 0
    const val PATH_DIRECT = 1 shl 1
    const val PATH_MASK = PATH_LAN or PATH_DIRECT
    const val PATH_STANDBY_SUPPORTED = 1 shl 0

    const val RECOVERY_NACK = 1 shl 0
    const val RECOVERY_FEC = 1 shl 1
    const val RECOVERY_CLOCK_SYNC = 1 shl 2
    const val RECOVERY_VIDEO_RESYNC = 1 shl 3
    const val RECOVERY_MASK = RECOVERY_NACK or RECOVERY_FEC or RECOVERY_CLOCK_SYNC or RECOVERY_VIDEO_RESYNC

    const val FEATURE_PROTECTED_SESSION_CONTROL = 1 shl 0
    const val FEATURE_MULTI_CLIENT_HOST = 1 shl 1
    const val FEATURE_STANDBY_PATH = 1 shl 2
    const val FEATURE_INPUT_STATE_CONVERGENCE = 1 shl 3
    const val SESSION_FEATURE_MASK =
        FEATURE_PROTECTED_SESSION_CONTROL or FEATURE_MULTI_CLIENT_HOST or FEATURE_STANDBY_PATH or
            FEATURE_INPUT_STATE_CONVERGENCE

    const val VIDEO_AVC = 1 shl 0
    const val VIDEO_CODEC_MASK = VIDEO_AVC
    const val VIDEO_HARDWARE_ENCODE = 1 shl 0
    const val VIDEO_SURFACE_INPUT_ENCODE = 1 shl 1
    const val VIDEO_HARDWARE_DECODE = 1 shl 2
    const val VIDEO_SURFACE_OUTPUT_DECODE = 1 shl 3
    const val VIDEO_LOW_LATENCY_DECODE = 1 shl 4
    const val VIDEO_DYNAMIC_BITRATE = 1 shl 5
    const val VIDEO_KEYFRAME_REQUEST = 1 shl 6
    const val VIDEO_RESYNC = 1 shl 7
    const val VIDEO_FLAG_MASK =
        VIDEO_HARDWARE_ENCODE or VIDEO_SURFACE_INPUT_ENCODE or VIDEO_HARDWARE_DECODE or
            VIDEO_SURFACE_OUTPUT_DECODE or VIDEO_LOW_LATENCY_DECODE or VIDEO_DYNAMIC_BITRATE or
            VIDEO_KEYFRAME_REQUEST or VIDEO_RESYNC

    const val AUDIO_OPUS = 1 shl 0
    const val AUDIO_CODEC_MASK = AUDIO_OPUS
    const val AUDIO_FRAME_2_5_MS = 1 shl 0
    const val AUDIO_FRAME_5_MS = 1 shl 1
    const val AUDIO_FRAME_10_MS = 1 shl 2
    const val AUDIO_FRAME_20_MS = 1 shl 3
    const val AUDIO_FRAME_MASK = AUDIO_FRAME_2_5_MS or AUDIO_FRAME_5_MS or AUDIO_FRAME_10_MS or AUDIO_FRAME_20_MS
    const val AUDIO_SAMPLE_RATE_48_KHZ = 1 shl 0
    const val AUDIO_SAMPLE_RATE_MASK = AUDIO_SAMPLE_RATE_48_KHZ
    const val AUDIO_OPUS_ENCODE = 1 shl 0
    const val AUDIO_OPUS_DECODE = 1 shl 1
    const val AUDIO_SYSTEM_CAPTURE = 1 shl 2
    const val AUDIO_MICROPHONE_CAPTURE = 1 shl 3
    const val AUDIO_LOW_LATENCY_PLAYBACK = 1 shl 4
    const val AUDIO_PLC_DECODE = 1 shl 5
    const val AUDIO_FLAG_MASK =
        AUDIO_OPUS_ENCODE or AUDIO_OPUS_DECODE or AUDIO_SYSTEM_CAPTURE or AUDIO_MICROPHONE_CAPTURE or
            AUDIO_LOW_LATENCY_PLAYBACK or AUDIO_PLC_DECODE

    const val INPUT_KEYBOARD = 1 shl 0
    const val INPUT_TOUCHSCREEN = 1 shl 1
    const val INPUT_MOUSE = 1 shl 2
    const val INPUT_GAMEPAD = 1 shl 3
    const val INPUT_STYLUS = 1 shl 4
    const val INPUT_TOUCHPAD = 1 shl 5
    const val INPUT_KIND_MASK =
        INPUT_KEYBOARD or INPUT_TOUCHSCREEN or INPUT_MOUSE or INPUT_GAMEPAD or INPUT_STYLUS or INPUT_TOUCHPAD
    const val INPUT_RELATIVE_POINTER = 1 shl 0
    const val INPUT_MULTI_TOUCH = 1 shl 1
    const val INPUT_STATE_CONVERGENCE = 1 shl 2
    const val INPUT_DISTINCT_GAMEPAD_IDENTITY = 1 shl 3
    const val INPUT_STABLE_VIRTUAL_GAMEPAD = 1 shl 4
    const val INPUT_PRIVILEGED_INJECTION = 1 shl 5
    const val INPUT_FLAG_MASK =
        INPUT_RELATIVE_POINTER or INPUT_MULTI_TOUCH or INPUT_STATE_CONVERGENCE or
            INPUT_DISTINCT_GAMEPAD_IDENTITY or INPUT_STABLE_VIRTUAL_GAMEPAD or INPUT_PRIVILEGED_INJECTION

    const val MICROPHONE_SEPARATE_PER_PEER = 1 shl 0
    const val MICROPHONE_MIX_TO_SINGLE_HOST_STREAM = 1 shl 1
    const val MICROPHONE_ROUTING_MASK = MICROPHONE_SEPARATE_PER_PEER or MICROPHONE_MIX_TO_SINGLE_HOST_STREAM

    const val CHANNEL_VIDEO = 1 shl 0
    const val CHANNEL_SYSTEM_AUDIO = 1 shl 1
    const val CHANNEL_MICROPHONE_AUDIO = 1 shl 2
    const val CHANNEL_INPUT = 1 shl 3
    const val CHANNEL_TELEMETRY = 1 shl 4
    const val OPTIONAL_CHANNEL_MASK =
        CHANNEL_VIDEO or CHANNEL_SYSTEM_AUDIO or CHANNEL_MICROPHONE_AUDIO or CHANNEL_INPUT or CHANNEL_TELEMETRY
}

enum class MicrophoneRoutingSelection(val wireId: Int, val mask: Int) {
    NotApplicable(0, 0),
    SeparatePerPeer(1, CapabilityBits.MICROPHONE_SEPARATE_PER_PEER),
    MixToSingleHostStream(2, CapabilityBits.MICROPHONE_MIX_TO_SINGLE_HOST_STREAM),
    ;

    companion object {
        fun fromWireId(wireId: Int): MicrophoneRoutingSelection? = entries.firstOrNull { it.wireId == wireId }
    }
}

enum class FeatureRequirement(val wireId: Int) {
    Disabled(0),
    Preferred(1),
    Required(2),
    ;

    companion object {
        fun fromWireId(wireId: Int): FeatureRequirement? = entries.firstOrNull { it.wireId == wireId }
    }
}

enum class LocalCapabilityAvailability {
    Unsupported,
    SupportedButUnavailable,
    Available,
}

data class CoreTransportCapabilities(
    val sclProtocolVersion: Int,
    val sessionPacketProtectionVersion: Int,
    val maxSecureDatagramBytes: Int,
    val maxSessionChannels: Int,
    val recoveryFlags: Int,
    val sessionFeatureFlags: Int,
) {
    fun isValid(): Boolean = sclProtocolVersion in 0..0xffff && sessionPacketProtectionVersion in 0..0xffff &&
        maxSecureDatagramBytes in 1..0xffff && maxSessionChannels in 1..0xffff &&
        recoveryFlags and CapabilityBits.RECOVERY_MASK.inv() == 0 &&
        sessionFeatureFlags and CapabilityBits.SESSION_FEATURE_MASK.inv() == 0
}

data class PathCapabilities(
    val implementedPathKinds: Int,
    val availablePathKinds: Int,
    val maxPaths: Int,
    val pathFlags: Int,
) {
    fun isValid(): Boolean = implementedPathKinds and CapabilityBits.PATH_MASK.inv() == 0 &&
        availablePathKinds and CapabilityBits.PATH_MASK.inv() == 0 &&
        availablePathKinds and implementedPathKinds == availablePathKinds && maxPaths in 1..4 &&
        pathFlags and CapabilityBits.PATH_STANDBY_SUPPORTED.inv() == 0
}

data class VideoCapabilities(
    val videoPayloadVersion: Int,
    val codecMask: Int,
    val videoFlags: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val maxBitrateBps: Long,
) {
    fun isValid(): Boolean =
        videoPayloadVersion in 0..0xffff && codecMask and CapabilityBits.VIDEO_CODEC_MASK.inv() == 0 &&
            videoFlags and CapabilityBits.VIDEO_FLAG_MASK.inv() == 0 && maxWidth in 0..0xffff &&
            maxHeight in 0..0xffff && maxFps in 0..0xffff && maxBitrateBps in 0..0xffff_ffffL
}

data class AudioCapabilities(
    val audioPayloadVersion: Int,
    val codecMask: Int,
    val audioFlags: Int,
    val frameDurationMask: Int,
    val maxSendChannels: Int,
    val maxReceiveChannels: Int,
    val sampleRateMask: Int,
) {
    fun isValid(): Boolean =
        audioPayloadVersion in 0..0xffff && codecMask and CapabilityBits.AUDIO_CODEC_MASK.inv() == 0 &&
            audioFlags and CapabilityBits.AUDIO_FLAG_MASK.inv() == 0 &&
            frameDurationMask and CapabilityBits.AUDIO_FRAME_MASK.inv() == 0 && maxSendChannels in 0..0xff &&
            maxReceiveChannels in 0..0xff && sampleRateMask and CapabilityBits.AUDIO_SAMPLE_RATE_MASK.inv() == 0
}

data class InputCapabilities(
    val inputPayloadVersion: Int,
    val captureKinds: Int,
    val injectionKinds: Int,
    val maxTouchPointers: Int,
    val maxDeviceSlots: Int,
    val inputFlags: Int,
) {
    fun isValid(): Boolean =
        inputPayloadVersion in 0..0xffff && captureKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
            injectionKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 && maxTouchPointers in 0..32 &&
            maxDeviceSlots in 0..0xff && inputFlags and CapabilityBits.INPUT_FLAG_MASK.inv() == 0
}

data class BehaviorCapabilities(
    val microphoneRoutingMask: Int,
    val mirrorPresenceKinds: Int,
    val stablePresenceKinds: Int,
) {
    fun isValid(): Boolean = microphoneRoutingMask and CapabilityBits.MICROPHONE_ROUTING_MASK.inv() == 0 &&
        mirrorPresenceKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
        stablePresenceKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
        mirrorPresenceKinds and stablePresenceKinds == 0
}

data class CapabilityRequest(
    val requiredChannels: Int,
    val preferredChannels: Int,
    val disabledChannels: Int,
    val requiredInputKinds: Int,
    val preferredInputKinds: Int,
    val microphonePolicyPrimary: MicrophoneRoutingSelection,
    val microphonePolicyFallback: MicrophoneRoutingSelection,
    val stablePresenceRequiredKinds: Int,
    val stablePresencePreferredKinds: Int,
    val videoLowLatencyRequirement: FeatureRequirement,
    val distinctGamepadIdentityRequirement: FeatureRequirement,
    val requiredRecoveryFlags: Int,
) {
    fun isValid(): Boolean {
        val channelUnion = requiredChannels or preferredChannels or disabledChannels
        return channelUnion == CapabilityBits.OPTIONAL_CHANNEL_MASK &&
            requiredChannels and preferredChannels == 0 && requiredChannels and disabledChannels == 0 &&
            preferredChannels and disabledChannels == 0 &&
            requiredInputKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
            preferredInputKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
            requiredInputKinds and preferredInputKinds == 0 &&
            stablePresenceRequiredKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
            stablePresencePreferredKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
            stablePresenceRequiredKinds and stablePresencePreferredKinds == 0 &&
            requiredRecoveryFlags and CapabilityBits.RECOVERY_MASK.inv() == 0 &&
            (
                requiredInputKinds == 0 && stablePresenceRequiredKinds == 0 ||
                    disabledChannels and CapabilityBits.CHANNEL_INPUT == 0
                ) &&
            microphoneFallbackIsExplicit()
    }

    fun wants(channel: Int): Boolean = requiredChannels and channel != 0 || preferredChannels and channel != 0
    fun requires(channel: Int): Boolean = requiredChannels and channel != 0

    private fun microphoneFallbackIsExplicit(): Boolean =
        microphonePolicyPrimary != MicrophoneRoutingSelection.NotApplicable ||
            microphonePolicyFallback == MicrophoneRoutingSelection.NotApplicable
}

data class HostCapabilityPolicy(
    val allowedChannels: Int = CapabilityBits.OPTIONAL_CHANNEL_MASK,
    val mandatoryChannels: Int = 0,
    val allowedPathKinds: Int = CapabilityBits.PATH_MASK,
    val allowedRecoveryFlags: Int = CapabilityBits.RECOVERY_MASK,
    val allowedInputKinds: Int = CapabilityBits.INPUT_KIND_MASK,
    val allowedMicrophoneRoutingMask: Int = CapabilityBits.MICROPHONE_ROUTING_MASK,
    val allowLowLatencyVideo: Boolean = true,
    val allowDistinctGamepadIdentity: Boolean = true,
    val allowedStablePresenceKinds: Int = CapabilityBits.INPUT_KIND_MASK,
) {
    fun isValid(): Boolean = allowedChannels and CapabilityBits.OPTIONAL_CHANNEL_MASK.inv() == 0 &&
        mandatoryChannels and CapabilityBits.OPTIONAL_CHANNEL_MASK.inv() == 0 &&
        mandatoryChannels and allowedChannels == mandatoryChannels &&
        allowedPathKinds and CapabilityBits.PATH_MASK.inv() == 0 &&
        allowedRecoveryFlags and CapabilityBits.RECOVERY_MASK.inv() == 0 &&
        allowedInputKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0 &&
        allowedMicrophoneRoutingMask and CapabilityBits.MICROPHONE_ROUTING_MASK.inv() == 0 &&
        allowedStablePresenceKinds and CapabilityBits.INPUT_KIND_MASK.inv() == 0
}

/** Immutable capability snapshot frozen once at the beginning of one WNCP negotiation. */
data class LocalCapabilitySnapshot(
    val capturedAtMonotonicNs: Long,
    val role: SessionRole,
    val core: CoreTransportCapabilities,
    val paths: PathCapabilities,
    val video: VideoCapabilities,
    val audio: AudioCapabilities,
    val input: InputCapabilities,
    val behavior: BehaviorCapabilities,
    val localAvailability: Map<String, LocalCapabilityAvailability> = emptyMap(),
) {
    fun isValid(): Boolean =
        capturedAtMonotonicNs >= 0L && core.isValid() && paths.isValid() && video.isValid() && audio.isValid() &&
            input.isValid() && behavior.isValid()
}

data class NegotiatedCapabilityProfile(
    val selectedChannels: Int,
    val eligiblePathKinds: Int,
    val secureDatagramBytes: Int,
    val maxSessionChannels: Int,
    val recoveryFlags: Int,
    val videoCodec: Int,
    val videoFlags: Int,
    val videoPayloadVersion: Int,
    val videoMaxWidth: Int,
    val videoMaxHeight: Int,
    val videoMaxFps: Int,
    val videoMaxBitrateBps: Long,
    val audioCodec: Int,
    val audioFrameDurationMask: Int,
    val audioPayloadVersion: Int,
    val audioSampleRateMask: Int,
    val systemAudioMaxChannels: Int,
    val microphoneMaxChannels: Int,
    val microphoneRoutingPolicy: MicrophoneRoutingSelection,
    val inputPayloadVersion: Int,
    val inputKinds: Int,
    val inputFeatureFlags: Int,
    val stablePresenceKinds: Int,
) {
    fun isValid(): Boolean {
        if (selectedChannels and CapabilityBits.OPTIONAL_CHANNEL_MASK.inv() != 0 ||
            eligiblePathKinds and CapabilityBits.PATH_MASK.inv() != 0 || eligiblePathKinds == 0 ||
            secureDatagramBytes !in 1..0xffff || maxSessionChannels !in 1..0xffff ||
            recoveryFlags and CapabilityBits.RECOVERY_MASK.inv() != 0 ||
            inputKinds and CapabilityBits.INPUT_KIND_MASK.inv() != 0 ||
            inputFeatureFlags and CapabilityBits.INPUT_FLAG_MASK.inv() != 0 ||
            stablePresenceKinds and CapabilityBits.INPUT_KIND_MASK.inv() != 0
        ) {
            return false
        }
        if (videoCodec !in listOf(VIDEO_CODEC_NONE, VIDEO_CODEC_AVC) ||
            audioCodec !in listOf(AUDIO_CODEC_NONE, AUDIO_CODEC_OPUS)
        ) {
            return false
        }
        val videoSelected = selectedChannels and CapabilityBits.CHANNEL_VIDEO != 0
        if (videoSelected != (videoCodec == VIDEO_CODEC_AVC)) return false
        if (videoSelected && (
                videoPayloadVersion != 1 || videoMaxWidth == 0 || videoMaxHeight == 0 ||
                    videoMaxFps == 0 || videoMaxBitrateBps == 0L || videoFlags and CapabilityBits.VIDEO_FLAG_MASK.inv() != 0
                )
        ) {
            return false
        }
        if (!videoSelected && (
                videoFlags != 0 || videoPayloadVersion != 0 || videoMaxWidth != 0 ||
                    videoMaxHeight != 0 || videoMaxFps != 0 || videoMaxBitrateBps != 0L
                )
        ) {
            return false
        }
        val audioSelected = selectedChannels and (CapabilityBits.CHANNEL_SYSTEM_AUDIO or CapabilityBits.CHANNEL_MICROPHONE_AUDIO) != 0
        if (audioSelected != (audioCodec == AUDIO_CODEC_OPUS)) return false
        if (audioSelected && (
                audioPayloadVersion != 1 || audioFrameDurationMask == 0 || audioSampleRateMask == 0 ||
                    audioFrameDurationMask and CapabilityBits.AUDIO_FRAME_MASK.inv() != 0 ||
                    audioSampleRateMask and CapabilityBits.AUDIO_SAMPLE_RATE_MASK.inv() != 0
                )
        ) {
            return false
        }
        if (!audioSelected && (
                audioFrameDurationMask != 0 || audioPayloadVersion != 0 || audioSampleRateMask != 0 ||
                    systemAudioMaxChannels != 0 || microphoneMaxChannels != 0 ||
                    microphoneRoutingPolicy != MicrophoneRoutingSelection.NotApplicable
                )
        ) {
            return false
        }
        val microphoneSelected = selectedChannels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0
        if (microphoneSelected != (microphoneRoutingPolicy != MicrophoneRoutingSelection.NotApplicable)) return false
        val systemAudioSelected = selectedChannels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0
        if ((systemAudioSelected && systemAudioMaxChannels == 0) ||
            (!systemAudioSelected && systemAudioMaxChannels != 0) ||
            (microphoneSelected && microphoneMaxChannels == 0) ||
            (!microphoneSelected && microphoneMaxChannels != 0)
        ) {
            return false
        }
        val inputSelected = selectedChannels and CapabilityBits.CHANNEL_INPUT != 0
        return if (inputSelected) {
            inputPayloadVersion == 1 && inputKinds != 0 &&
                stablePresenceKinds and inputKinds == stablePresenceKinds &&
                (
                    inputFeatureFlags and
                        (CapabilityBits.INPUT_DISTINCT_GAMEPAD_IDENTITY or CapabilityBits.INPUT_STABLE_VIRTUAL_GAMEPAD) == 0 ||
                        inputKinds and CapabilityBits.INPUT_GAMEPAD != 0
                    )
        } else {
            inputPayloadVersion == 0 && inputKinds == 0 && inputFeatureFlags == 0 && stablePresenceKinds == 0
        }
    }

    companion object {
        const val VIDEO_CODEC_NONE = 0
        const val VIDEO_CODEC_AVC = 1
        const val AUDIO_CODEC_NONE = 0
        const val AUDIO_CODEC_OPUS = 1
    }
}

data class CapabilityNegotiationResult(
    val error: CapabilityNegotiationError,
    val profile: NegotiatedCapabilityProfile? = null,
) {
    val isSuccess: Boolean get() = error == CapabilityNegotiationError.None && profile != null
}
