package io.warpnect.audio.performance

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioBitrateMode
import io.warpnect.audio.transport.AudioReceiverRuntimeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPerformanceConfigTest {
    @Test
    fun ultraLowLatencyProfileRecordsCurrentPhase3Defaults() {
        val config = AudioPerformanceConfig.UltraLowLatency
        val snapshot = config.snapshot()

        assertEquals(AudioPerformanceConfigError.None, config.validate())
        assertEquals(5_000, snapshot.captureTargetChunkDurationUs)
        assertEquals(5_000, snapshot.codecFrameDurationUs)
        assertEquals(48_000, snapshot.sampleRateHz)
        assertEquals(64_000, snapshot.microphoneMonoBitrateBps)
        assertEquals(128_000, snapshot.systemStereoBitrateBps)
        assertEquals(AudioBitrateMode.ConstantBitrate, snapshot.bitrateMode)
        assertEquals(5, snapshot.encoderComplexity)
        assertEquals(AudioRecoveryPolicyKind.ImmediateFreshness, snapshot.recoveryPolicyKind)
        assertEquals(0L, snapshot.networkReorderWaitUs)
        assertEquals(2, snapshot.maxImmediatePlcFrames)
        assertEquals(10_000L, snapshot.maxRecoverableAudioAgeUs)
        assertEquals(4, snapshot.audioReassemblySlotCount)
        assertEquals(4, snapshot.audioReadySlotCount)
        assertEquals(4_096, snapshot.maxLogicalAudioPayloadSize)
        assertEquals(4, snapshot.playbackRingCapacityCodecFrames)
        assertEquals(1, snapshot.playbackStartThresholdCodecFrames)
        assertEquals(2, snapshot.playbackRequestedBufferBursts)
        assertEquals(20_000L, snapshot.avSyncSampleIntervalUs)
        assertEquals(8, snapshot.avSyncMinimumVideoCalibrationSamples)
        assertEquals(250_000L, snapshot.avSyncMaxCrossMediaPathDifferenceUs)
        assertEquals(50_000L, snapshot.avSyncMaxTimestampRateErrorPpm)
        assertEquals(-1L, snapshot.avSyncMaxStartupAudioSyncHoldUs)
        assertEquals(15_000L, snapshot.avSyncCapacityDerivedStartupAudioSyncHoldUs)
        assertEquals(15_000L, snapshot.avSyncEffectiveStartupAudioSyncHoldUs)
        assertEquals(20_000L, snapshot.avSyncMaxVideoScheduleAheadUs)
    }

    @Test
    fun tinyReorderWindowIsBoundedByCodecFrameDuration() {
        assertEquals(
            AudioPerformanceConfigError.None,
            AudioPerformanceConfig(
                recoveryPolicy = AudioRecoveryPolicy.TinyReorderWindow(networkReorderWaitUs = 2_500),
            ).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidReorderWait,
            AudioPerformanceConfig(
                recoveryPolicy = AudioRecoveryPolicy.TinyReorderWindow(networkReorderWaitUs = 5_001),
            ).validate(),
        )
    }

    @Test
    fun recoverableAgeMustCoverConfiguredImmediatePlcBurst() {
        assertEquals(
            AudioPerformanceConfigError.InvalidRecoverableAudioAge,
            AudioPerformanceConfig(
                recoveryPolicy = AudioRecoveryPolicy.ImmediateFreshness(
                    maxImmediatePlcFrames = 3,
                    maxRecoverableAudioAgeUs = 10_000,
                ),
            ).validate(),
        )
    }

    @Test
    fun invalidSubsystemValuesAreRejected() {
        assertEquals(
            AudioPerformanceConfigError.InvalidCodecFrameDuration,
            AudioPerformanceConfig(codecFrameDurationUs = 7_000).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidCaptureChunkDuration,
            AudioPerformanceConfig(captureTargetChunkDurationUs = 7_000).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidReceiverCapacity,
            AudioPerformanceConfig(audioReadySlotCount = 0).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidPlaybackCapacity,
            AudioPerformanceConfig(playbackStartThresholdCodecFrames = 5).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidOboeBufferBursts,
            AudioPerformanceConfig(playbackRequestedBufferBursts = 9).validate(),
        )
        assertEquals(
            AudioPerformanceConfigError.InvalidEncoderComplexity,
            AudioPerformanceConfig(encoderComplexity = 11).validate(),
        )
    }

    @Test
    fun profileBuildsReceiverSessionAndAvSyncConfigsWithoutChangingDefaults() {
        val profile = AudioPerformanceConfig.UltraLowLatency
        val runtime = profile.toReceiverRuntimeConfig(
            source = AudioCaptureSource.SystemAudio,
            localPort = 40_000,
            maxWireDatagramSize = 1_200,
        )
        val session = profile.toReceiverSessionConfig(runtime)
        val avSync = profile.toAvSyncConfig()

        assertEquals(
            AudioReceiverRuntimeConfig.DEFAULT_REASSEMBLY_SLOT_COUNT,
            runtime.reassemblySlotCount,
        )
        assertEquals(AudioReceiverRuntimeConfig.DEFAULT_READY_SLOT_COUNT, runtime.readySlotCount)
        assertEquals(
            AudioReceiverRuntimeConfig.DEFAULT_MAX_LOGICAL_AUDIO_PAYLOAD_SIZE,
            runtime.maxLogicalAudioPayloadSize,
        )
        assertEquals(2, session.maxImmediatePlcFrames)
        assertEquals(4, session.playbackRingCapacityCodecFrames)
        assertEquals(1, session.playbackStartThresholdCodecFrames)
        assertEquals(2, session.playbackRequestedBufferBursts)
        assertTrue(avSync.enabled)
        assertEquals(20_000L, avSync.syncSampleIntervalUs)
        assertEquals(-1L, avSync.maxStartupAudioSyncHoldUs)
        assertEquals(20_000L, avSync.maxVideoSyncScheduleAheadUs)
    }
}
