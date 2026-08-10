package io.warpnect.video.render

data class VideoRenderRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

object VideoRenderGeometry {
    fun aspectFit(sourceWidth: Int, sourceHeight: Int, containerWidth: Int, containerHeight: Int): VideoRenderRect {
        require(sourceWidth > 0) { "sourceWidth must be positive" }
        require(sourceHeight > 0) { "sourceHeight must be positive" }
        require(containerWidth > 0) { "containerWidth must be positive" }
        require(containerHeight > 0) { "containerHeight must be positive" }

        val source = sourceWidth.toLong() * containerHeight.toLong()
        val container = containerWidth.toLong() * sourceHeight.toLong()
        val renderWidth: Int
        val renderHeight: Int
        if (source > container) {
            renderWidth = containerWidth
            renderHeight = ((containerWidth.toLong() * sourceHeight.toLong()) / sourceWidth.toLong()).toInt()
        } else {
            renderHeight = containerHeight
            renderWidth = ((containerHeight.toLong() * sourceWidth.toLong()) / sourceHeight.toLong()).toInt()
        }
        return VideoRenderRect(
            left = (containerWidth - renderWidth) / 2,
            top = (containerHeight - renderHeight) / 2,
            width = renderWidth.coerceAtLeast(1),
            height = renderHeight.coerceAtLeast(1),
        )
    }
}
