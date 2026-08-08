package io.warpnect.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGeometryTest {
    @Test
    fun portraitSourceIntoPortraitTargetFillsTarget() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceRotation = 0,
            targetWidth = 720,
            targetHeight = 1280,
        )

        assertEquals(CaptureRect(0, 0, 1080, 1920), projection.sourceCrop)
        assertEquals(CaptureRect(0, 0, 720, 1280), projection.targetRect)
        assertEquals(0, projection.orientation)
    }

    @Test
    fun portraitSourceIntoLandscapeTargetLetterboxesWithoutStretching() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceRotation = 0,
            targetWidth = 1280,
            targetHeight = 720,
        )

        assertEquals(437, projection.targetRect.left)
        assertEquals(0, projection.targetRect.top)
        assertEquals(405, projection.targetRect.width)
        assertEquals(720, projection.targetRect.height)
    }

    @Test
    fun landscapeSourceIntoPortraitTargetLetterboxesWithoutStretching() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceRotation = 0,
            targetWidth = 720,
            targetHeight = 1280,
        )

        assertEquals(0, projection.targetRect.left)
        assertEquals(437, projection.targetRect.top)
        assertEquals(720, projection.targetRect.width)
        assertEquals(405, projection.targetRect.height)
    }

    @Test
    fun landscapeSourceIntoLandscapeTargetFillsTarget() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1920,
            sourceHeight = 1080,
            sourceRotation = 0,
            targetWidth = 1280,
            targetHeight = 720,
        )

        assertEquals(CaptureRect(0, 0, 1280, 720), projection.targetRect)
    }

    @Test
    fun rotation90SwapsAspectForProjection() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceRotation = 1,
            targetWidth = 1280,
            targetHeight = 720,
        )

        assertEquals(1, projection.orientation)
        assertEquals(CaptureRect(0, 0, 1080, 1920), projection.sourceCrop)
        assertEquals(CaptureRect(0, 0, 1280, 720), projection.targetRect)
    }

    @Test
    fun rotation180PreservesSourceAspect() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceRotation = 2,
            targetWidth = 720,
            targetHeight = 1280,
        )

        assertEquals(2, projection.orientation)
        assertEquals(CaptureRect(0, 0, 720, 1280), projection.targetRect)
    }

    @Test
    fun rotation270SwapsAspectForProjection() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1080,
            sourceHeight = 1920,
            sourceRotation = 3,
            targetWidth = 1280,
            targetHeight = 720,
        )

        assertEquals(3, projection.orientation)
        assertEquals(CaptureRect(0, 0, 1280, 720), projection.targetRect)
    }

    @Test
    fun squareSourceIntoWideTargetCentersOutput() {
        val projection = CaptureGeometry.computeProjection(
            sourceWidth = 1000,
            sourceHeight = 1000,
            sourceRotation = 0,
            targetWidth = 1200,
            targetHeight = 800,
        )

        assertEquals(200, projection.targetRect.left)
        assertEquals(0, projection.targetRect.top)
        assertEquals(800, projection.targetRect.width)
        assertEquals(800, projection.targetRect.height)
    }
}
