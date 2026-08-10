package io.warpnect.video.render

import android.view.Surface

data class VideoRenderTarget(
    val surface: Surface,
    val surfaceGeneration: Long,
    val width: Int,
    val height: Int,
)

interface VideoRenderTargetListener {
    fun onRenderTargetAvailable(target: VideoRenderTarget) = Unit

    fun onRenderTargetChanged(target: VideoRenderTarget) = Unit

    fun onRenderTargetDestroyed(surfaceGeneration: Long) = Unit
}
