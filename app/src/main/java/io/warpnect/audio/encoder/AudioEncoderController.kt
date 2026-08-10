package io.warpnect.audio.encoder

interface AudioEncoderController : AutoCloseable {
    fun queryCapabilities(request: AudioEncoderRequest): AudioEncoderCapabilities

    fun prepare(request: AudioEncoderRequest, sink: EncodedAudioSink): AudioEncoderResult

    fun start(): AudioEncoderResult

    fun updateBitrate(bitrateBps: Int): AudioEncoderResult

    fun stop(): AudioEncoderResult

    fun snapshot(): AudioEncoderSnapshot

    override fun close()
}
