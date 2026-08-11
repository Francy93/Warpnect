package io.warpnect.audio.performance

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.encoder.AudioEncoderRequest
import io.warpnect.audio.session.AudioReceiverSessionConfig
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import io.warpnect.avsync.AvSyncConfig
import io.warpnect.avsync.AvSyncError

enum class AudioPerformanceConfigError {
    None,
    InvalidCodecFrameDuration,
    InvalidCaptureChunkDuration,
    InvalidRecoverableAudioAge,
    InvalidReorderWait,
    InvalidPlcLimit,
    InvalidReceiverCapacity,
    InvalidPlaybackCapacity,
    InvalidOboeBufferBursts,
    InvalidEncoderBitrate,
    InvalidEncoderComplexity,
    InvalidAvSyncConfig,
}

enum class AudioRecoveryPolicyKind {
    ImmediateFreshness,
    TinyReorderWindow,
}

sealed interface AudioRecoveryPolicy {
    val kind: AudioRecoveryPolicyKind
    val networkReorderWaitUs: Long
    val maxImmediatePlcFrames: Int
    val maxRecoverableAudioAgeUs: Long

    fun validate(codecFrameDurationUs: Int): AudioPerformanceConfigError

    data class ImmediateFreshness(
        override val maxImmediatePlcFrames: Int = DEFAULT_MAX_IMMEDIATE_PLC_FRAMES,
        override val maxRecoverableAudioAgeUs: Long = DEFAULT_MAX_RECOVERABLE_AUDIO_AGE_US,
    ) : AudioRecoveryPolicy {
        override val kind: AudioRecoveryPolicyKind = AudioRecoveryPolicyKind.ImmediateFreshness
        override val networkReorderWaitUs: Long = 0L

        override fun validate(codecFrameDurationUs: Int): AudioPerformanceConfigError =
            validateCommon(codecFrameDurationUs)
    }

    data class TinyReorderWindow(
        override val networkReorderWaitUs: Long,
        override val maxImmediatePlcFrames: Int = DEFAULT_MAX_IMMEDIATE_PLC_FRAMES,
        override val maxRecoverableAudioAgeUs: Long = DEFAULT_MAX_RECOVERABLE_AUDIO_AGE_US,
    ) : AudioRecoveryPolicy {
        override val kind: AudioRecoveryPolicyKind = AudioRecoveryPolicyKind.TinyReorderWindow

        override fun validate(codecFrameDurationUs: Int): AudioPerformanceConfigError {
            val common = validateCommon(codecFrameDurationUs)
            if (common != AudioPerformanceConfigError.None) return common
            if (networkReorderWaitUs <= 0L || networkReorderWaitUs > codecFrameDurationUs.toLong()) {
                return AudioPerformanceConfigError.InvalidReorderWait
            }
            return AudioPerformanceConfigError.None
        }
    }

    companion object {
        const val DEFAULT_MAX_IMMEDIATE_PLC_FRAMES = 2
        const val DEFAULT_MAX_RECOVERABLE_AUDIO_AGE_US = 10_000L
    }
}

