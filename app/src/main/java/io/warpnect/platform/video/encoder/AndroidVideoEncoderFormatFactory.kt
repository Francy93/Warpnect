package io.warpnect.platform.video.encoder

import android.annotation.SuppressLint
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import io.warpnect.video.encoder.VideoEncoderFormatPlan
import io.warpnect.video.encoder.VideoEncoderFormatPlanner
import io.warpnect.video.encoder.VideoEncoderFormatSupport
import io.warpnect.video.encoder.VideoEncoderRequest

internal object AndroidVideoEncoderFormatFactory {
    fun support(): VideoEncoderFormatSupport = VideoEncoderFormatSupport(
        supportsPriority = true,
        supportsLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        supportsMaxBFrames = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        supportsMaxFpsToEncoder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    )

    @SuppressLint("InlinedApi")
    fun create(request: VideoEncoderRequest): MediaFormat {
        val plan = VideoEncoderFormatPlanner.build(request, support())
        return MediaFormat.createVideoFormat(plan.mimeType, plan.width, plan.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, plan.bitrateBps)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, plan.iFrameIntervalSeconds)
            plan.priority?.let { setInteger(MediaFormat.KEY_PRIORITY, it) }
            plan.latencyFrames?.let { setInteger(MediaFormat.KEY_LATENCY, it) }
            plan.maxBFrames?.let { setInteger(MediaFormat.KEY_MAX_B_FRAMES, it) }
            plan.maxFpsToEncoder?.let { setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, it) }
        }
    }

    fun plan(request: VideoEncoderRequest): VideoEncoderFormatPlan = VideoEncoderFormatPlanner.build(request, support())
}
