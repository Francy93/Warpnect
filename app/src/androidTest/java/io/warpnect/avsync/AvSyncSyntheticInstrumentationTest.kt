package io.warpnect.avsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.decoder.DecodedAudioFrameKind
import io.warpnect.audio.playback.AudioPlaybackError
import io.warpnect.audio.playback.AudioSourcePresentationAnchorResult
import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.render.VideoRenderDecision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvSyncSyntheticInstrumentationTest {
    @Test
    fun controlledCommonTimestampsReachSynchronizedAndScheduleVideo() {
        val clock = InstrumentedFakeClock(nowNs = 1_010_000_000L)
        val controller = DefaultAvSyncController(
            audioAnchorSource = InstrumentedAnchorSource(),
            clock = clock,
            startWorker = false,
        )

        assertEquals(AvSyncError.None, controller.start(config()).error)
        repeat(4) { index ->
            val videoPts = 1_000_000L + (index * 16_667L)
            controller.observeVideoDecoded(videoPts, videoPts + 50_000L)
        }
        controller.sampleOnceForTests()

        assertEquals(AvSyncState.Synchronized, controller.snapshot().state)
        assertEquals(
            VideoRenderDecision.RenderAtLocalTime(1_018_000_000L),
            controller.renderPolicy().decide(videoFrame(1_008_000L), nowNs = 1_010_500_000L),
        )
        controller.close()
    }

    @Test
    fun incompatibleVideoEpochFallsBackToImmediateRendering() {
        val controller = DefaultAvSyncController(
            audioAnchorSource = InstrumentedAnchorSource(),
            clock = InstrumentedFakeClock(nowNs = 1_010_000_000L),
            startWorker = false,
        )

        assertEquals(AvSyncError.None, controller.start(config()).error)
        repeat(4) { index ->
            controller.observeVideoDecoded(presentationTimeUs = index * 16_667L, readyLocalUs = 1_050_000L + index)
        }
        controller.sampleOnceForTests()

        assertEquals(VideoTimestampDomainQuality.Rejected, controller.snapshot().videoTimestampDomainQuality)
        assertEquals(
            VideoRenderDecision.RenderImmediately,
            controller.renderPolicy().decide(videoFrame(16_667L), nowNs = 1_010_500_000L),
        )
        controller.close()
    }

    private fun config(): AvSyncConfig = AvSyncConfig(
        minimumVideoCalibrationSamples = 4,
    )

    private fun videoFrame(presentationTimeUs: Long): DecodedVideoFrame = DecodedVideoFrame(
        presentationTimeUs = presentationTimeUs,
        flags = 0,
        outputFormatGeneration = 1,
        sourceFrameId = 1,
    )
}

private class InstrumentedFakeClock(
    private val nowNs: Long,
) : AvSyncClock {
    override fun nowNs(): Long = nowNs
}

private class InstrumentedAnchorSource : AudioPresentationAnchorSource {
    override fun querySourcePresentationAnchor(): AudioSourcePresentationAnchorResult =
        AudioSourcePresentationAnchorResult(
            error = AudioPlaybackError.None,
            valid = true,
            sourceContentTimeUs = 1_000_000L,
            sourceCaptureTimeUs = 1_002_500L,
            sourceFramePosition = 0,
            outputFramePosition = 0,
            localPresentationTimeNs = 1_010_000_000L,
            oboeFramePosition = 0,
            oboePresentationTimeNs = 1_010_000_000L,
            ageNs = 0,
            configGeneration = 1,
            sampleRateHz = 48_000,
            lookaheadSamples = 120,
            timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            discontinuityBefore = false,
            frameKind = DecodedAudioFrameKind.Normal,
        )
}
