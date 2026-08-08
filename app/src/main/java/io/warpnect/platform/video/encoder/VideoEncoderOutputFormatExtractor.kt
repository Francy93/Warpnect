package io.warpnect.platform.video.encoder

import android.annotation.SuppressLint
import android.media.MediaFormat
import android.os.Build
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderOutputFormat

internal object VideoEncoderOutputFormatExtractor {
    @SuppressLint("InlinedApi")
    fun extract(format: MediaFormat): VideoEncoderOutputFormat {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: VideoCodec.Avc.mimeType
        val width = optionalInteger(format, MediaFormat.KEY_WIDTH) ?: 0
        val height = optionalInteger(format, MediaFormat.KEY_HEIGHT) ?: 0
        val codec = when (mime) {
            VideoCodec.Avc.mimeType -> VideoCodec.Avc
            else -> VideoCodec.Avc
        }
        return VideoEncoderOutputFormat(
            codec = codec,
            mimeType = mime,
            width = width,
            height = height,
            frameRate = optionalInteger(format, MediaFormat.KEY_FRAME_RATE),
            bitrateBps = optionalInteger(format, MediaFormat.KEY_BIT_RATE),
            profile = optionalInteger(format, MediaFormat.KEY_PROFILE),
            level = optionalInteger(format, "level"),
            outputReorderDepth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                optionalInteger(format, MediaFormat.KEY_OUTPUT_REORDER_DEPTH)
            } else {
                null
            },
            reportedLatencyFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                optionalInteger(format, MediaFormat.KEY_LATENCY)
            } else {
                null
            },
            codecSpecificData = extractCsd(format),
        )
    }

    private fun optionalInteger(format: MediaFormat, key: String): Int? {
        return if (format.containsKey(key)) {
            runCatching { format.getInteger(key) }.getOrNull()
        } else {
            null
        }
    }

    private fun extractCsd(format: MediaFormat): List<ByteArray> {
        return (0 until MAX_CSD_BUFFERS).mapNotNull { index ->
            val key = "csd-$index"
            if (!format.containsKey(key)) {
                null
            } else {
                format.getByteBuffer(key)?.let { source ->
                    val duplicate = source.duplicate()
                    ByteArray(duplicate.remaining()).also { bytes ->
                        duplicate.get(bytes)
                    }
                }
            }
        }
    }

    private const val MAX_CSD_BUFFERS = 16
}
