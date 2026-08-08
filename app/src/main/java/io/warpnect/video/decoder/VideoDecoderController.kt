package io.warpnect.video.decoder

import android.view.Surface

interface VideoDecoderController : AutoCloseable {
    fun queryCapabilities(config: VideoDecoderConfig): VideoDecoderCapabilities

    suspend fun prepare(
        config: VideoDecoderConfig,
        outputSurface: Surface,
        inputSource: VideoDecoderInputSource,
        outputSink: DecodedVideoSink,
    ): VideoDecoderPrepareResult

    suspend fun start(): VideoDecoderStartResult

    fun notifyInputAvailable()

    suspend fun signalEndOfStream(): VideoDecoderControlResult

    suspend fun stop(): VideoDecoderStopResult

    fun snapshot(): VideoDecoderSnapshot

    override fun close()
}
