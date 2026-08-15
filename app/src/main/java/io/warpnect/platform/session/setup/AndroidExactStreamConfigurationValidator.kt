package io.warpnect.platform.session.setup

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.decoder.AudioDecoderConfig
import io.warpnect.audio.decoder.AudioDecoderError
import io.warpnect.audio.decoder.AudioDecoderValidation
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.encoder.AudioEncoderValidation
import io.warpnect.audio.performance.AudioPerformanceConfig
import io.warpnect.audio.performance.AudioPerformanceConfigError
import io.warpnect.audio.playback.AudioPlaybackConfig
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackValidation
import io.warpnect.input.reliability.InputPerformanceProfile
import io.warpnect.input.reliability.InputReliabilityConfig
import io.warpnect.input.reliability.InputReliabilityConfigurationError
import io.warpnect.platform.video.decoder.AndroidVideoDecoderDiscovery
import io.warpnect.platform.video.decoder.VideoDecoderDiscovery
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.VideoEncoderDiscovery
import io.warpnect.session.SessionRole
import io.warpnect.session.capability.CapabilityBits
import io.warpnect.session.capability.NegotiatedCapabilityProfile
import io.warpnect.session.setup.AudioStreamMode
import io.warpnect.session.setup.ExactStreamConfigurationValidator
import io.warpnect.session.setup.InputStreamConfiguration
import io.warpnect.session.setup.SessionSetupError
import io.warpnect.session.setup.SetupConfiguration
import io.warpnect.session.setup.VideoStreamMode
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderHardwareAcceleration
import io.warpnect.video.encoder.VideoEncoderRequest

fun interface ExactAudioCaptureCapabilityQuery {
    fun query(request: AudioCaptureRequest): AudioCaptureCapabilities
}

fun interface ExactInputBackendAvailability {
    fun isAvailable(config: InputStreamConfiguration): Boolean
}

/**
 * Side-effect-free RFC-005G validation over the existing Phase 2/3/4 planners and probes.
 * No codec, capture, playback, injection, or receiver worker is prepared or started here.
 */
