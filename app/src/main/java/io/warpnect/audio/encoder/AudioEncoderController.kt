package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioTimestampQuality
import java.nio.ByteBuffer

interface AudioEncoderController : AutoCloseable {
    fun queryCapabilities(request: AudioEncoderRequest): AudioEncoderCapabilities

    fun prepare(request: AudioEncoderRequest, sink: EncodedAudioSink): AudioEncoderResult

    fun start(): AudioEncoderResult

    fun updateBitrate(bitrateBps: Int): AudioEncoderResult

    fun stop(): AudioEncoderResult

    fun snapshot(): AudioEncoderSnapshot

    override fun close()
}

interface PcmSubmittingAudioEncoderController : AudioEncoderController {
    fun submitPcm(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        frameCount: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
    ): AudioEncoderError

    fun reportInputError(error: AudioEncoderError): AudioEncoderError
}
