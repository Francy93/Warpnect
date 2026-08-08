package io.warpnect.capture

data class CaptureRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

data class CaptureProjection(
    val sourceCrop: CaptureRect,
    val targetRect: CaptureRect,
    val orientation: Int,
)

object CaptureGeometry {
    fun computeProjection(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceRotation: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): CaptureProjection {
        require(sourceWidth > 0)
        require(sourceHeight > 0)
        require(targetWidth > 0)
        require(targetHeight > 0)
        require(sourceRotation in 0..3)

        val rotatedSourceWidth = if (sourceRotation % 2 == 0) sourceWidth else sourceHeight
        val rotatedSourceHeight = if (sourceRotation % 2 == 0) sourceHeight else sourceWidth
        val scale = minOf(
            targetWidth.toDouble() / rotatedSourceWidth.toDouble(),
            targetHeight.toDouble() / rotatedSourceHeight.toDouble(),
        )
        val projectedWidth = (rotatedSourceWidth * scale).toInt().coerceAtLeast(1)
        val projectedHeight = (rotatedSourceHeight * scale).toInt().coerceAtLeast(1)
        val left = (targetWidth - projectedWidth) / 2
        val top = (targetHeight - projectedHeight) / 2

        return CaptureProjection(
            sourceCrop = CaptureRect(0, 0, sourceWidth, sourceHeight),
            targetRect = CaptureRect(left, top, left + projectedWidth, top + projectedHeight),
            orientation = sourceRotation,
        )
    }
}
