package io.warpnect.video.decoder

import java.nio.ByteBuffer

interface VideoDecoderInputSource {
    fun fillInput(target: ByteBuffer, capacity: Int): VideoDecoderInputResult
}

sealed interface VideoDecoderInputResult {
    data object NoData : VideoDecoderInputResult

    data class AccessUnit(
        val size: Int,
        val presentationTimeUs: Long,
        val configGeneration: Long,
        val frameId: Long,
        val isKeyFrame: Boolean,
    ) : VideoDecoderInputResult

    data object EndOfStream : VideoDecoderInputResult

    data class Failure(
        val error: VideoDecoderError,
    ) : VideoDecoderInputResult
}
