package io.warpnect.platform.input.injection

import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult

/** Creates framework events from already validated Android-ready values and releases MotionEvents. */
internal class AndroidInjectedEventFactory(
    private val inputManager: PrivilegedInputManagerApi,
) : AndroidInputEventDispatcher {
    override fun submitKey(
        event: AndroidKeyInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult = inputManager.inject(
        KeyEvent(
            downTimeMs,
            eventTimeMs,
            event.action,
            event.keyCode,
            event.repeatCount,
            event.metaState,
            event.androidDeviceId,
            event.scanCode,
            event.flags,
            event.source,
        ),
        event.displayId,
        mode,
        targetUid,
    )

    override fun submitTouch(
        event: AndroidTouchInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult {
        val pointerProperties = Array(event.pointers.size) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(event.pointers.size) { MotionEvent.PointerCoords() }
        event.pointers.forEachIndexed { index, pointer ->
            pointerProperties[index].id = pointer.pointerId
            pointerProperties[index].toolType = pointer.toolType
            pointerCoords[index].x = pointer.xPx
            pointerCoords[index].y = pointer.yPx
            pointerCoords[index].pressure = pointer.pressure
            pointerCoords[index].size = pointer.size
        }
        val action = if (event.actionMasked == AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN ||
            event.actionMasked == AndroidInjectionConstants.MOTION_ACTION_POINTER_UP
        ) {
            event.actionMasked or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        } else {
            event.actionMasked
        }
        val motion = MotionEvent.obtain(
            downTimeMs,
            eventTimeMs,
            action,
            event.pointers.size,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            1f,
            1f,
            event.androidDeviceId,
            0,
            event.source,
            0,
        )
        return try {
            inputManager.inject(motion, event.displayId, mode, targetUid)
        } finally {
            motion.recycle()
        }
    }

    override fun submitPointer(
        event: AndroidPointerInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = event.xPx
                y = event.yPx
                pressure = event.pressure
                size = event.size
                setAxisValue(MotionEvent.AXIS_RELATIVE_X, event.relativeXPx)
                setAxisValue(MotionEvent.AXIS_RELATIVE_Y, event.relativeYPx)
                setAxisValue(MotionEvent.AXIS_HSCROLL, event.horizontalScroll)
                setAxisValue(MotionEvent.AXIS_VSCROLL, event.verticalScroll)
            },
        )
        val motion = MotionEvent.obtain(
            downTimeMs,
            eventTimeMs,
            event.action,
            1,
            properties,
            coords,
            event.metaState,
            event.buttonState,
            1f,
            1f,
            event.androidDeviceId,
            0,
            event.source,
            0,
        )
        return try {
            inputManager.inject(motion, event.displayId, mode, targetUid, event.actionButton)
        } finally {
            motion.recycle()
        }
    }

    override fun submitJoystick(
        event: AndroidJoystickInjectionEvent,
        eventTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, event.leftX)
                setAxisValue(MotionEvent.AXIS_Y, event.leftY)
                setAxisValue(MotionEvent.AXIS_Z, event.rightX)
                setAxisValue(MotionEvent.AXIS_RZ, event.rightY)
                setAxisValue(MotionEvent.AXIS_LTRIGGER, event.leftTrigger)
                setAxisValue(MotionEvent.AXIS_RTRIGGER, event.rightTrigger)
                setAxisValue(MotionEvent.AXIS_HAT_X, event.hatX)
                setAxisValue(MotionEvent.AXIS_HAT_Y, event.hatY)
            },
        )
        val motion = MotionEvent.obtain(
            eventTimeMs,
            eventTimeMs,
            AndroidInjectionConstants.MOTION_ACTION_MOVE,
            1,
            properties,
            coords,
            event.metaState,
            0,
            1f,
            1f,
            event.androidDeviceId,
            0,
            event.source,
            0,
        )
        return try {
            inputManager.inject(motion, event.displayId, mode, targetUid)
        } finally {
            motion.recycle()
        }
    }
}
