package io.warpnect.video.render

import io.warpnect.video.decoder.DecodedVideoFrame

sealed interface VideoRenderDecision {
    data object RenderImmediately : VideoRenderDecision

    data object Drop : VideoRenderDecision

    data class RenderAtLocalTime(
        val timestampNs: Long,
    ) : VideoRenderDecision
}
interface VideoRenderPolicy {
    fun decide(frame: DecodedVideoFrame, nowNs: Long): VideoRenderDecision
}

object ImmediateLowLatencyVideoRenderPolicy : VideoRenderPolicy {
    override fun decide(frame: DecodedVideoFrame, nowNs: Long): VideoRenderDecision =
        VideoRenderDecision.RenderImmediately
}

fun interface LocalRenderDeadlineProvider {
    fun deadlineNs(frame: DecodedVideoFrame): Long?
}

class ScheduledVideoRenderPolicy(
    private val deadlineProvider: LocalRenderDeadlineProvider,
    private val lateDecision: VideoRenderDecision = VideoRenderDecision.RenderImmediately,
) : VideoRenderPolicy {
    override fun decide(frame: DecodedVideoFrame, nowNs: Long): VideoRenderDecision {
        val deadlineNs = deadlineProvider.deadlineNs(frame) ?: return VideoRenderDecision.Drop
        return if (deadlineNs < nowNs) {
            lateDecision
        } else {
            VideoRenderDecision.RenderAtLocalTime(deadlineNs)
        }
    }
}
