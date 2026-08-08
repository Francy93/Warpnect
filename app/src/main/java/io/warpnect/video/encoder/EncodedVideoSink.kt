package io.warpnect.video.encoder

import java.nio.ByteBuffer

interface EncodedVideoSink {
    fun onOutputFormatChanged(format: VideoEncoderOutputFormat)

    fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)

    fun onEncoderError(error: VideoEncoderError)
}
