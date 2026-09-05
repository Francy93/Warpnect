package io.warpnect.debug.input

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView

/** Debug-only owned target for a real Session's reverse-input delivery. */
class InputSessionTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Warpnect reverse input target" })
        Log.i(TAG, "INPUT_SESSION_TARGET_READY")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.i(TAG, "INPUT_SESSION_TARGET_KEY_OBSERVED")
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            Log.i(TAG, "INPUT_SESSION_TARGET_TOUCH_OBSERVED")
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_MOUSE) -> {
            Log.i(TAG, "INPUT_SESSION_TARGET_POINTER_OBSERVED")
            true
        }
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> {
            Log.i(TAG, "INPUT_SESSION_TARGET_JOYSTICK_OBSERVED")
            true
        }
        else -> super.onGenericMotionEvent(event)
    }

    private companion object {
        const val TAG = "WarpnectInputSessionTarget"
    }
}
