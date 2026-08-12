package io.warpnect.platform.input.capture

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_A
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_B
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_DOWN
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_LEFT
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_RIGHT
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_UP
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_GUIDE_MODE
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_STICK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_TRIGGER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_SHOULDER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_STICK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_TRIGGER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_SELECT_BACK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_START
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_X
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_Y
import io.warpnect.input.model.INPUT_POINTER_BUTTON_BACK
import io.warpnect.input.model.INPUT_POINTER_BUTTON_FORWARD
import io.warpnect.input.model.INPUT_POINTER_BUTTON_PRIMARY
import io.warpnect.input.model.INPUT_POINTER_BUTTON_SECONDARY
import io.warpnect.input.model.INPUT_POINTER_BUTTON_TERTIARY
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputNormalization
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.platform.input.mapping.AndroidHidKeyboardMappingTable
import io.warpnect.platform.input.mapping.AndroidHidUsage as SharedAndroidHidUsage
import kotlin.math.roundToInt

typealias AndroidHidUsage = SharedAndroidHidUsage

object AndroidInputEventClock {
    fun keyEventTimeUs(event: KeyEvent): Long = event.eventTime * 1_000L

    fun motionEventTimeUs(event: MotionEvent): Long = if (Build.VERSION.SDK_INT >= 34) {
        event.eventTimeNanos / 1_000L
    } else {
        event.eventTime * 1_000L
    }

    fun historicalMotionEventTimeUs(event: MotionEvent, historyIndex: Int): Long = if (Build.VERSION.SDK_INT >= 34) {
        event.getHistoricalEventTimeNanos(historyIndex) / 1_000L
    } else {
        event.getHistoricalEventTime(historyIndex) * 1_000L
    }

    fun callbackUptimeUs(): Long = SystemClock.uptimeMillis() * 1_000L
}

object AndroidKeyboardHidMapper {
    const val KEYBOARD_USAGE_PAGE: Int = AndroidHidKeyboardMappingTable.KEYBOARD_USAGE_PAGE

    fun mapKeyCode(keyCode: Int): AndroidHidUsage? = AndroidHidKeyboardMappingTable.androidToHid(keyCode)

    fun modifierMaskForKeyCode(keyCode: Int): Int =
        AndroidHidKeyboardMappingTable.modifierMaskForAndroidKeyCode(keyCode)
}

class AndroidKeyboardModifierTracker {
    private val masksBySlot = mutableMapOf<Int, Int>()

    fun update(deviceSlot: Int, keyCode: Int, down: Boolean): Int {
        val bit = AndroidKeyboardHidMapper.modifierMaskForKeyCode(keyCode)
        val current = masksBySlot[deviceSlot] ?: 0
        val next = if (bit == 0) {
            current
        } else if (down) {
            current or bit
        } else {
            current and bit.inv()
        }
        masksBySlot[deviceSlot] = next
        return next
    }

    fun clear() {
        masksBySlot.clear()
    }

    fun removeSlot(deviceSlot: Int) {
        masksBySlot.remove(deviceSlot)
    }
}

data class AndroidLogicalDeviceKey(
    val androidDeviceId: Int,
    val kind: InputDeviceKind,
)

data class AndroidLogicalDeviceAssignment(
    val androidDeviceId: Int,
    val kind: InputDeviceKind,
    val slot: Int,
)

class AndroidInputDeviceRegistry(
    private val maxTrackedLogicalDevices: Int,
) {
    private val assignments = LinkedHashMap<AndroidLogicalDeviceKey, Int>()
    private var nextSlot = 0
    var registryFullCount: Long = 0
        private set

    fun slotFor(androidDeviceId: Int, kind: InputDeviceKind): Int? {
        val key = AndroidLogicalDeviceKey(androidDeviceId, kind)
        assignments[key]?.let { return it }
        if (assignments.size >= maxTrackedLogicalDevices || nextSlot >= 65_535) {
            registryFullCount += 1
            return null
        }
        val slot = nextSlot
        nextSlot += 1
        assignments[key] = slot
        return slot
    }

    fun removeAndroidDevice(androidDeviceId: Int): List<AndroidLogicalDeviceAssignment> {
        val removed = assignments
            .filterKeys { it.androidDeviceId == androidDeviceId }
            .map { (key, slot) ->
                AndroidLogicalDeviceAssignment(key.androidDeviceId, key.kind, slot)
            }
        for (assignment in removed) {
            assignments.remove(AndroidLogicalDeviceKey(assignment.androidDeviceId, assignment.kind))
        }
        return removed
    }

    fun activeAssignments(): List<AndroidLogicalDeviceAssignment> = assignments.map { (key, slot) ->
        AndroidLogicalDeviceAssignment(key.androidDeviceId, key.kind, slot)
    }

    fun clearForNewLifecycle() {
        assignments.clear()
        nextSlot = 0
        registryFullCount = 0
    }

    fun trackedLogicalDevices(): Int = assignments.size

    fun highestAssignedSlot(): Int? = if (nextSlot == 0) null else nextSlot - 1
}

