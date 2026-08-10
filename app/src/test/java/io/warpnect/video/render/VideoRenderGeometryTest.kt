package io.warpnect.video.render

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoRenderGeometryTest {
    @Test
    fun sixteenByNineFitsSixteenByNine() {
        assertEquals(
            VideoRenderRect(left = 0, top = 0, width = 1920, height = 1080),
            VideoRenderGeometry.aspectFit(1920, 1080, 1920, 1080),
        )
    }

    @Test
    fun sixteenByNineFitsPortraitWithLetterbox() {
        assertEquals(
            VideoRenderRect(left = 0, top = 656, width = 1080, height = 607),
            VideoRenderGeometry.aspectFit(1920, 1080, 1080, 1920),
        )
    }

    @Test
    fun portraitFitsLandscapeWithPillarbox() {
        assertEquals(
            VideoRenderRect(left = 656, top = 0, width = 607, height = 1080),
            VideoRenderGeometry.aspectFit(1080, 1920, 1920, 1080),
        )
    }

    @Test
    fun fourByThreeFitsSixteenByNine() {
        assertEquals(
            VideoRenderRect(left = 240, top = 0, width = 1440, height = 1080),
            VideoRenderGeometry.aspectFit(4, 3, 1920, 1080),
        )
    }

    @Test
    fun squareFitsWidescreen() {
        assertEquals(
            VideoRenderRect(left = 420, top = 0, width = 1080, height = 1080),
            VideoRenderGeometry.aspectFit(1, 1, 1920, 1080),
        )
    }

    @Test
    fun veryNarrowAndVeryWideRemainCentered() {
        assertEquals(
            VideoRenderRect(left = 933, top = 0, width = 54, height = 1080),
            VideoRenderGeometry.aspectFit(1, 20, 1920, 1080),
        )
        assertEquals(
            VideoRenderRect(left = 0, top = 492, width = 1920, height = 96),
            VideoRenderGeometry.aspectFit(20, 1, 1920, 1080),
        )
    }
}
