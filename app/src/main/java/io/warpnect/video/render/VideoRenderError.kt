package io.warpnect.video.render

enum class VideoRenderError {
    None,

    InvalidVideoGeometry,
    InvalidPreferredFrameRate,

    SurfaceUnavailable,
    SurfaceInvalid,
    SurfaceDestroyed,

    FrameRateHintFailed,

    InvalidRenderTimestamp,
    RenderPolicyFailure,

    Closed,
}
