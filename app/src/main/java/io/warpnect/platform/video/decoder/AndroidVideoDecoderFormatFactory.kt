package io.warpnect.platform.video.decoder

import android.annotation.SuppressLint
import android.media.MediaFormat
import android.os.Build
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderFormatPlan
import io.warpnect.video.decoder.VideoDecoderFormatPlanner
import io.warpnect.video.decoder.VideoDecoderFormatSupport
import java.nio.ByteBuffer

internal object AndroidVideoDecoderFormatFactory {
    fun support(): VideoDecoderFormatSupport = VideoDecoderFormatSupport(
        supportsLowLatencyKey = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
    )

    @SuppressLint("InlinedApi")
    fun create(config: VideoDecoderConfig, lowLatencyFeatureSupported: Boolean): MediaFormat {
        val plan = plan(config, lowLatencyFeatureSupported)
        return MediaFormat.createVideoFormat(plan.mimeType, plan.width, plan.height).apply {
            plan.codecSpecificData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", ByteBuffer.wrap(bytes.copyOf()))
            }
            plan.maxInputSizeBytes?.let { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, it) }
            if (plan.lowLatencyRequested) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
        }
    }

    fun plan(config: VideoDecoderConfig, lowLatencyFeatureSupported: Boolean): VideoDecoderFormatPlan =
        VideoDecoderFormatPlanner.build(
            config = config,
            lowLatencyFeatureSupported = lowLatencyFeatureSupported,
            support = support(),
        )
}