data class AndroidAxisRange(
    val min: Float,
    val max: Float,
) {
    val isValid: Boolean
        get() = min.isFinite() && max.isFinite() && max > min
}

object AndroidGamepadMapper {
    fun buttonMaskForKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> INPUT_GAMEPAD_BUTTON_A
        KeyEvent.KEYCODE_BUTTON_B -> INPUT_GAMEPAD_BUTTON_B
        KeyEvent.KEYCODE_BUTTON_X -> INPUT_GAMEPAD_BUTTON_X
        KeyEvent.KEYCODE_BUTTON_Y -> INPUT_GAMEPAD_BUTTON_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_R1 -> INPUT_GAMEPAD_BUTTON_RIGHT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_L2 -> INPUT_GAMEPAD_BUTTON_LEFT_TRIGGER
        KeyEvent.KEYCODE_BUTTON_R2 -> INPUT_GAMEPAD_BUTTON_RIGHT_TRIGGER
        KeyEvent.KEYCODE_BUTTON_SELECT -> INPUT_GAMEPAD_BUTTON_SELECT_BACK
        KeyEvent.KEYCODE_BACK -> INPUT_GAMEPAD_BUTTON_SELECT_BACK
        KeyEvent.KEYCODE_BUTTON_START -> INPUT_GAMEPAD_BUTTON_START
        KeyEvent.KEYCODE_BUTTON_MODE -> INPUT_GAMEPAD_BUTTON_GUIDE_MODE
        KeyEvent.KEYCODE_BUTTON_THUMBL -> INPUT_GAMEPAD_BUTTON_LEFT_STICK
        KeyEvent.KEYCODE_BUTTON_THUMBR -> INPUT_GAMEPAD_BUTTON_RIGHT_STICK
        KeyEvent.KEYCODE_DPAD_UP -> INPUT_GAMEPAD_BUTTON_DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> INPUT_GAMEPAD_BUTTON_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> INPUT_GAMEPAD_BUTTON_DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> INPUT_GAMEPAD_BUTTON_DPAD_RIGHT
        else -> 0
    }

    fun normalizeStick(value: Float, range: AndroidAxisRange): Int? {
        if (!value.isFinite() || !range.isValid) return null
        val unit = (((value - range.min) / (range.max - range.min)) * 2.0f - 1.0f)
            .coerceIn(-1.0f, 1.0f)
        return InputNormalization.gamepadAxisFromUnit(unit.toDouble())
    }

    fun normalizeTrigger(value: Float, range: AndroidAxisRange): Int? {
        if (!value.isFinite() || !range.isValid) return null
        val unit = ((value - range.min) / (range.max - range.min)).coerceIn(0.0f, 1.0f)
        return InputNormalization.triggerFromUnit(unit.toDouble())
    }

    fun hatMask(horizontal: Float, vertical: Float): Int {
        var mask = 0
        if (horizontal < -0.5f) mask = mask or INPUT_GAMEPAD_BUTTON_DPAD_LEFT
        if (horizontal > 0.5f) mask = mask or INPUT_GAMEPAD_BUTTON_DPAD_RIGHT
        if (vertical < -0.5f) mask = mask or INPUT_GAMEPAD_BUTTON_DPAD_UP
        if (vertical > 0.5f) mask = mask or INPUT_GAMEPAD_BUTTON_DPAD_DOWN
        return mask
    }
}

class AndroidGamepadStateCache {
    private data class State(
        var keyButtonMask: Int = 0,
        var hatButtonMask: Int = 0,
        var leftX: Int = 0,
        var leftY: Int = 0,
        var rightX: Int = 0,
        var rightY: Int = 0,
        var leftTrigger: Int = 0,
        var rightTrigger: Int = 0,
    )

    private val states = mutableMapOf<Int, State>()

    fun updateButton(deviceSlot: Int, buttonMask: Int, down: Boolean): InputGamepadState {
        val state = states.getOrPut(deviceSlot) { State() }
        state.keyButtonMask = if (down) {
            state.keyButtonMask or buttonMask
        } else {
            state.keyButtonMask and buttonMask.inv()
        }
        return state.toEvent(deviceSlot)
    }

