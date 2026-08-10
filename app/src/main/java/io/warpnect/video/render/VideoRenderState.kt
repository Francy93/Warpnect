package io.warpnect.video.render

enum class VideoRenderState {
    WaitingForSurface,
    SurfaceAvailable,
    Rendering,
    SurfaceDestroyed,
    Closed,
}
