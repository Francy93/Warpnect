package io.warpnect.video.decoder

data class DecodedVideoFrame(
    val presentationTimeUs: Long,
    val flags: Int,
    val outputFormatGeneration: Long,
    val sourceFrameId: Long?,
)

data class VideoDecoderOutputFormat(
    val width: Int?,
    val height: Int?,
    val colorFormat: Int?,
    val cropLeft: Int?,
    val cropTop: Int?,
    val cropRight: Int?,
    val cropBottom: Int?,
    val frameRate: Int?,
)

data class VideoDecoderFrameRenderedEvent(
    val presentationTimeUs: Long,
    val nanoTime: Long,
)

sealed interface DecodedVideoOutputAction {
    data object RenderNow : DecodedVideoOutputAction

    data object Drop : DecodedVideoOutputAction

    data class RenderAt(
        val timestampNs: Long,
    ) : DecodedVideoOutputAction
}

interface DecodedVideoSink {
    fun onFrameAvailable(frame: DecodedVideoFrame): DecodedVideoOutputAction

    fun onOutputFormatChanged(format: VideoDecoderOutputFormat) = Unit

    fun onFrameRendered(event: VideoDecoderFrameRenderedEvent) = Unit

    fun onDecoderError(error: VideoDecoderError) = Unit
}

internal sealed interface DecodedVideoOutputRelease {
    data class BooleanRelease(
        val render: Boolean,
    ) : DecodedVideoOutputRelease

    data class ScheduledRelease(
        val timestampNs: Long,
    ) : DecodedVideoOutputRelease
}

internal object DecodedVideoOutputReleasePlanner {
    fun plan(action: DecodedVideoOutputAction): DecodedVideoOutputRelease = when (action) {
        DecodedVideoOutputAction.RenderNow -> DecodedVideoOutputRelease.BooleanRelease(render = true)
        DecodedVideoOutputAction.Drop -> DecodedVideoOutputRelease.BooleanRelease(render = false)
        is DecodedVideoOutputAction.RenderAt -> DecodedVideoOutputRelease.ScheduledRelease(action.timestampNs)
    }
}
