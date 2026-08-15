package io.warpnect.platform.session.setup

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.session.ChannelId
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.MicrophoneRoutingSelection
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.setup.AudioStreamMode
import io.warpnect.session.setup.InputStreamConfiguration
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SetupConfiguration
import io.warpnect.session.setup.VideoStreamMode
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderCodecInfo
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderHardwareAcceleration
import io.warpnect.video.decoder.VideoDecoderSupport
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderCodecInfo
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidExactStreamConfigurationValidatorTest {
    @Test
    fun hostVideoValidationUsesExactHardwareEncoderRequestWithoutStartingCodec() {
        val encoder = RecordingEncoderDiscovery(supported = true)
        val validator = validator(encoder = encoder)
        val mode = VideoStreamMode(1920, 1080, 60, 12_000_000)

        val result = validator.validate(
            SessionRole.Host,
            profile(CapabilityBits.CHANNEL_VIDEO),
            listOf(SetupConfiguration.Video(channelId(1), mode)),
        )

        assertEquals(SessionSetupError.None, result)
        assertEquals(1920, encoder.lastRequest?.width)
        assertEquals(1080, encoder.lastRequest?.height)
        assertEquals(60, encoder.lastRequest?.frameRate)
        assertEquals(12_000_000, encoder.lastRequest?.bitrateBps)
        assertFalse(encoder.codecStarted)
    }

    @Test
    fun clientVideoValidationRequiresExactFrameRateAndRequestedLowLatency() {
        val decoder = RecordingDecoderDiscovery(supported = false, lowLatency = false)
        val validator = validator(decoder = decoder)
        val mode = VideoStreamMode(
            1280,
            720,
            120,
            8_000_000,
            CapabilityBits.VIDEO_LOW_LATENCY_DECODE,
        )

        val result = validator.validate(
            SessionRole.Client,
            profile(CapabilityBits.CHANNEL_VIDEO),
            listOf(SetupConfiguration.Video(channelId(1), mode)),
        )

        assertEquals(SessionSetupError.ExactVideoConfigurationUnavailable, result)
        assertEquals(120, decoder.lastConfig?.expectedFrameRate)
        assertFalse(decoder.codecStarted)
    }

    @Test
    fun microphoneSenderRevalidatesExactCaptureAndOpusWithoutStartingCapture() {
        var queries = 0
        val validator = validator(
            microphone = ExactAudioCaptureCapabilityQuery { request ->
                queries += 1
                AudioCaptureCapabilities(
                    source = request.source,
                    available = true,
                    supportedSampleRatesHz = listOf(48_000),
                    selectedSampleRateHz = request.preferredSampleRateHz,
                    channelCount = request.channelCount,
                    timestampSupport = AudioTimestampQuality.AudioRecordTimestamp,
                )
            },
        )
        val result = validator.validate(
            SessionRole.Client,
            profile(CapabilityBits.CHANNEL_MICROPHONE_AUDIO),
            listOf(
                SetupConfiguration.MicrophoneAudio(
                    channelId(1),
                    AudioStreamMode(48_000, 5_000, 1, 64_000),
                ),
            ),
        )

        assertEquals(SessionSetupError.None, result)
        assertEquals(1, queries)
    }

    @Test
    fun hostInputRequiresLiveInjectionBackendAndExactConvergenceProfile() {
        val config = InputStreamConfiguration(
            inputKinds = CapabilityBits.INPUT_KEYBOARD,
            stablePresenceKinds = 0,
            featureFlags = CapabilityBits.INPUT_STATE_CONVERGENCE,
        )
        val unavailable = validator(inputInjection = ExactInputBackendAvailability { false })
        val available = validator(inputInjection = ExactInputBackendAvailability { true })
        val setup = listOf(SetupConfiguration.Input(channelId(1), config))
        val profile = profile(CapabilityBits.CHANNEL_INPUT)

        assertEquals(
            SessionSetupError.InputConfigurationUnavailable,
            unavailable.validate(SessionRole.Host, profile, setup),
        )
        assertEquals(SessionSetupError.None, available.validate(SessionRole.Host, profile, setup))
        assertTrue(config.isValid())
    }

    private fun validator(
        encoder: RecordingEncoderDiscovery = RecordingEncoderDiscovery(true),
        decoder: RecordingDecoderDiscovery = RecordingDecoderDiscovery(true, true),
        microphone: ExactAudioCaptureCapabilityQuery? = null,
        inputInjection: ExactInputBackendAvailability = ExactInputBackendAvailability { false },
    ) = AndroidExactStreamConfigurationValidator(
        videoEncoderDiscovery = encoder,
        videoDecoderDiscovery = decoder,
        microphoneCapture = microphone,
        inputInjectionAvailable = inputInjection,
    )

    private fun profile(channels: Int): NegotiatedCapabilityProfile {
        val video = channels and CapabilityBits.CHANNEL_VIDEO != 0
        val systemAudio = channels and CapabilityBits.CHANNEL_SYSTEM_AUDIO != 0
        val microphone = channels and CapabilityBits.CHANNEL_MICROPHONE_AUDIO != 0
        val audio = systemAudio || microphone
        val input = channels and CapabilityBits.CHANNEL_INPUT != 0
        return NegotiatedCapabilityProfile(
            selectedChannels = channels,
            eligiblePathKinds = CapabilityBits.PATH_LAN,
            secureDatagramBytes = 1_200,
            maxSessionChannels = 32,
            recoveryFlags = 0,
            videoCodec = if (video) NegotiatedCapabilityProfile.VIDEO_CODEC_AVC else 0,
            videoFlags = if (video) {
                CapabilityBits.VIDEO_LOW_LATENCY_DECODE or CapabilityBits.VIDEO_KEYFRAME_REQUEST
            } else {
                0
            },
            videoPayloadVersion = if (video) 1 else 0,
            videoMaxWidth = if (video) 3840 else 0,
            videoMaxHeight = if (video) 2160 else 0,
            videoMaxFps = if (video) 120 else 0,
            videoMaxBitrateBps = if (video) 50_000_000 else 0,
            audioCodec = if (audio) NegotiatedCapabilityProfile.AUDIO_CODEC_OPUS else 0,
            audioFrameDurationMask = if (audio) CapabilityBits.AUDIO_FRAME_5_MS else 0,
            audioPayloadVersion = if (audio) 1 else 0,
            audioSampleRateMask = if (audio) CapabilityBits.AUDIO_SAMPLE_RATE_48_KHZ else 0,
            systemAudioMaxChannels = if (systemAudio) 2 else 0,
            microphoneMaxChannels = if (microphone) 1 else 0,
            microphoneRoutingPolicy = if (microphone) {
                MicrophoneRoutingSelection.SeparatePerPeer
            } else {
                MicrophoneRoutingSelection.NotApplicable
            },
            inputPayloadVersion = if (input) 1 else 0,
            inputKinds = if (input) CapabilityBits.INPUT_KEYBOARD else 0,
            inputFeatureFlags = if (input) CapabilityBits.INPUT_STATE_CONVERGENCE else 0,
            stablePresenceKinds = 0,
        )
    }

    private fun channelId(value: Int) = ChannelId.requireValid(value.toUInt())

    private class RecordingEncoderDiscovery(
        private val supported: Boolean,
    ) : io.warpnect.platform.video.encoder.VideoEncoderDiscovery {
        var lastRequest: VideoEncoderRequest? = null
        var codecStarted = false

        override fun query(request: VideoEncoderRequest): VideoEncoderCapabilities {
            lastRequest = request
            val codec = VideoEncoderCodecInfo(
                "hardware-avc",
                "hardware-avc",
                VideoEncoderHardwareAcceleration.Hardware,
                false,
                true,
                false,
            )
            return VideoEncoderCapabilities(
                request,
                codec.takeIf { supported },
                VideoEncoderSupport(
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    true,
                    2,
                    2,
                    16,
                    4096,
                    16,
                    4096,
                    500,
                    50_000_000,
                ).takeIf { supported },
                error = if (supported) VideoEncoderError.None else VideoEncoderError.UnsupportedFrameRate,
            )
        }
    }

    private class RecordingDecoderDiscovery(
        private val supported: Boolean,
        private val lowLatency: Boolean,
    ) : io.warpnect.platform.video.decoder.VideoDecoderDiscovery {
        var lastConfig: VideoDecoderConfig? = null
        var codecStarted = false

        override fun query(config: VideoDecoderConfig): VideoDecoderCapabilities {
            lastConfig = config
            val codec = VideoDecoderCodecInfo(
                "hardware-avc",
                "hardware-avc",
                VideoDecoderHardwareAcceleration.Hardware,
                false,
                true,
                false,
                lowLatency,
            )
            return VideoDecoderCapabilities(
                config,
                codec.takeIf { supported },
                VideoDecoderSupport(
                    true,
                    true,
                    supported,
                    supported,
                    lowLatency,
                    2,
                    2,
                    16,
                    4096,
                    16,
                    4096,
                ).takeIf { supported },
                error = if (supported) VideoDecoderError.None else VideoDecoderError.UnsupportedFrameRate,
            )
        }
    }
}
