package io.warpnect.platform.video.render

/** DEBUG-only observation of the production Client Surface attachment and render target lifecycle. */
interface VideoRenderDebugObserver {
    fun onRenderTargetAvailable() = Unit

    fun onRenderTargetAvailable(surfaceGeneration: Long) {
        onRenderTargetAvailable()
    }

    fun onRenderTargetDestroyed(surfaceGeneration: Long) = Unit

    fun onDecoderPrepared(surfaceGeneration: Long) = Unit

    fun onRemoteFrameRendered(surfaceGeneration: Long) = Unit

    fun onControllerAttached(surfaceWasValid: Boolean) = Unit

    companion object {
        val None = object : VideoRenderDebugObserver {}
    }
}
