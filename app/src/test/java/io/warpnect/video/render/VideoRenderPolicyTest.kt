package io.warpnect.video.render

import io.warpnect.video.decoder.DecodedVideoFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRenderPolicyTest {
    private val frame = DecodedVideoFrame(
        presentationTimeUs = 33_333,
        flags = 0,
        outputFormatGeneration = 1,
        sourceFrameId = 7,
    )

    @Test
    fun immediatePolicyNeverBuffersForPts() {
        assertEquals(
            VideoRenderDecision.RenderImmediately,
            ImmediateLowLatencyVideoRenderPolicy.decide(frame, nowNs = 999_999),
        )
    }

    @Test
    fun scheduledPolicyReturnsFutureLocalDeadline() {
        val policy = ScheduledVideoRenderPolicy(
            deadlineProvider = LocalRenderDeadlineProvider { 2_000 },
        )

        assertEquals(
            VideoRenderDecision.RenderAtLocalTime(timestampNs = 2_000),
            policy.decide(frame, nowNs = 1_000),
        )
    }

    @Test
    fun scheduledPolicyUsesConfiguredLateAction() {
        val policy = ScheduledVideoRenderPolicy(
            deadlineProvider = LocalRenderDeadlineProvider { 1_000 },
            lateDecision = VideoRenderDecision.Drop,
        )

        assertEquals(VideoRenderDecision.Drop, policy.decide(frame, nowNs = 2_000))
    }

    @Test
    fun scheduledPolicyDropsWhenNoDeadlineExists() {
        val policy = ScheduledVideoRenderPolicy(
            deadlineProvider = LocalRenderDeadlineProvider { null },
        )

        assertEquals(VideoRenderDecision.Drop, policy.decide(frame, nowNs = 2_000))
    }
}
