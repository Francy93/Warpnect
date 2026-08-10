package io.warpnect.platform.video.render

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.SurfaceView
import io.warpnect.video.render.VideoRenderGeometry

class WarpnectVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {
    var renderController: AndroidVideoRenderController? = null
        private set

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)
        val snapshot = renderController?.snapshot()
        val videoWidth = snapshot?.videoWidth
        val videoHeight = snapshot?.videoHeight
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth == null || videoHeight == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val rect = VideoRenderGeometry.aspectFit(videoWidth, videoHeight, containerWidth, containerHeight)
        setMeasuredDimension(rect.width, rect.height)
    }
}