data class AudioPerformanceConfig(
    val captureTargetChunkDurationUs: Int = 5_000,
    val codecFrameDurationUs: Int = AudioEncoderRequest.DEFAULT_FRAME_DURATION_US,
    val sampleRateHz: Int = AudioEncoderRequest.DEFAULT_SAMPLE_RATE_HZ,
    val microphoneMonoBitrateBps: Int = AudioEncoderRequest.MICROPHONE_MONO_BITRATE_BPS,
    val systemStereoBitrateBps: Int = AudioEncoderRequest.SYSTEM_AUDIO_STEREO_BITRATE_BPS,
    val bitrateMode: AudioBitrateMode = AudioBitrateMode.ConstantBitrate,
    val encoderComplexity: Int = AudioEncoderRequest.DEFAULT_COMPLEXITY,
    val recoveryPolicy: AudioRecoveryPolicy = AudioRecoveryPolicy.ImmediateFreshness(),
    val audioReassemblySlotCount: Int = AudioReceiverRuntimeConfig.DEFAULT_REASSEMBLY_SLOT_COUNT,
    val audioReadySlotCount: Int = AudioReceiverRuntimeConfig.DEFAULT_READY_SLOT_COUNT,
    val maxLogicalAudioPayloadSize: Int = AudioReceiverRuntimeConfig.DEFAULT_MAX_LOGICAL_AUDIO_PAYLOAD_SIZE,
    val playbackRingCapacityCodecFrames: Int = 4,
    val playbackStartThresholdCodecFrames: Int = 1,
    val playbackRequestedBufferBursts: Int = 2,
    val avSyncSampleIntervalUs: Long = 20_000L,
    val avSyncMinimumVideoCalibrationSamples: Int = 8,
    val avSyncMaxCrossMediaPathDifferenceUs: Long = 250_000L,
    val avSyncMaxTimestampRateErrorPpm: Long = 50_000L,
    val avSyncMaxStartupAudioSyncHoldUs: Long = AvSyncConfig.AUTO_STARTUP_HOLD_US,
    val avSyncMaxVideoScheduleAheadUs: Long = 20_000L,
) {
    fun validate(): AudioPerformanceConfigError {
        if (codecFrameDurationUs !in SUPPORTED_CODEC_FRAME_DURATIONS_US) {
            return AudioPerformanceConfigError.InvalidCodecFrameDuration
        }
        if (captureTargetChunkDurationUs !in SUPPORTED_CAPTURE_CHUNK_DURATIONS_US) {
            return AudioPerformanceConfigError.InvalidCaptureChunkDuration
        }
        val recoveryError = recoveryPolicy.validate(codecFrameDurationUs)
        if (recoveryError != AudioPerformanceConfigError.None) return recoveryError
        if (audioReassemblySlotCount <= 0 || audioReadySlotCount <= 0 || maxLogicalAudioPayloadSize <= 0) {
            return AudioPerformanceConfigError.InvalidReceiverCapacity
        }
        if (playbackRingCapacityCodecFrames <= 0 ||
            playbackStartThresholdCodecFrames <= 0 ||
            playbackStartThresholdCodecFrames > playbackRingCapacityCodecFrames
        ) {
            return AudioPerformanceConfigError.InvalidPlaybackCapacity
        }
        if (playbackRequestedBufferBursts <= 0 || playbackRequestedBufferBursts > 8) {
            return AudioPerformanceConfigError.InvalidOboeBufferBursts
        }
        if (microphoneMonoBitrateBps !in AudioEncoderRequest.MIN_BITRATE_BPS..AudioEncoderRequest.MAX_BITRATE_BPS ||
            systemStereoBitrateBps !in AudioEncoderRequest.MIN_BITRATE_BPS..AudioEncoderRequest.MAX_BITRATE_BPS
        ) {
            return AudioPerformanceConfigError.InvalidEncoderBitrate
        }
        if (encoderComplexity !in 0..10) {
            return AudioPerformanceConfigError.InvalidEncoderComplexity
        }
        val avSyncError = toAvSyncConfig().validate()
        if (avSyncError != AvSyncError.None) {
            return AudioPerformanceConfigError.InvalidAvSyncConfig
        }
        return AudioPerformanceConfigError.None
    }

    fun capacityDerivedStartupAudioSyncHoldUs(): Long {
        val extraFrames = playbackRingCapacityCodecFrames - playbackStartThresholdCodecFrames
        return maxOf(0, extraFrames) * codecFrameDurationUs.toLong()
    }

    fun effectiveStartupAudioSyncHoldUs(): Long =
        if (avSyncMaxStartupAudioSyncHoldUs == AvSyncConfig.AUTO_STARTUP_HOLD_US) {
            capacityDerivedStartupAudioSyncHoldUs()
        } else {
            minOf(avSyncMaxStartupAudioSyncHoldUs, capacityDerivedStartupAudioSyncHoldUs())
        }

    fun snapshot(): AudioPerformanceSnapshot = AudioPerformanceSnapshot(
        captureTargetChunkDurationUs = captureTargetChunkDurationUs,
        codecFrameDurationUs = codecFrameDurationUs,
        sampleRateHz = sampleRateHz,
        microphoneMonoBitrateBps = microphoneMonoBitrateBps,
        systemStereoBitrateBps = systemStereoBitrateBps,
        bitrateMode = bitrateMode,
        encoderComplexity = encoderComplexity,
        recoveryPolicyKind = recoveryPolicy.kind,
        networkReorderWaitUs = recoveryPolicy.networkReorderWaitUs,
        maxImmediatePlcFrames = recoveryPolicy.maxImmediatePlcFrames,
        maxRecoverableAudioAgeUs = recoveryPolicy.maxRecoverableAudioAgeUs,
        audioReassemblySlotCount = audioReassemblySlotCount,
        audioReadySlotCount = audioReadySlotCount,
        maxLogicalAudioPayloadSize = maxLogicalAudioPayloadSize,
        playbackRingCapacityCodecFrames = playbackRingCapacityCodecFrames,
        playbackStartThresholdCodecFrames = playbackStartThresholdCodecFrames,
        playbackRequestedBufferBursts = playbackRequestedBufferBursts,
        avSyncSampleIntervalUs = avSyncSampleIntervalUs,
        avSyncMinimumVideoCalibrationSamples = avSyncMinimumVideoCalibrationSamples,
        avSyncMaxCrossMediaPathDifferenceUs = avSyncMaxCrossMediaPathDifferenceUs,
        avSyncMaxTimestampRateErrorPpm = avSyncMaxTimestampRateErrorPpm,
        avSyncMaxStartupAudioSyncHoldUs = avSyncMaxStartupAudioSyncHoldUs,
        avSyncCapacityDerivedStartupAudioSyncHoldUs = capacityDerivedStartupAudioSyncHoldUs(),
        avSyncEffectiveStartupAudioSyncHoldUs = effectiveStartupAudioSyncHoldUs(),
        avSyncMaxVideoScheduleAheadUs = avSyncMaxVideoScheduleAheadUs,
    )

    fun toReceiverRuntimeConfig(
        source: AudioCaptureSource,
        localPort: Int,
        localAddress: String = "0.0.0.0",
        remoteAddress: String? = null,
        remotePort: Int = 0,
        restrictRemoteEndpoint: Boolean = false,
        maxWireDatagramSize: Int,
    ): AudioReceiverRuntimeConfig = AudioReceiverRuntimeConfig(
        source = source,
        localAddress = localAddress,
        localPort = localPort,
        remoteAddress = remoteAddress,
        remotePort = remotePort,
        restrictRemoteEndpoint = restrictRemoteEndpoint,
        maxWireDatagramSize = maxWireDatagramSize,
        maxLogicalAudioPayloadSize = maxLogicalAudioPayloadSize,
        reassemblySlotCount = audioReassemblySlotCount,
        readySlotCount = audioReadySlotCount,
    )

    fun toReceiverSessionConfig(receiverRuntimeConfig: AudioReceiverRuntimeConfig): AudioReceiverSessionConfig =
        AudioReceiverSessionConfig(
            receiverRuntimeConfig = receiverRuntimeConfig,
            playbackRingCapacityCodecFrames = playbackRingCapacityCodecFrames,
            playbackStartThresholdCodecFrames = playbackStartThresholdCodecFrames,
            playbackRequestedBufferBursts = playbackRequestedBufferBursts,
            maxImmediatePlcFrames = recoveryPolicy.maxImmediatePlcFrames,
        )

    fun toAvSyncConfig(
        enabled: Boolean = true,
        audioMasterSource: AudioCaptureSource = AudioCaptureSource.SystemAudio,
    ): AvSyncConfig = AvSyncConfig(
        enabled = enabled,
        audioMasterSource = audioMasterSource,
        minimumVideoCalibrationSamples = avSyncMinimumVideoCalibrationSamples,
        maxCrossMediaPathDifferenceUs = avSyncMaxCrossMediaPathDifferenceUs,
        maxTimestampRateErrorPpm = avSyncMaxTimestampRateErrorPpm,
        syncSampleIntervalUs = avSyncSampleIntervalUs,
        maxStartupAudioSyncHoldUs = avSyncMaxStartupAudioSyncHoldUs,
        maxVideoSyncScheduleAheadUs = avSyncMaxVideoScheduleAheadUs,
    )

    companion object {
        val UltraLowLatency = AudioPerformanceConfig()

        val SUPPORTED_CODEC_FRAME_DURATIONS_US = setOf(2_500, 5_000, 10_000, 20_000)
        val SUPPORTED_CAPTURE_CHUNK_DURATIONS_US = setOf(2_500, 5_000, 10_000)
    }
}

