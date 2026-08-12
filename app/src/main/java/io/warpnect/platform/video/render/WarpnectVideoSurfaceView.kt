package io.warpnect.platform.video.render

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.SurfaceView
import io.warpnect.video.render.VideoRenderGeometry
import io.warpnect.video.render.VideoViewportGeometry

class WarpnectVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {
    var renderController: AndroidVideoRenderController? = null
        private set

    @Volatile
    private var latestViewportGeometry = VideoViewportGeometry()

    private var videoConfigGeneration = 0L
    private var hasExplicitVideoConfigGeneration = false
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    private var viewportGeometryListener: ((VideoViewportGeometry) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun attachController(controller: AndroidVideoRenderController) {
        if (renderController === controller) {
            return
        }
        renderController = controller
        controller.attach(this)
        requestLayout()
    }

    /**
     * Exposes the exact aspect-fit content rectangle used by this renderer for input mapping.
     * Callers only receive immutable metadata; no video buffers are exposed.
     */
    fun viewportGeometry(): VideoViewportGeometry = latestViewportGeometry

    fun setViewportGeometryListener(listener: ((VideoViewportGeometry) -> Unit)?) {
        viewportGeometryListener = listener
        listener?.invoke(latestViewportGeometry)
    }

    /**
     * Allows the owning video session to supply its real config generation when available.
     * The local geometry generation remains monotonic when that session hook is not wired yet.
     */
    fun updateVideoConfigGeneration(generation: Long) {
        if (generation <= 0L || generation == videoConfigGeneration) {
            return
        }
        videoConfigGeneration = generation
        hasExplicitVideoConfigGeneration = true
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)
        val snapshot = renderController?.snapshot()
        val videoWidth = snapshot?.videoWidth
        val videoHeight = snapshot?.videoHeight
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth == null || videoHeight == null) {
            publishViewportGeometry(
                VideoViewportGeometry(
                    surfaceWidthPx = containerWidth.coerceAtLeast(0),
                    surfaceHeightPx = containerHeight.coerceAtLeast(0),
                    videoWidthPx = videoWidth ?: 0,
                    videoHeightPx = videoHeight ?: 0,
                    surfaceGeneration = snapshot?.surfaceGeneration ?: 0L,
                    videoConfigGeneration = videoConfigGeneration,
                ),
            )
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if ((videoWidth != lastVideoWidth || videoHeight != lastVideoHeight) && !hasExplicitVideoConfigGeneration) {
            lastVideoWidth = videoWidth
            lastVideoHeight = videoHeight
            if (videoConfigGeneration == 0L || videoConfigGeneration == Long.MAX_VALUE) {
                videoConfigGeneration = 1L
            } else {
                videoConfigGeneration += 1L
            }
        }
        val rect = VideoRenderGeometry.aspectFit(videoWidth, videoHeight, containerWidth, containerHeight)
        publishViewportGeometry(
            VideoRenderGeometry.viewportGeometry(
                sourceWidth = videoWidth,
                sourceHeight = videoHeight,
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                surfaceGeneration = snapshot.surfaceGeneration,
                videoConfigGeneration = videoConfigGeneration,
            ),
        )
        setMeasuredDimension(rect.width, rect.height)
    }

    private fun publishViewportGeometry(geometry: VideoViewportGeometry) {
        if (geometry == latestViewportGeometry) {
            return
        }
        latestViewportGeometry = geometry
        viewportGeometryListener?.invoke(geometry)
    }
}
