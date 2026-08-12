package io.warpnect.video.render

import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable receiver-side geometry for the visible video content inside an input surface.
 *
 * The rectangle is produced by the same aspect-fit authority that lays out video. It is
 * intentionally endpoint-local metadata and never crosses the SCL input boundary.
 */
data class VideoViewportGeometry(
    val surfaceWidthPx: Int = 0,
    val surfaceHeightPx: Int = 0,
    val contentLeftPx: Int = 0,
    val contentTopPx: Int = 0,
    val contentWidthPx: Int = 0,
    val contentHeightPx: Int = 0,
    val videoWidthPx: Int = 0,
    val videoHeightPx: Int = 0,
    val surfaceGeneration: Long = 0L,
    val videoConfigGeneration: Long = 0L,
    val valid: Boolean = false,
) {
    val contentRightPx: Int
        get() = contentLeftPx + contentWidthPx

    val contentBottomPx: Int
        get() = contentTopPx + contentHeightPx

    fun isUsable(): Boolean = valid &&
        surfaceWidthPx > 0 &&
        surfaceHeightPx > 0 &&
        contentWidthPx > 0 &&
        contentHeightPx > 0 &&
        contentLeftPx >= 0 &&
        contentTopPx >= 0 &&
        contentLeftPx.toLong() + contentWidthPx.toLong() <= surfaceWidthPx.toLong() &&
        contentTopPx.toLong() + contentHeightPx.toLong() <= surfaceHeightPx.toLong() &&
        videoWidthPx > 0 &&
        videoHeightPx > 0
}

fun interface VideoViewportGeometryProvider {
    fun currentGeometry(): VideoViewportGeometry
}

/** Lock-free handoff for the renderer's immutable geometry snapshot. */
class VideoViewportGeometryStore(
    initialGeometry: VideoViewportGeometry = VideoViewportGeometry(),
) : VideoViewportGeometryProvider {
    private val geometryRef = AtomicReference(initialGeometry)

    override fun currentGeometry(): VideoViewportGeometry = geometryRef.get()

    fun update(geometry: VideoViewportGeometry) {
        geometryRef.set(geometry)
    }
}
