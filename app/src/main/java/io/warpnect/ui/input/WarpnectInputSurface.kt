package io.warpnect.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.capture.InputCaptureState
import io.warpnect.input.capture.InputEventSink
import io.warpnect.platform.input.capture.AndroidInputCaptureController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView

@Composable
fun WarpnectInputSurface(
    controller: AndroidInputCaptureController,
    config: InputCaptureConfig,
    sink: InputEventSink,
    modifier: Modifier = Modifier,
) {
    val captureView = remember { mutableStateOf<WarpnectInputCaptureView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WarpnectInputCaptureView(context).also { view ->
                captureView.value = view
                controller.prepare(view, config, sink)
            }
        },
        update = { view ->
            captureView.value = view
            if (controller.snapshot().state != InputCaptureState.Running) {
                controller.prepare(view, config, sink)
            }
        },
    )

    DisposableEffect(controller, captureView.value) {
        onDispose {
            controller.stop()
        }
    }
}