data class AudioPerformanceSnapshot(
    val captureTargetChunkDurationUs: Int,
    val codecFrameDurationUs: Int,
    val sampleRateHz: Int,
    val microphoneMonoBitrateBps: Int,
    val systemStereoBitrateBps: Int,
    val bitrateMode: AudioBitrateMode,
    val encoderComplexity: Int,
    val recoveryPolicyKind: AudioRecoveryPolicyKind,
    val networkReorderWaitUs: Long,
    val maxImmediatePlcFrames: Int,
    val maxRecoverableAudioAgeUs: Long,
    val audioReassemblySlotCount: Int,
    val audioReadySlotCount: Int,
    val maxLogicalAudioPayloadSize: Int,
    val playbackRingCapacityCodecFrames: Int,
    val playbackStartThresholdCodecFrames: Int,
    val playbackRequestedBufferBursts: Int,
    val avSyncSampleIntervalUs: Long,
    val avSyncMinimumVideoCalibrationSamples: Int,
    val avSyncMaxCrossMediaPathDifferenceUs: Long,
    val avSyncMaxTimestampRateErrorPpm: Long,
    val avSyncMaxStartupAudioSyncHoldUs: Long,
    val avSyncCapacityDerivedStartupAudioSyncHoldUs: Long,
    val avSyncEffectiveStartupAudioSyncHoldUs: Long,
    val avSyncMaxVideoScheduleAheadUs: Long,
)

private fun AudioRecoveryPolicy.validateCommon(codecFrameDurationUs: Int): AudioPerformanceConfigError {
    if (codecFrameDurationUs <= 0) return AudioPerformanceConfigError.InvalidCodecFrameDuration
    if (maxImmediatePlcFrames < 0) return AudioPerformanceConfigError.InvalidPlcLimit
    if (maxRecoverableAudioAgeUs < 0L) return AudioPerformanceConfigError.InvalidRecoverableAudioAge
    val maxPlcDurationUs = maxImmediatePlcFrames.toLong() * codecFrameDurationUs.toLong()
    if (maxRecoverableAudioAgeUs < maxPlcDurationUs) {
        return AudioPerformanceConfigError.InvalidRecoverableAudioAge
    }
    if (networkReorderWaitUs < 0L) return AudioPerformanceConfigError.InvalidReorderWait
    return AudioPerformanceConfigError.None
}
