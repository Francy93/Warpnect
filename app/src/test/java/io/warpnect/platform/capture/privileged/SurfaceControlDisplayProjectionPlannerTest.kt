package io.warpnect.platform.capture.privileged

import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureRect
import io.warpnect.capture.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceControlDisplayProjectionPlannerTest {
    @Test
    fun currentLogicalLandscapeDimensionsAreNotRotatedAgain() {
        val projection = SurfaceControlDisplayProjectionPlanner.plan(
            display = CaptureDisplayInfo(
                displayId = 0,
                logicalWidth = 1280,
                logicalHeight = 800,
                rotation = 1,
                refreshRate = 60f,
                layerStack = 0,
            ),
            request = CaptureRequest(
                sourceDisplayId = 0,
                outputWidth = 1280,
                outputHeight = 720,
                followSourceRotation = true,
            ),
        )

        assertEquals(0, projection.orientation)
        assertEquals(CaptureRect(64, 0, 1216, 720), projection.targetRect)
    }
}
