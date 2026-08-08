package io.warpnect.platform.video.decoder

import android.media.MediaFormat
import io.warpnect.video.decoder.VideoDecoderOutputFormat

internal object VideoDecoderOutputFormatExtractor {
    fun extract(format: MediaFormat): VideoDecoderOutputFormat = VideoDecoderOutputFormat(
        width = optionalInteger(format, MediaFormat.KEY_WIDTH),
        height = optionalInteger(format, MediaFormat.KEY_HEIGHT),
        colorFormat = optionalInteger(format, MediaFormat.KEY_COLOR_FORMAT),
        cropLeft = optionalInteger(format, "crop-left"),
        cropTop = optionalInteger(format, "crop-top"),
        cropRight = optionalInteger(format, "crop-right"),
        cropBottom = optionalInteger(format, "crop-bottom"),
        frameRate = optionalInteger(format, MediaFormat.KEY_FRAME_RATE),
    )

    private fun optionalInteger(format: MediaFormat, key: String): Int? {
        return if (format.containsKey(key)) {
            runCatching { format.getInteger(key) }.getOrNull()
        } else {
            null
        }
    }
}
