@file:Suppress("ktlint:standard:max-line-length")

package io.warpnect.platform.session.capability

import android.os.SystemClock
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.encoder.AudioEncoderCapabilities
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.AudioCapabilities
import io.warpnect.session.capability.BehaviorCapabilities
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.CoreTransportCapabilities
import io.warpnect.session.capability.InputCapabilities
import io.warpnect.session.capability.LocalCapabilityAvailability
import io.warpnect.session.capability.LocalCapabilityCollector
import io.warpnect.session.capability.LocalCapabilitySnapshot
import io.warpnect.session.capability.PathCapabilities
import io.warpnect.session.capability.VideoCapabilities
import io.warpnect.session.security.SessionProtectionProtocol
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration

/**
 * A cold-path mapper over existing subsystem probes. The caller supplies probe results; this
 * collector never starts a codec, capture device, audio stream, input injection, UHID device, or
 * Wi-Fi Direct connection merely to construct WNCP.
 */
class AndroidLocalCapabilityCollector(
    private val probe: AndroidCapabilityProbe,
    private val monotonicNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : LocalCapabilityCollector {
    override fun collect(role: SessionRole): LocalCapabilitySnapshot =
        probe.snapshot(role).toLocalSnapshot(role, monotonicNs())
}

fun interface AndroidCapabilityProbe {
    fun snapshot(role: SessionRole): AndroidCapabilityProbeSnapshot
}

/**
 * The producer should reuse the Phase 2/3/4 controller query APIs. `implementedCaptureKinds` is
 * deliberately independent from the instantaneous physical-device observations in Phase 4.
 */
data class AndroidCapabilityProbeSnapshot(
    val lanSecurePathAvailable: Boolean,
    val videoEncoder: VideoEncoderCapabilities? = null,
    val videoDecoder: VideoDecoderCapabilities? = null,
    val systemAudioCapture: AudioCaptureCapabilities? = null,
    val microphoneCapture: AudioCaptureCapabilities? = null,
    val audioEncoder: AudioEncoderCapabilities? = null,
    val opusDecodeAvailable: Boolean = false,
    val lowLatencyPlaybackAvailable: Boolean = false,
    val inputInjection: InputInjectionCapabilities? = null,
    val implementedCaptureKinds: Int = 0,
    val captureMaxTouchPointers: Int = 32,
    val captureMaxDeviceSlots: Int = 32,
    val inputStateConvergenceAvailable: Boolean = true,
    val recoveryFlags: Int = CapabilityBits.RECOVERY_NACK or CapabilityBits.RECOVERY_FEC or
        CapabilityBits.RECOVERY_CLOCK_SYNC or CapabilityBits.RECOVERY_VIDEO_RESYNC,
    val multiClientHostModelAvailable: Boolean = true,
    val separateMicrophonePerPeerAvailable: Boolean = false,
) {
    fun toLocalSnapshot(role: SessionRole, capturedAtMonotonicNs: Long): LocalCapabilitySnapshot {
        val encoder = videoEncoder?.takeIf {
            it.isSupported && it.selectedCodec?.hardwareAcceleration == VideoEncoderHardwareAcceleration.Hardware
        }
        val decoder = videoDecoder?.takeIf {
            it.isSupported && it.selectedCodec?.hardwareAcceleration == VideoDecoderHardwareAcceleration.Hardware
        }
        val videoFlags = (if (encoder != null) CapabilityBits.VIDEO_HARDWARE_ENCODE else 0) or
            (if (encoder?.support?.surfaceInputSupported == true) CapabilityBits.VIDEO_SURFACE_INPUT_ENCODE else 0) or
            (if (decoder != null) CapabilityBits.VIDEO_HARDWARE_DECODE else 0) or
            (if (decoder != null) CapabilityBits.VIDEO_SURFACE_OUTPUT_DECODE else 0) or
            (
                if (decoder?.support?.lowLatencyFeatureSupported == true || decoder?.selectedCodec?.lowLatencyFeatureSupported == true) {
                    CapabilityBits.VIDEO_LOW_LATENCY_DECODE
                } else {
                    0
                }
                ) or
            (if (encoder?.support?.bitrateModeSupported == true) CapabilityBits.VIDEO_DYNAMIC_BITRATE else 0) or
            (if (encoder != null) CapabilityBits.VIDEO_KEYFRAME_REQUEST or CapabilityBits.VIDEO_RESYNC else 0)
        val videoReady = when (role) {
            SessionRole.Host -> encoder?.support?.surfaceInputSupported == true
            SessionRole.Client -> decoder != null
        }
        val video = VideoCapabilities(
            videoPayloadVersion = if (videoReady) 1 else 0,
            codecMask = if (videoReady) CapabilityBits.VIDEO_AVC else 0,
            videoFlags = videoFlags,
            maxWidth = listOfNotNull(encoder?.support?.maxWidth, decoder?.support?.maxWidth).minOrNull() ?: 0,
            maxHeight = listOfNotNull(encoder?.support?.maxHeight, decoder?.support?.maxHeight).minOrNull() ?: 0,
            maxFps = if (videoReady) 120 else 0,
            maxBitrateBps = (encoder?.support?.maxBitrateBps ?: if (decoder != null) 50_000_000 else 0).toLong(),
        )

        val opusEncode = audioEncoder?.let {
            it.available && it.support.codecSupported && it.support.sampleRateSupported &&
                it.support.channelCountSupported && it.support.frameDurationSupported
        } == true
        val systemAvailable = systemAudioCapture?.available == true
        val microphoneAvailable = microphoneCapture?.available == true
        val audioFlags = (if (opusEncode) CapabilityBits.AUDIO_OPUS_ENCODE else 0) or
            (if (opusDecodeAvailable) CapabilityBits.AUDIO_OPUS_DECODE else 0) or
            (if (systemAvailable) CapabilityBits.AUDIO_SYSTEM_CAPTURE else 0) or
            (if (microphoneAvailable) CapabilityBits.AUDIO_MICROPHONE_CAPTURE else 0) or
            (if (lowLatencyPlaybackAvailable) CapabilityBits.AUDIO_LOW_LATENCY_PLAYBACK else 0) or
            (if (opusDecodeAvailable) CapabilityBits.AUDIO_PLC_DECODE else 0)
        val audioReady = audioFlags != 0
        val audio = AudioCapabilities(
            audioPayloadVersion = if (audioReady) 1 else 0,
            codecMask = if (audioReady) CapabilityBits.AUDIO_OPUS else 0,
            audioFlags = audioFlags,
            frameDurationMask = if (audioReady) {
                CapabilityBits.AUDIO_FRAME_5_MS or CapabilityBits.AUDIO_FRAME_10_MS or
                    CapabilityBits.AUDIO_FRAME_20_MS
            } else {
                0
            },
            maxSendChannels = maxOf(systemAudioCapture?.channelCount ?: 0, microphoneCapture?.channelCount ?: 0),
            maxReceiveChannels = if (opusDecodeAvailable) 2 else 0,
            sampleRateMask = if (audioReady) CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ else 0,
        )

        val injection = inputInjection
        val injectionReady = injection?.serviceAvailable == true && injection.backend != InputInjectionBackend.None
        val injectionKinds = if (!injectionReady) {
            0
        } else {
            (if (injection.keyInjectionSupported) CapabilityBits.INPUT_KEYBOARD else 0) or
                (if (injection.touchInjectionSupported) CapabilityBits.INPUT_TOUCHSCREEN else 0) or
                (
                    if (injection.pointerInjectionSupported) {
                        CapabilityBits.INPUT_MOUSE or CapabilityBits.INPUT_STYLUS or CapabilityBits.INPUT_TOUCHPAD
                    } else {
                        0
                    }
                    ) or
                (if (injection.joystickInjectionSupported) CapabilityBits.INPUT_GAMEPAD else 0)
        }
        val inputFlags = (if (injectionReady) CapabilityBits.INPUT_PRIVILEGED_INJECTION else 0) or
            (if (inputStateConvergenceAvailable) CapabilityBits.INPUT_STATE_CONVERGENCE else 0) or
            (if (injection?.pointerInjectionSupported == true) CapabilityBits.INPUT_RELATIVE_POINTER else 0) or
            (if (injection?.touchInjectionSupported == true) CapabilityBits.INPUT_MULTI_TOUCH else 0)
        val input = InputCapabilities(
            inputPayloadVersion = 1,
            captureKinds = implementedCaptureKinds and CapabilityBits.INPUT_KIND_MASK,
            injectionKinds = injectionKinds,
            maxTouchPointers = minOf(
                captureMaxTouchPointers.coerceIn(0, 32),
                injection?.maxPointers?.coerceIn(0, 32) ?: 32,
            ),
            maxDeviceSlots = captureMaxDeviceSlots.coerceIn(0, 255),
            inputFlags = inputFlags,
        )

        val availability = linkedMapOf(
            "lanPath" to if (lanSecurePathAvailable) LocalCapabilityAvailability.Available else LocalCapabilityAvailability.SupportedButUnavailable,
            "directPath" to LocalCapabilityAvailability.Unsupported,
            "video" to if (videoReady) LocalCapabilityAvailability.Available else LocalCapabilityAvailability.SupportedButUnavailable,
            "systemAudio" to if (systemAvailable && opusEncode) LocalCapabilityAvailability.Available else LocalCapabilityAvailability.SupportedButUnavailable,
            "microphone" to if (microphoneAvailable && opusEncode) LocalCapabilityAvailability.Available else LocalCapabilityAvailability.SupportedButUnavailable,
            "distinctGamepadIdentity" to LocalCapabilityAvailability.Unsupported,
            "stableVirtualGamepad" to LocalCapabilityAvailability.Unsupported,
            "microphoneMix" to LocalCapabilityAvailability.Unsupported,
        )
        return LocalCapabilitySnapshot(
            capturedAtMonotonicNs = capturedAtMonotonicNs.coerceAtLeast(0L),
            role = role,
            core = CoreTransportCapabilities(
                sclProtocolVersion = 1,
                sessionPacketProtectionVersion = SessionProtectionProtocol.VERSION,
                maxSecureDatagramBytes = 1_200,
                maxSessionChannels = 32,
                recoveryFlags = recoveryFlags and CapabilityBits.RECOVERY_MASK,
                sessionFeatureFlags = CapabilityBits.FEATURE_PROTECTED_SESSION_CONTROL or
                    CapabilityBits.FEATURE_INPUT_STATE_CONVERGENCE or
                    (if (multiClientHostModelAvailable) CapabilityBits.FEATURE_MULTI_CLIENT_HOST else 0),
            ),
            paths = PathCapabilities(
                implementedPathKinds = CapabilityBits.PATH_LAN,
                availablePathKinds = if (lanSecurePathAvailable) CapabilityBits.PATH_LAN else 0,
                maxPaths = 1,
                pathFlags = 0,
            ),
            video = video,
            audio = audio,
            input = input,
            behavior = BehaviorCapabilities(
                microphoneRoutingMask = if (separateMicrophonePerPeerAvailable) {
                    CapabilityBits.MICROPHONE_SEPARATE_PER_PEER
                } else {
                    0
                },
                mirrorPresenceKinds = implementedCaptureKinds and CapabilityBits.INPUT_KIND_MASK,
                stablePresenceKinds = 0,
            ),
            localAvailability = availability,
        )
    }
}
