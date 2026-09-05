package io.warpnect.debug.input

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.platform.input.injection.ShizukuInputInjectionGateway
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/** Debug-only owned target that exercises the production gateway and UserService end to end. */
class InputProductionValidationActivity : Activity() {
    private val started = AtomicBoolean(false)
    private val observedKeyEvents = AtomicInteger(0)
    private val observedTouchEvents = AtomicInteger(0)
    private val observedPointerEvents = AtomicInteger(0)
    private val observedJoystickEvents = AtomicInteger(0)

    @Volatile private var gateway: ShizukuInputInjectionGateway? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Warpnect production input validation" })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && started.compareAndSet(false, true)) {
            @Suppress("DEPRECATION")
            val displayId = windowManager.defaultDisplay.displayId
            val x = window.decorView.width.coerceAtLeast(1) / 2f
            val y = window.decorView.height.coerceAtLeast(1) / 2f
            Thread { exerciseProductionGateway(displayId, x, y) }.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F1) {
            observedKeyEvents.incrementAndGet()
            Log.i(TAG, "INPUT_PRODUCTION_TARGET_KEY_OBSERVED")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            observedTouchEvents.incrementAndGet()
            Log.i(TAG, "INPUT_PRODUCTION_TARGET_TOUCH_OBSERVED")
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_MOUSE) -> {
            observedPointerEvents.incrementAndGet()
            Log.i(TAG, "INPUT_PRODUCTION_TARGET_POINTER_OBSERVED")
            true
        }
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> {
            observedJoystickEvents.incrementAndGet()
            Log.i(TAG, "INPUT_PRODUCTION_TARGET_JOYSTICK_OBSERVED")
            true
        }
        else -> super.onGenericMotionEvent(event)
    }

    override fun onDestroy() {
        gateway?.close()
        gateway = null
        super.onDestroy()
    }

    private fun exerciseProductionGateway(displayId: Int, xPx: Float, yPx: Float) {
        val activeGateway = ShizukuInputInjectionGateway(applicationContext) {
            Log.i(TAG, "INPUT_PRODUCTION_GATEWAY_SERVICE_DIED")
        }
        gateway = activeGateway
        try {
            val capabilities = runBlocking { activeGateway.queryCapabilities() }
            Log.i(
                TAG,
                "INPUT_PRODUCTION_CAPABILITIES api=${capabilities.inputManagerApiResolved} " +
                    "key=${capabilities.keyInjectionSupported} " +
                    "touch=${capabilities.touchInjectionSupported} " +
                    "pointer=${capabilities.pointerInjectionSupported} " +
                    "joystick=${capabilities.joystickInjectionSupported} " +
                    "uid=${capabilities.privilegedUid} error=${capabilities.lastError.name}",
            )
            val prepare = runBlocking { activeGateway.prepare(InputInjectionConfig()) }
            if (prepare != InputInjectionError.None) {
                Log.i(TAG, "INPUT_PRODUCTION_PREPARE_FAILED error=${prepare.name}")
                return
            }
            val start = activeGateway.start()
            if (start != InputInjectionError.None) {
                Log.i(TAG, "INPUT_PRODUCTION_START_FAILED error=${start.name}")
                return
            }
            logResult("KEY_DOWN", activeGateway.injectKey(key(displayId, KeyEvent.ACTION_DOWN)))
            logResult("KEY_UP", activeGateway.injectKey(key(displayId, KeyEvent.ACTION_UP)))
            Thread.sleep(EVENT_SETTLE_MS)
            logResult("TOUCH_DOWN", activeGateway.injectTouch(touch(displayId, xPx, yPx, MotionEvent.ACTION_DOWN)))
            logResult("TOUCH_UP", activeGateway.injectTouch(touch(displayId, xPx, yPx, MotionEvent.ACTION_UP)))
            Thread.sleep(EVENT_SETTLE_MS)
            logResult("POINTER", activeGateway.injectPointer(pointer(displayId, xPx, yPx)))
            Thread.sleep(EVENT_SETTLE_MS)
            logResult("JOYSTICK", activeGateway.injectJoystick(joystick(displayId)))
            Thread.sleep(EVENT_SETTLE_MS)
            Log.i(
                TAG,
                "INPUT_PRODUCTION_OBSERVED key=${observedKeyEvents.get()} touch=${observedTouchEvents.get()} " +
                    "pointer=${observedPointerEvents.get()} joystick=${observedJoystickEvents.get()}",
            )
        } finally {
            runCatching { activeGateway.stop(resetAll = true) }
            activeGateway.close()
            gateway = null
            runOnUiThread(::finish)
        }
    }

    private fun key(displayId: Int, action: Int): AndroidKeyInjectionEvent = AndroidKeyInjectionEvent(
        stateSlot = 0,
        sourceEventTimeUs = nowUs(),
        action = action,
        keyCode = KeyEvent.KEYCODE_F1,
        source = AndroidInjectionConstants.SOURCE_KEYBOARD,
        displayId = displayId,
    )

    private fun touch(displayId: Int, xPx: Float, yPx: Float, action: Int): AndroidTouchInjectionEvent =
        AndroidTouchInjectionEvent(
            stateSlot = 1,
            sourceEventTimeUs = nowUs(),
            actionMasked = action,
            actionIndex = 0,
            pointers = arrayOf(
                AndroidTouchPointer(
                    pointerId = 0,
                    toolType = MotionEvent.TOOL_TYPE_FINGER,
                    xPx = xPx,
                    yPx = yPx,
                ),
            ),
            source = AndroidInjectionConstants.SOURCE_TOUCHSCREEN,
            displayId = displayId,
        )

    private fun pointer(displayId: Int, xPx: Float, yPx: Float): AndroidPointerInjectionEvent =
        AndroidPointerInjectionEvent(
            stateSlot = 2,
            sourceEventTimeUs = nowUs(),
            action = MotionEvent.ACTION_HOVER_MOVE,
            xPx = xPx,
            yPx = yPx,
            source = AndroidInjectionConstants.SOURCE_MOUSE,
            displayId = displayId,
        )

    private fun joystick(displayId: Int): AndroidJoystickInjectionEvent = AndroidJoystickInjectionEvent(
        stateSlot = 3,
        sourceEventTimeUs = nowUs(),
        leftX = 0.25f,
        leftY = -0.25f,
        rightX = 0f,
        rightY = 0f,
        leftTrigger = 0f,
        rightTrigger = 0f,
        hatX = 0f,
        hatY = 0f,
        source = AndroidInjectionConstants.SOURCE_JOYSTICK,
        displayId = displayId,
    )

    private fun logResult(name: String, code: Int) {
        Log.i(TAG, "INPUT_PRODUCTION_$name result=${InputInjectionServiceResult.fromCode(code).name}")
    }

    private fun nowUs(): Long = SystemClock.uptimeMillis() * 1_000L

    private companion object {
        const val EVENT_SETTLE_MS = 250L
        const val TAG = "WarpnectInputProduction"
    }
}