class AndroidExactStreamConfigurationValidator(
    private val videoEncoderDiscovery: VideoEncoderDiscovery = AndroidVideoEncoderDiscovery(),
    private val videoDecoderDiscovery: VideoDecoderDiscovery = AndroidVideoDecoderDiscovery(),
    private val systemAudioCapture: ExactAudioCaptureCapabilityQuery? = null,
    private val microphoneCapture: ExactAudioCaptureCapabilityQuery? = null,
    private val inputCaptureAvailable: ExactInputBackendAvailability = ExactInputBackendAvailability { false },
    private val inputInjectionAvailable: ExactInputBackendAvailability = ExactInputBackendAvailability { false },
    private val audioPerformance: AudioPerformanceConfig = AudioPerformanceConfig.UltraLowLatency,
    private val videoIFrameIntervalSeconds: Float = 1f,
) : ExactStreamConfigurationValidator {
    override fun validate(
        localRole: SessionRole,
        profile: NegotiatedCapabilityProfile,
        configurations: List<SetupConfiguration>,
    ): SessionSetupError {
        if (!profile.isValid() || audioPerformance.validate() != AudioPerformanceConfigError.None) {
            return SessionSetupError.InvalidConfig
        }
        for (configuration in configurations) {
            val error = when (configuration) {
                is SetupConfiguration.Video -> validateVideo(localRole, configuration.mode)
                is SetupConfiguration.SystemAudio -> validateAudio(
                    localRole,
                    AudioCaptureSource.SystemAudio,
                    configuration.mode,
                )
                is SetupConfiguration.MicrophoneAudio -> validateAudio(
                    localRole,
                    AudioCaptureSource.MicrophoneAudio,
                    configuration.mode,
                )
                is SetupConfiguration.Input -> validateInput(localRole, configuration.config)
                is SetupConfiguration.Telemetry,
                is SetupConfiguration.Recovery,
                -> SessionSetupError.None
            }
            if (error != SessionSetupError.None) return error
        }
        return SessionSetupError.None
    }

    private fun validateVideo(localRole: SessionRole, mode: VideoStreamMode): SessionSetupError {
        if (!mode.isValid()) return SessionSetupError.ExactVideoConfigurationUnavailable
        return when (localRole) {
            SessionRole.Host -> {
                val capabilities = videoEncoderDiscovery.query(
                    VideoEncoderRequest(
                        width = mode.width,
                        height = mode.height,
                        frameRate = mode.fps,
                        bitrateBps = mode.bitrateBps.toInt(),
                        iFrameIntervalSeconds = videoIFrameIntervalSeconds,
                    ),
                )
                if (capabilities.isSupported &&
                    capabilities.selectedCodec?.hardwareAcceleration == VideoEncoderHardwareAcceleration.Hardware &&
                    capabilities.support?.surfaceInputSupported == true
                ) {
                    SessionSetupError.None
                } else {
                    SessionSetupError.ExactVideoConfigurationUnavailable
                }
            }
            SessionRole.Client -> {
                // Discovery inspects MediaCodec capabilities only; this sentinel CSD is never passed to a decoder.
                val capabilities = videoDecoderDiscovery.query(
                    VideoDecoderConfig(
                        width = mode.width,
                        height = mode.height,
                        expectedFrameRate = mode.fps,
                        configGeneration = 1,
                        codecSpecificData = listOf(byteArrayOf(1)),
                    ),
                )
                val lowLatencyRequired = mode.flags and CapabilityBits.VIDEO_LOW_LATENCY_DECODE != 0
                val lowLatencyAvailable = capabilities.support?.lowLatencyFeatureSupported == true ||
                    capabilities.selectedCodec?.lowLatencyFeatureSupported == true
                if (capabilities.isSupported &&
                    capabilities.selectedCodec?.hardwareAcceleration == VideoDecoderHardwareAcceleration.Hardware &&
                    (!lowLatencyRequired || lowLatencyAvailable)
                ) {
                    SessionSetupError.None
                } else {
                    SessionSetupError.ExactVideoConfigurationUnavailable
                }
            }
        }
    }

    private fun validateAudio(
        localRole: SessionRole,
        source: AudioCaptureSource,
        mode: AudioStreamMode,
    ): SessionSetupError {
        if (!mode.isValid()) return SessionSetupError.ExactAudioConfigurationUnavailable
        val sender = localRole == SessionRole.Host && source == AudioCaptureSource.SystemAudio ||
            localRole == SessionRole.Client && source == AudioCaptureSource.MicrophoneAudio
        return if (sender) validateAudioSender(source, mode) else validateAudioReceiver(source, mode)
    }

    private fun validateAudioSender(source: AudioCaptureSource, mode: AudioStreamMode): SessionSetupError {
        val captureQuery = when (source) {
            AudioCaptureSource.SystemAudio -> systemAudioCapture
            AudioCaptureSource.MicrophoneAudio -> microphoneCapture
        } ?: return SessionSetupError.ExactAudioConfigurationUnavailable
        val capture = captureQuery.query(
            AudioCaptureRequest(
                source = source,
                preferredSampleRateHz = mode.sampleRateHz,
                channelCount = mode.channelCount,
                targetChunkDurationUs = mode.frameDurationUs.toLong(),
                requireStrictFormat = true,
            ),
        )
        if (!capture.available || capture.selectedSampleRateHz != mode.sampleRateHz ||
            capture.channelCount != mode.channelCount
        ) {
            return SessionSetupError.ExactAudioConfigurationUnavailable
        }
        val encoder = AudioEncoderValidation.validate(
            AudioEncoderRequest(
                source = source,
                sampleRateHz = mode.sampleRateHz,
                channelCount = mode.channelCount,
                frameDurationUs = mode.frameDurationUs,
                bitrateBps = mode.bitrateBps.toInt(),
                complexity = audioPerformance.encoderComplexity,
            ),
        )
        return if (encoder == AudioEncoderError.None) {
            SessionSetupError.None
        } else {
            SessionSetupError.ExactAudioConfigurationUnavailable
        }
    }

    private fun validateAudioReceiver(source: AudioCaptureSource, mode: AudioStreamMode): SessionSetupError {
        val decoder = AudioDecoderValidation.validate(
            AudioDecoderConfig(
                source = source,
                configGeneration = 1,
                sampleRateHz = mode.sampleRateHz,
                channelCount = mode.channelCount,
                frameDurationUs = mode.frameDurationUs,
            ),
        )
        if (decoder != AudioDecoderError.None) return SessionSetupError.ExactAudioConfigurationUnavailable
        if (source == AudioCaptureSource.MicrophoneAudio) {
            return SessionSetupError.None
        }
        val frames = AudioPlaybackValidation.samplesPerFrame(mode.sampleRateHz, mode.frameDurationUs)
        val playback = AudioPlaybackValidation.validate(
            AudioPlaybackConfig(
                source = source,
                configGeneration = 1,
                sampleRateHz = mode.sampleRateHz,
                channelCount = mode.channelCount,
                frameDurationUs = mode.frameDurationUs,
                framesPerCodecFrame = frames,
                ringCapacityCodecFrames = audioPerformance.playbackRingCapacityCodecFrames,
                startThresholdCodecFrames = audioPerformance.playbackStartThresholdCodecFrames,
                requestedBufferBursts = audioPerformance.playbackRequestedBufferBursts,
            ),
        )
        return if (playback == AudioPlaybackError.None) {
            SessionSetupError.None
        } else {
            SessionSetupError.ExactAudioConfigurationUnavailable
        }
    }

    private fun validateInput(localRole: SessionRole, config: InputStreamConfiguration): SessionSetupError {
        val reliability = InputReliabilityConfig(
            profile = InputPerformanceProfile.UltraLowLatencyConvergent,
            criticalCopies = config.criticalCopies,
            resetCopies = config.resetCopies,
            recentTransportSequenceCapacity = config.transportDuplicateWindow,
            recentSemanticDuplicateCapacity = config.semanticIdentityCache,
            networkReorderWaitUs = config.networkReorderWaitUs,
        )
        val backendAvailable = when (localRole) {
            SessionRole.Host -> inputInjectionAvailable.isAvailable(config)
            SessionRole.Client -> inputCaptureAvailable.isAvailable(config)
        }
        val reliabilityValid = reliability.validate() == InputReliabilityConfigurationError.None
        return if (config.isValid() && reliabilityValid && backendAvailable) {
            SessionSetupError.None
        } else {
            SessionSetupError.InputConfigurationUnavailable
        }
    }
}
