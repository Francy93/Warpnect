package io.warpnect.input.transport

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.MainActivity
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.platform.input.capture.AndroidInputCaptureController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import io.warpnect.platform.input.transport.NativeSclInputTransportController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputTransportJniInstrumentationTest {
    private var transport: NativeSclInputTransportController? = null
    private var capture: AndroidInputCaptureController? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        capture?.close()
        transport?.close()
        scenario?.close()
    }

    @Test
    fun nativeSenderAcceptsPrimitiveAndPersistentTouchScratchSubmissions() {
        val controller = NativeSclInputTransportController()
        transport = controller
        assertTrue(controller.prepare(loopbackConfig()).isSuccess)
        assertTrue(controller.start().isSuccess)
        assertTrue(
            controller.submit(
                100,
                InputKeyEvent(1, 7, 4, InputKeyAction.Down, 0, 0),
            ).isSuccess,
        )
        assertTrue(
            controller.submit(
                101,
                InputTouchFrame(
                    InputDeviceKind.Touchscreen,
                    2,
                    InputTouchAction.Move,
                    INPUT_NO_ACTION_POINTER_ID,
                    listOf(InputTouchContact(0, InputTouchToolType.Finger, 0, 10, 20)),
                ),
            ).isSuccess,
        )
        assertEquals(2L, controller.snapshot().datagramsAttempted)
        assertEquals(2L, controller.snapshot().datagramsSent)
        assertTrue(controller.stop().isSuccess)
    }

    @Test
    fun captureCanSubmitToSclSinkWithoutAnIntermediateQueue() {
        val transportController = NativeSclInputTransportController()
        transport = transportController
        assertTrue(transportController.prepare(loopbackConfig()).isSuccess)
        assertTrue(transportController.start().isSuccess)
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            val captureController = AndroidInputCaptureController(activity)
            capture = captureController
            val view = WarpnectInputCaptureView(activity)
            activity.setContentView(FrameLayout(activity).apply { addView(view) })
            view.layout(0, 0, 200, 100)
            assertTrue(
                captureController.prepare(
                    view,
                    InputCaptureConfig(),
                    SclInputEventSink(transportController),
                ).isSuccess,
            )
            assertTrue(captureController.start().isSuccess)
            val time = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(
                KeyEvent(
                    time,
                    time,
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_A,
                    0,
                ),
            )
            captureController.stop()
        }
        assertTrue(transportController.snapshot().keyEvents >= 1L)
        assertTrue(transportController.snapshot().resetEvents >= 1L)
    }

    private fun loopbackConfig(): InputTransportConfig = InputTransportConfig(
        remoteAddress = "127.0.0.1",
        remotePort = 45_551,
    )
}
