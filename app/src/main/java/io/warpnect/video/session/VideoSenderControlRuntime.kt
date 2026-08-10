package io.warpnect.video.session

import io.warpnect.video.transport.VideoTransportController

interface VideoSenderControlRuntime : AutoCloseable {
    fun start(timeoutUs: Long): VideoSessionControlResult

    fun stop(): VideoSessionControlResult

    fun snapshot(): VideoSenderControlSnapshot

    override fun close() {
        stop()
    }
}

fun interface VideoKeyFrameRequestHandler {
    fun onKeyFrameRequested(): VideoSessionControlResult

    companion object {
        val None = VideoKeyFrameRequestHandler { VideoSessionControlResult.Success }
    }
}

fun interface VideoSenderControlRuntimeFactory {
    fun create(
        transportController: VideoTransportController,
        keyFrameRequestHandler: VideoKeyFrameRequestHandler,
    ): VideoSenderControlRuntime?

    companion object {
        val None = VideoSenderControlRuntimeFactory { _, _ -> null }
    }
}