    fun updateAxes(
        deviceSlot: Int,
        leftX: Int?,
        leftY: Int?,
        rightX: Int?,
        rightY: Int?,
        leftTrigger: Int?,
        rightTrigger: Int?,
        hatButtonMask: Int?,
    ): InputGamepadState {
        val state = states.getOrPut(deviceSlot) { State() }
        if (leftX != null) state.leftX = leftX
        if (leftY != null) state.leftY = leftY
        if (rightX != null) state.rightX = rightX
        if (rightY != null) state.rightY = rightY
        if (leftTrigger != null) state.leftTrigger = leftTrigger
        if (rightTrigger != null) state.rightTrigger = rightTrigger
        if (hatButtonMask != null) state.hatButtonMask = hatButtonMask
        return state.toEvent(deviceSlot)
    }

    fun clear() {
        states.clear()
    }

    fun removeSlot(deviceSlot: Int) {
        states.remove(deviceSlot)
    }

    private fun State.toEvent(deviceSlot: Int): InputGamepadState = InputGamepadState(
        deviceSlot = deviceSlot,
        buttonMask = keyButtonMask or hatButtonMask,
        leftX = leftX,
        leftY = leftY,
        rightX = rightX,
        rightY = rightY,
        leftTrigger = leftTrigger,
        rightTrigger = rightTrigger,
    )
}

object AndroidPointerMapper {
    fun buttonMask(androidButtonState: Int): Int {
        var mask = 0
        if ((androidButtonState and MotionEvent.BUTTON_PRIMARY) != 0) {
            mask = mask or INPUT_POINTER_BUTTON_PRIMARY
        }
        if ((androidButtonState and MotionEvent.BUTTON_SECONDARY) != 0) {
            mask = mask or INPUT_POINTER_BUTTON_SECONDARY
        }
        if ((androidButtonState and MotionEvent.BUTTON_TERTIARY) != 0) {
            mask = mask or INPUT_POINTER_BUTTON_TERTIARY
        }
        if ((androidButtonState and MotionEvent.BUTTON_BACK) != 0) {
            mask = mask or INPUT_POINTER_BUTTON_BACK
        }
        if ((androidButtonState and MotionEvent.BUTTON_FORWARD) != 0) {
            mask = mask or INPUT_POINTER_BUTTON_FORWARD
        }
        return mask
    }

    fun normalizedCoordinate(value: Float, extent: Int): Int? {
        if (!value.isFinite() || extent <= 0) return null
        val normalized = (value.toDouble() / extent.toDouble()).coerceIn(0.0, 1.0)
        return InputNormalization.normalizedU16FromUnit(normalized)
    }

    fun relativeQ1616(delta: Float, extent: Int): Int? {
        if (!delta.isFinite() || extent <= 0) return null
        return InputNormalization.q1616FromNormalizedDelta(delta.toDouble() / extent.toDouble())
    }

    fun scrollQ88(value: Float): Int? = InputNormalization.q88FromScrollUnits(value.toDouble())
}

object AndroidTouchMapper {
    fun toolType(androidToolType: Int): InputTouchToolType = when (androidToolType) {
        MotionEvent.TOOL_TYPE_FINGER -> InputTouchToolType.Finger
        MotionEvent.TOOL_TYPE_STYLUS -> InputTouchToolType.Stylus
        MotionEvent.TOOL_TYPE_ERASER -> InputTouchToolType.Eraser
        MotionEvent.TOOL_TYPE_MOUSE -> InputTouchToolType.Mouse
        else -> InputTouchToolType.Unknown
    }

    fun normalizedAxis(value: Float, range: AndroidAxisRange?): Int? {
        if (!value.isFinite()) return null
        val unit = if (range != null && range.isValid) {
            ((value - range.min) / (range.max - range.min)).coerceIn(0.0f, 1.0f)
        } else {
            value.coerceIn(0.0f, 1.0f)
        }
        return (unit * 65_535.0f).roundToInt().coerceIn(0, 65_535)
    }
}

fun Int.containsInputSource(source: Int): Boolean = (this and source) == source

fun MotionEvent.axisRange(axis: Int): AndroidAxisRange? {
    val device = device ?: return null
    val range = device.getMotionRange(axis, source) ?: device.getMotionRange(axis) ?: return null
    return AndroidAxisRange(range.min, range.max)
}

fun MotionEvent.axisValue(axis: Int, historyIndex: Int?): Float = if (historyIndex == null) {
    getAxisValue(axis)
} else {
    getHistoricalAxisValue(axis, historyIndex)
}

fun MotionEvent.axisValue(axis: Int, pointerIndex: Int, historyIndex: Int?): Float = if (historyIndex == null) {
    getAxisValue(axis, pointerIndex)
} else {
    getHistoricalAxisValue(axis, pointerIndex, historyIndex)
}
