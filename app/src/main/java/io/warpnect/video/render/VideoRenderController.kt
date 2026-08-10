package io.warpnect.video.render

import io.warpnect.video.decoder.DecodedVideoSink

interface VideoRenderController : AutoCloseable {
    val decodedVideoSink: DecodedVideoSink

    fun setVideoGeometry(width: Int, height: Int): VideoRenderControlResult

    fun setPreferredFrameRate(frameRateHz: Float?): VideoRenderControlResult

    fun setRenderPolicy(policy: VideoRenderPolicy): VideoRenderControlResult

    fun currentTarget(): VideoRenderTarget?

    fun snapshot(): VideoRenderSnapshot

    override fun close()
}
