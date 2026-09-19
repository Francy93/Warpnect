package io.warpnect.debug.input

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/** Debug-only owned target for a real Session's reverse-input delivery. */
class InputSessionTargetActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Warpnect reverse input target\nEvents: 0" }
        setContentView(status)
        Log.i(TAG, "INPUT_SESSION_TARGET_READY")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "INPUT_SESSION_TARGET_READY")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kind = when (event.action) {
            KeyEvent.ACTION_DOWN -> "KEY_DOWN"
            KeyEvent.ACTION_UP -> "KEY_UP"
            else -> "KEY_OTHER"
        }
        observe(
            kind,
            "key_code=${event.keyCode} scan_code=${event.scanCode} " +
                "target_display_id=${targetDisplayId()} source=${event.source} event_time=${event.eventTime}",
        )
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "TOUCH_DOWN"
            MotionEvent.ACTION_MOVE -> "TOUCH_MOVE"
            MotionEvent.ACTION_UP -> "TOUCH_UP"
            MotionEvent.ACTION_CANCEL -> "TOUCH_CANCEL"
            else -> "TOUCH_OTHER"
        }
        observe(
            kind,
            "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                "event_time=${event.eventTime}",
        )
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_MOUSE) -> {
            observe(
                "POINTER",
                "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                    "event_time=${event.eventTime}",
            )
            true
        }
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> {
            observe(
                "JOYSTICK",
                "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                    "event_time=${event.eventTime}",
            )
            true
        }
        else -> super.onGenericMotionEvent(event)
    }

    private fun observe(kind: String, detail: String) {
        val sequence = eventSequence.incrementAndGet()
        status.text = "Warpnect reverse input target\nEvents: $sequence\n$kind"
        Log.i(
            TAG,
            "INPUT_SESSION_TARGET_EVENT sequence=$sequence kind=$kind $detail " +
                "received_time=${System.currentTimeMillis()}",
        )
    }

    private fun targetDisplayId(): Int = window.decorView.display?.displayId ?: -1

    private companion object {
        const val TAG = "WarpnectInputSessionTarget"
        val eventSequence = AtomicLong()
    }
}
