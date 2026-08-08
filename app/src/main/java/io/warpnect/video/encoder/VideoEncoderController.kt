package io.warpnect.video.encoder

interface VideoEncoderController : AutoCloseable {
    suspend fun queryCapabilities(request: VideoEncoderRequest): VideoEncoderCapabilities

    suspend fun prepare(request: VideoEncoderRequest, sink: EncodedVideoSink): VideoEncoderPrepareResult

    suspend fun start(): VideoEncoderStartResult

    suspend fun requestKeyFrame(): VideoEncoderControlResult

    suspend fun updateBitrate(bitrateBps: Int): VideoEncoderControlResult

    suspend fun stop(): VideoEncoderStopResult

    fun snapshot(): VideoEncoderSnapshot

    override fun close()
}
