package io.warpnect.platform.video.render

/** DEBUG-only observation of the production Client Surface attachment and render target lifecycle. */
interface VideoRenderDebugObserver {
    fun onRenderTargetAvailable()

    fun onControllerAttached(surfaceWasValid: Boolean) = Unit

    companion object {
        val None = object : VideoRenderDebugObserver {
            override fun onRenderTargetAvailable() = Unit
        }
    }
}
