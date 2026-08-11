package io.warpnect.platform.input.capture

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

class WarpnectInputCaptureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var controller: AndroidInputCaptureController? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun attachController(controller: AndroidInputCaptureController) {
        if (this.controller === controller) {
            return
        }
        this.controller = controller
    }

    fun detachController(controller: AndroidInputCaptureController) {
        if (this.controller === controller) {
            this.controller = null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        controller?.handleKeyEvent(event) ?: super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        controller?.handleKeyEvent(event) ?: super.onKeyUp(keyCode, event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocus()
        }
        return controller?.handleTouchEvent(this, event) ?: super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        controller?.handleGenericMotionEvent(this, event) ?: super.onGenericMotionEvent(event)

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean =
        controller?.handleCapturedPointerEvent(this, event) ?: super.onCapturedPointerEvent(event)

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        controller?.onPointerCaptureChanged(hasCapture)
        super.onPointerCaptureChange(hasCapture)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        controller?.onWindowFocusChanged(hasWindowFocus)
        super.onWindowFocusChanged(hasWindowFocus)
    }
}
