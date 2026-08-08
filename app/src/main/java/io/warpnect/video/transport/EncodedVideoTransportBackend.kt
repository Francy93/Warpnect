package io.warpnect.video.transport

import io.warpnect.video.encoder.VideoEncoderOutputFormat
import java.nio.ByteBuffer

interface EncodedVideoTransportBackend {
    fun submitStreamConfig(format: VideoEncoderOutputFormat): VideoTransportError

    fun submitAccessUnit(
        buffer: ByteBuffer,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        keyframe: Boolean,
    ): VideoTransportError
}
