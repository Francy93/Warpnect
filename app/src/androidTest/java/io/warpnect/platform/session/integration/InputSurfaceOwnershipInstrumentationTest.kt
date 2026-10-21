package io.warpnect.platform.session.integration

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.MainActivity
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.capture.InputCaptureState
import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.platform.input.capture.AndroidInputCaptureController
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputSurfaceOwnershipInstrumentationTest {
    private val resources = AndroidSessionUiResources()
    private val events = mutableListOf<WarpnectInputEvent>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: AndroidInputCaptureController? = null
    private var binding: AutoCloseable? = null

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            binding?.close()
            controller?.close()
        }
        scenario?.close()
    }

    @Test
    fun activityRecreationRebindsWithoutRestartingCaptureAndIgnoresLateOldViewClear() {
        lateinit var oldActivity: MainActivity
        lateinit var oldView: WarpnectInputCaptureView
        val launched = launch()
        launched.onActivity { activity ->
            oldActivity = activity
            oldView = attach(activity)
            start(activity, oldView)
            assertTrue(key(oldView))
        }
        launched.recreate()
        launched.onActivity { activity ->
            assertNotSame(oldActivity, activity)
            val replacement = attach(activity)
            resources.clearClientInputSurface(oldView)
            val before = keys()
            key(oldView)
            assertEquals(before, keys())
            assertTrue(key(replacement))
            assertEquals(before + 1, keys())
            assertEquals(InputCaptureState.Running, requireNotNull(controller).snapshot().state)
            assertEquals(2L, requireNotNull(controller).snapshot().keyEventsCaptured)
            assertTrue(events.filterIsInstance<InputResetState>().any { it.reason == InputResetReason.FocusLost })
        }
    }

    @Test
    fun removedSurfaceDoesNotCaptureAndReopenKeepsSameSessionSink() {
        launch().onActivity { activity ->
            val oldView = attach(activity)
            start(activity, oldView)
            resources.clearClientInputSurface(oldView)
            val before = keys()
            key(oldView)
            assertEquals(before, keys())
            val replacement = attach(activity)
            assertTrue(key(replacement))
            assertEquals(before + 1, keys())
            assertEquals(InputCaptureState.Running, requireNotNull(controller).snapshot().state)
        }
    }

    @Test
    fun pendingStartUsesNewestSurfaceInsteadOfRetainingObsoleteActivityView() {
        launch().onActivity { activity ->
            val oldView = attach(activity)
            val capture = createController(activity)
            val replacement = attach(activity)
            assertTrue(capture.prepare(oldView, InputCaptureConfig(), sink()).isSuccess)
            assertTrue(capture.start().isSuccess)
            val before = keys()
            key(oldView)
            assertEquals(before, keys())
            assertTrue(key(replacement))
            assertEquals(before + 1, keys())
        }
    }

    @Test
    fun bindingIsExclusiveAndOldOwnerCannotClearNextSessionBinding() {
        launch().onActivity { activity ->
            val view = attach(activity)
            val first = createController(activity)
            val oldBinding = requireNotNull(binding)
            val next = AndroidInputCaptureController(activity, surfaceProvider = resources::inputCaptureSurface)
            assertNull(resources.bindClientInputCapture(next))
            assertTrue(first.prepare(view, InputCaptureConfig(), sink()).isSuccess)
            assertTrue(first.start().isSuccess)
            oldBinding.close()
            first.close()
            binding = requireNotNull(resources.bindClientInputCapture(next))
            controller = next
            assertTrue(next.prepare(view, InputCaptureConfig(), sink()).isSuccess)
            assertTrue(next.start().isSuccess)
            oldBinding.close()
            val replacement = attach(activity)
            assertTrue(key(replacement))
            assertEquals(1, keys())
            next.close()
            val before = keys()
            assertFalse(key(replacement))
            assertEquals(before, keys())
        }
    }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also {
        scenario = it
    }

    private fun attach(activity: MainActivity): WarpnectInputCaptureView = WarpnectInputCaptureView(activity).also {
        activity.setContentView(FrameLayout(activity).apply { addView(it, FrameLayout.LayoutParams(200, 100)) })
        it.layout(0, 0, 200, 100)
        resources.attachClientInputSurface(it)
    }

    private fun createController(activity: MainActivity): AndroidInputCaptureController =
        AndroidInputCaptureController(activity, surfaceProvider = resources::inputCaptureSurface).also {
            controller = it
            binding = requireNotNull(resources.bindClientInputCapture(it))
        }

    private fun start(activity: MainActivity, view: WarpnectInputCaptureView) {
        val capture = createController(activity)
        assertTrue(capture.prepare(view, InputCaptureConfig(), sink()).isSuccess)
        assertTrue(capture.start().isSuccess)
    }

    private fun sink() = InputEventSink { _, event ->
        events += event
        InputSinkResult.Accepted
    }

    private fun keys(): Int = events.count { it is InputKeyEvent }

    private fun key(view: WarpnectInputCaptureView): Boolean {
        val now = SystemClock.uptimeMillis()
        return view.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))
    }
}
