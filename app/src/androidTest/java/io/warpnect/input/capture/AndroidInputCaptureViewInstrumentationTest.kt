package io.warpnect.input.capture

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.MainActivity
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.platform.input.capture.AndroidInputCaptureController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInputCaptureViewInstrumentationTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: AndroidInputCaptureController? = null

    @After
    fun tearDown() {
        scenario?.close()
        controller?.close()
    }

    @Test
    fun viewDispatchesKeyboardTouchMouseScrollAndGamepadSynchronously() {
        val events = CopyOnWriteArrayList<Captured>()
        val sink = InputEventSink { eventTimeUs, event ->
            events += Captured(eventTimeUs, event)
            InputSinkResult.Accepted
        }

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            val captureController = AndroidInputCaptureController(activity)
            controller = captureController
            val view = WarpnectInputCaptureView(activity)
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(
                        view,
                        FrameLayout.LayoutParams(200, 100),
                    )
                },
            )
            view.layout(0, 0, 200, 100)
            captureController.prepare(view, InputCaptureConfig(), sink)
            captureController.start()
            view.requestFocus()

            val eventTime = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
            view.dispatchTouchEvent(MotionEvent.obtain(eventTime, eventTime + 1, MotionEvent.ACTION_DOWN, 100f, 50f, 0))
            view.dispatchGenericMotionEvent(mouseMove(eventTime + 2))
            view.dispatchGenericMotionEvent(scroll(eventTime + 3))
            view.dispatchKeyEvent(
                KeyEvent(eventTime, eventTime + 4, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0).apply {
                    source = InputDevice.SOURCE_GAMEPAD
                },
            )
        }

        assertTrue(events.any { it.event is InputKeyEvent })
        assertTrue(events.any { it.event is InputTouchFrame })
        assertTrue(events.any { it.event is InputPointerAbsolute })
        assertTrue(events.any { it.event is InputScroll })
        assertTrue(events.any { it.event is InputGamepadState })
        assertEquals(0L, events.first().eventTimeUs % 1_000L)
    }

    private fun mouseMove(eventTime: Long): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 40f
                y = 20f
            },
        )
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            MotionEvent.ACTION_HOVER_MOVE,
            1,
            properties,
            coords,
            0,
            MotionEvent.BUTTON_PRIMARY,
            1f,
            1f,
            1,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
    }

    private fun scroll(eventTime: Long): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = 40f
                y = 20f
                setAxisValue(MotionEvent.AXIS_VSCROLL, 0.5f)
            },
        )
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            MotionEvent.ACTION_SCROLL,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            1,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
    }

    private data class Captured(
        val eventTimeUs: Long,
        val event: WarpnectInputEvent,
    )
}
