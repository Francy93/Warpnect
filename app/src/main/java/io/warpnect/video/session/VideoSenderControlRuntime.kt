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

fun interface VideoSenderControlRuntimeFactory {
    fun create(transportController: VideoTransportController): VideoSenderControlRuntime?

    companion object {
        val None = VideoSenderControlRuntimeFactory { null }
    }
}
