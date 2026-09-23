package io.warpnect.platform.capture.privileged

import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureGeometry
import io.warpnect.capture.CaptureProjection
import io.warpnect.capture.CaptureRequest

/**
 * Plans a SurfaceControl projection from the logical display geometry supplied by DisplayInfo.
 *
 * DisplayInfo.logicalWidth and logicalHeight already describe the current display orientation.
 * Applying DisplayInfo.rotation again to setDisplayProjection would rotate that oriented image a
 * second time. Rotation changes are instead reflected by fresh logical dimensions when the
 * display monitor requests a capture reconfiguration.
 */
internal object SurfaceControlDisplayProjectionPlanner {
    fun plan(display: CaptureDisplayInfo, request: CaptureRequest): CaptureProjection =
        CaptureGeometry.computeProjection(
            sourceWidth = display.logicalWidth,
            sourceHeight = display.logicalHeight,
            sourceRotation = 0,
            targetWidth = request.outputWidth,
            targetHeight = request.outputHeight,
        )
}
