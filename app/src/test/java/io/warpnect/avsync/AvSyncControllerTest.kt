package io.warpnect.avsync

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioPlaybackSnapshot
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.audio.session.AudioPlaybackStartGateDecision
import io.warpnect.audio.session.AudioReceiverSessionSnapshot
import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.render.VideoRenderDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvSyncControllerTest {
    @Test
    fun audioPresentationMathUsesRationalLookaheadAndOutputInterpolation() {
        assertEquals(2_500L, AudioPresentationMath.lookaheadDurationUs(120, 48000))
        assertEquals(5_000L, AudioPresentationMath.lookaheadDurationUs(120, 24000))
        assertEquals(97_500L, AudioPresentationMath.sourceContentTimeUs(100_000, 120, 48000))

        assertEquals(
            5_000_000L,
            AudioPresentationMath.interpolateLocalPresentationTimeNs(
                anchorOutputFramePosition = 9_760,
                timestampFramePosition = 10_000,
                timestampTimeNs = 10_000_000,
                sampleRateHz = 48_000,
            ),
        )
        assertEquals(
            15_000_000L,
            AudioPresentationMath.interpolateLocalPresentationTimeNs(
                anchorOutputFramePosition = 10_240,
                timestampFramePosition = 10_000,
                timestampTimeNs = 10_000_000,
                sampleRateHz = 48_000,
            ),
        )
    }

    @Test
    fun videoTimestampDomainQualifiesCompatibleSyntheticClocks() {
        val validator = VideoTimestampDomainValidator(config(minimumVideoCalibrationSamples = 4))
        repeat(8) { index ->
            val audioTime = 1_000_000L + (index * 5_000L)
            validator.addAudioObservation(audioTime, audioTime + 30_000L + (index % 2))
            val videoTime = 1_000_000L + (index * 16_667L)
            validator.addVideoObservation(videoTime, videoTime + 50_000L - (index % 2))
        }

        val snapshot = validator.snapshot(nowUs = 1_200_000L)

        assertEquals(VideoTimestampDomainQuality.SenderMonotonicCompatible, snapshot.quality)
        assertEquals(8, snapshot.videoCalibrationSamples)
        assertTrue(snapshot.pathOffsetDifferenceUs in 19_000L..21_000L)
    }

    @Test
    fun videoTimestampDomainRejectsDifferentEpochAndRateMismatch() {
        val epochValidator = VideoTimestampDomainValidator(config(minimumVideoCalibrationSamples = 4))
        repeat(4) { index ->
            val audioTime = 1_000_000_000L + (index * 5_000L)
            epochValidator.addAudioObservation(audioTime, audioTime + 30_000L)
            val videoPts = index * 16_667L
            epochValidator.addVideoObservation(videoPts, audioTime + 50_000L)
        }
        assertEquals(VideoTimestampDomainQuality.Rejected, epochValidator.snapshot(1_000_100_000L).quality)

        val rateValidator = VideoTimestampDomainValidator(config(minimumVideoCalibrationSamples = 4))
        repeat(4) { index ->
            val audioTime = 1_000_000L + (index * 5_000L)
            rateValidator.addAudioObservation(audioTime, audioTime + 30_000L)
            rateValidator.addVideoObservation(1_000_000L + (index * 10_000L), 1_050_000L + (index * 20_000L))
        }
        assertEquals(VideoTimestampDomainQuality.Rejected, rateValidator.snapshot(1_200_000L).quality)
    }

    @Test
    fun videoTimestampDomainBecomesStale() {
        val validator = VideoTimestampDomainValidator(
            config(minimumVideoCalibrationSamples = 4, maxSyncModelAgeUs = 20_000L),
        )
        repeat(4) { index ->
            val time = 1_000_000L + (index * 5_000L)
            validator.addAudioObservation(time, time + 20_000L)
            validator.addVideoObservation(time, time + 40_000L)
        }

        assertEquals(VideoTimestampDomainQuality.Stale, validator.snapshot(nowUs = 2_000_000L).quality)
    }

    @Test
    fun renderPolicySchedulesPastFutureClampedAndManualOffsetTargets() {
        val model = AvSyncModel(
            state = AvSyncState.Synchronized,
            audioPresentationQuality = AudioPresentationModelQuality.Valid,
            audioSourceTimeUs = 1_000_000L,
            audioPresentationTimeNs = 10_000_000_000L,
            audioTimestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            audioPresentationAnchorValid = true,
            videoTimestampDomainQuality = VideoTimestampDomainQuality.SenderMonotonicCompatible,
            modelUpdatedAtNs = 9_999_000_000L,
        )
        val policy = AvSynchronizedVideoRenderPolicy(
            configProvider = { config(maxVideoSyncScheduleAheadUs = 20_000L) },
            modelProvider = { model },
        )

        assertEquals(
            VideoRenderDecision.RenderAtLocalTime(10_010_000_000L),
            policy.decide(frame(1_010_000L), nowNs = 10_000_000_000L),
        )
        assertEquals(
            VideoRenderDecision.RenderImmediately,
            policy.decide(frame(1_010_000L), nowNs = 10_011_000_000L),
        )
        assertEquals(
            VideoRenderDecision.RenderAtLocalTime(10_020_000_000L),
            policy.decide(frame(1_100_000L), nowNs = 10_000_000_000L),
        )

        val offsetPolicy = AvSynchronizedVideoRenderPolicy(
            configProvider = { config(manualAvOffsetUs = 5_000L) },
            modelProvider = { model },
        )
        assertEquals(
            VideoRenderDecision.RenderAtLocalTime(10_015_000_000L),
            offsetPolicy.decide(frame(1_010_000L), nowNs = 10_000_000_000L),
        )
    }

    @Test
    fun renderPolicyFallsBackWhenModelIsStaleOrDomainUnqualified() {
        val staleModel = AvSyncModel(
            state = AvSyncState.Synchronized,
            audioPresentationQuality = AudioPresentationModelQuality.Valid,
            audioPresentationAnchorValid = true,
            videoTimestampDomainQuality = VideoTimestampDomainQuality.SenderMonotonicCompatible,
            modelUpdatedAtNs = 1_000L,
        )
        val policy = AvSynchronizedVideoRenderPolicy(
            configProvider = { config(maxSyncModelAgeUs = 10L) },
            modelProvider = { staleModel },
        )

        assertEquals(VideoRenderDecision.RenderImmediately, policy.decide(frame(1), nowNs = 1_000_000L))
    }

    @Test
    fun startupGateUsesExistingPlaybackRingSlackOnly() {
        val clock = FakeClock(nowNs = 1_000_000L)
        val gate = AvSyncPlaybackStartGate({ config() }, clock)
        val primed = receiverSnapshot(ringOccupancyFrames = 240)

        assertEquals(AudioPlaybackStartGateDecision.Hold, gate.evaluate(primed, clock.nowNs()))
        assertEquals(15_000L, gate.snapshot().startupHoldCapacityLimitUs)

        clock.nowNs = 5_000_000L
        gate.notifyVideoReady()
        assertEquals(AudioPlaybackStartGateDecision.Start, gate.evaluate(primed, clock.nowNs()))

        gate.onPlaybackReset()
        clock.nowNs = 1_000_000L
        assertEquals(AudioPlaybackStartGateDecision.Hold, gate.evaluate(primed, clock.nowNs()))
        clock.nowNs = 17_000_000L
        assertEquals(AudioPlaybackStartGateDecision.Start, gate.evaluate(primed, clock.nowNs()))
        assertTrue(gate.snapshot().startupHoldExpired)
    }

    @Test
    fun startupGateReleasesBeforeRingOverflow() {
        val gate = AvSyncPlaybackStartGate({ config() }, FakeClock(nowNs = 1_000_000L))

        assertEquals(
            AudioPlaybackStartGateDecision.Start,
            gate.evaluate(receiverSnapshot(ringOccupancyFrames = 720), nowNs = 1_000_000L),
        )
    }

    @Test
    fun controllerSamplesAudioAnchorsAndSynchronizesAfterVideoCalibration() {
        val clock = FakeClock(nowNs = 1_060_000_000L)
        val anchorSource = FakeAnchorSource()
        val controller = DefaultAvSyncController(
            audioAnchorSource = anchorSource,
            clock = clock,
            startWorker = false,
        )
        controller.start(config(minimumVideoCalibrationSamples = 4))
        repeat(4) { index ->
            val videoPts = 1_000_000L + (index * 16_667L)
            controller.observeVideoDecoded(videoPts, videoPts + 50_000L)
        }

        controller.sampleOnceForTests()

        val snapshot = controller.snapshot()
        assertEquals(AvSyncState.Synchronized, snapshot.state)
        assertEquals(VideoTimestampDomainQuality.SenderMonotonicCompatible, snapshot.videoTimestampDomainQuality)
        assertEquals(1L, snapshot.syncAcquisitions)
    }

    private fun config(
        minimumVideoCalibrationSamples: Int = 8,
        maxSyncModelAgeUs: Long = 100_000L,
        maxVideoSyncScheduleAheadUs: Long = 20_000L,
        manualAvOffsetUs: Long = 0L,
    ): AvSyncConfig = AvSyncConfig(
        minimumVideoCalibrationSamples = minimumVideoCalibrationSamples,
        maxSyncModelAgeUs = maxSyncModelAgeUs,
        maxVideoSyncScheduleAheadUs = maxVideoSyncScheduleAheadUs,
        manualAvOffsetUs = manualAvOffsetUs,
    )

    private fun frame(presentationTimeUs: Long): DecodedVideoFrame = DecodedVideoFrame(
        presentationTimeUs = presentationTimeUs,
        flags = 0,
        outputFormatGeneration = 1,
        sourceFrameId = 1,
    )

    private fun receiverSnapshot(ringOccupancyFrames: Int) = AudioReceiverSessionSnapshot(
        source = AudioCaptureSource.SystemAudio,
        sampleRateHz = 48000,
        channelCount = 1,
        frameDurationUs = 5000,
        samplesPerFrame = 240,
        playbackRingCapacityCodecFrames = 4,
        playbackStartThresholdCodecFrames = 1,
        playback = AudioPlaybackSnapshot(
            ringCapacityFrames = 960,
            ringOccupancyFrames = ringOccupancyFrames,
            framesPerCodecFrame = 240,
        ),
    )
}

private class FakeClock(
    var nowNs: Long,
) : AvSyncClock {
    override fun nowNs(): Long = nowNs
}

private class FakeAnchorSource : AudioPresentationAnchorSource {
    override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult =
        AudioSourcePresentationAnchorResult(
            error = AudioPlaybackError.None,
            valid = true,
            sourceContentTimeUs = 1_000_000L,
            sourceCaptureTimeUs = 1_002_500L,
            sourceFramePosition = 0,
            outputFramePosition = 0,
            localPresentationTimeNs = 1_032_500_000L,
            oboeFramePosition = 0,
            oboePresentationTimeNs = 1_032_500_000L,
            ageNs = 0,
            configGeneration = 1,
            sampleRateHz = 48000,
            lookaheadSamples = 120,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = false,
            frameKind = DecodedAudioFrameKind.Normal,
        )
}
