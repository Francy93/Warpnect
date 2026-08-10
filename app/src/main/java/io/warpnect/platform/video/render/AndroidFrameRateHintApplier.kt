package io.warpnect.platform.video.render

import android.annotation.SuppressLint
import android.os.Build
import android.view.Surface
import io.warpnect.video.render.VideoFrameRateChangeStrategy
import io.warpnect.video.render.VideoFrameRateCompatibility
import io.warpnect.video.render.VideoFrameRateHintPlan

internal object AndroidFrameRateHintApplier {
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val supportsChangeStrategy: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @SuppressLint("NewApi")
    fun apply(surface: Surface, plan: VideoFrameRateHintPlan): Boolean {
        if (!isSupported || !surface.isValid) {
            return false
        }
        val compatibility = when (plan.compatibility) {
            VideoFrameRateCompatibility.FixedSource -> Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && plan.changeStrategy != null) {
                val strategy = when (plan.changeStrategy) {
                    VideoFrameRateChangeStrategy.OnlyIfSeamless -> Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                }
                surface.setFrameRate(plan.frameRateHz, compatibility, strategy)
            } else {
                surface.setFrameRate(plan.frameRateHz, compatibility)
            }
        }.isSuccess
    }
}
