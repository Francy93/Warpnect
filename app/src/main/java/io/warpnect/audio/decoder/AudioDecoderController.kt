package io.warpnect.audio.decoder

import java.nio.ByteBuffer

interface AudioDecoderController : AutoCloseable {
    fun prepare(config: AudioDecoderConfig, sink: DecodedPcmAudioSink): AudioDecoderResult

    fun start(): AudioDecoderResult

    fun decode(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        metadata: EncodedAudioFrameMetadata,
    ): AudioDecoderResult

    fun concealMissingFrame(metadata: MissingAudioFrameMetadata): AudioDecoderResult

    fun stop(): AudioDecoderResult

    fun snapshot(): AudioDecoderSnapshot

    override fun close()
}
