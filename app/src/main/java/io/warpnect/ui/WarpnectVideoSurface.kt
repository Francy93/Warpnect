package io.warpnect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView

@Composable
fun WarpnectVideoSurface(controller: AndroidVideoRenderController, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                WarpnectVideoSurfaceView(context).apply {
                    attachController(controller)
                }
            },
            update = { view ->
                view.attachController(controller)
                view.requestLayout()
            },
        )
    }
}
