package io.warpnect.capture

object CaptureValidation {
    fun validate(request: CaptureRequest, targetSurfaceIsValid: Boolean): CaptureError = when {
        request.outputWidth <= 0 || request.outputHeight <= 0 -> CaptureError.InvalidCaptureDimensions
        !targetSurfaceIsValid -> CaptureError.InvalidTargetSurface
        else -> CaptureError.None
    }
}
