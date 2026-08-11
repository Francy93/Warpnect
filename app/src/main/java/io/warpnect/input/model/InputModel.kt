package io.warpnect.input.model

import kotlin.math.roundToInt
import kotlin.math.roundToLong

const val INPUT_PAYLOAD_VERSION: Int = 1
const val INPUT_PRIMARY_DEVICE_SLOT: Int = 0
const val INPUT_RESERVED_DEVICE_SLOT: Int = 65_535
const val INPUT_NO_ACTION_POINTER_ID: Int = 255
const val INPUT_MAX_TOUCH_CONTACTS: Int = 32

const val INPUT_MODIFIER_LEFT_CONTROL: Int = 1 shl 0
const val INPUT_MODIFIER_LEFT_SHIFT: Int = 1 shl 1
const val INPUT_MODIFIER_LEFT_ALT: Int = 1 shl 2
const val INPUT_MODIFIER_LEFT_GUI: Int = 1 shl 3
const val INPUT_MODIFIER_RIGHT_CONTROL: Int = 1 shl 4
const val INPUT_MODIFIER_RIGHT_SHIFT: Int = 1 shl 5
const val INPUT_MODIFIER_RIGHT_ALT: Int = 1 shl 6
const val INPUT_MODIFIER_RIGHT_GUI: Int = 1 shl 7
const val INPUT_MODIFIER_MASK_ALLOWED: Int = 0x00FF

const val INPUT_POINTER_BUTTON_PRIMARY: Int = 1 shl 0
const val INPUT_POINTER_BUTTON_SECONDARY: Int = 1 shl 1
const val INPUT_POINTER_BUTTON_TERTIARY: Int = 1 shl 2
const val INPUT_POINTER_BUTTON_BACK: Int = 1 shl 3
const val INPUT_POINTER_BUTTON_FORWARD: Int = 1 shl 4
const val INPUT_POINTER_BUTTON_MASK_ALLOWED: Int = 0x001F

const val INPUT_TOUCH_PRESSURE_VALID: Int = 1 shl 0
const val INPUT_TOUCH_SIZE_VALID: Int = 1 shl 1
const val INPUT_TOUCH_POINTER_FLAGS_ALLOWED: Int = 0x0003
const val INPUT_POINTER_ABSOLUTE_PRESSURE_VALID: Int = 1 shl 0
const val INPUT_POINTER_ABSOLUTE_FLAGS_ALLOWED: Int = 0x0001

const val INPUT_GAMEPAD_BUTTON_A: Int = 1 shl 0
const val INPUT_GAMEPAD_BUTTON_B: Int = 1 shl 1
const val INPUT_GAMEPAD_BUTTON_X: Int = 1 shl 2
const val INPUT_GAMEPAD_BUTTON_Y: Int = 1 shl 3
const val INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER: Int = 1 shl 4
const val INPUT_GAMEPAD_BUTTON_RIGHT_SHOULDER: Int = 1 shl 5
const val INPUT_GAMEPAD_BUTTON_LEFT_TRIGGER: Int = 1 shl 6
const val INPUT_GAMEPAD_BUTTON_RIGHT_TRIGGER: Int = 1 shl 7
const val INPUT_GAMEPAD_BUTTON_SELECT_BACK: Int = 1 shl 8
const val INPUT_GAMEPAD_BUTTON_START: Int = 1 shl 9
const val INPUT_GAMEPAD_BUTTON_GUIDE_MODE: Int = 1 shl 10
const val INPUT_GAMEPAD_BUTTON_LEFT_STICK: Int = 1 shl 11
const val INPUT_GAMEPAD_BUTTON_RIGHT_STICK: Int = 1 shl 12
const val INPUT_GAMEPAD_BUTTON_DPAD_UP: Int = 1 shl 13
const val INPUT_GAMEPAD_BUTTON_DPAD_DOWN: Int = 1 shl 14
const val INPUT_GAMEPAD_BUTTON_DPAD_LEFT: Int = 1 shl 15
const val INPUT_GAMEPAD_BUTTON_DPAD_RIGHT: Int = 1 shl 16
const val INPUT_GAMEPAD_BUTTON_MASK_ALLOWED: Int = 0x0001FFFF

enum class InputModelError {
    None,
    InvalidDeviceKind,
    InvalidDeviceSlot,
    InvalidKeyAction,
    InvalidModifierMask,
    InvalidTouchAction,
    InvalidPointerCount,
    DuplicatePointerId,
    InvalidActionPointer,
    InvalidPointerFlags,
    InvalidPointerButtonMask,
    InvalidGamepadButtonMask,
    InvalidGamepadAxis,
    InvalidResetScope,
    InvalidResetReason,
    InvalidNormalizedValue,
    InvalidScroll,
}

enum class InputDeviceKind {
    Unknown,
    Keyboard,
    Touchscreen,
    Mouse,
    Gamepad,
    Stylus,
    Touchpad,
}

enum class InputMessageType {
    Unknown,
    Key,
    TouchFrame,
    PointerAbsolute,
    PointerRelative,
    Scroll,
    GamepadState,
    ResetState,
}

enum class InputKeyAction {
    Unknown,
    Down,
    Up,
}

enum class InputTouchAction {
    Unknown,
    Down,
    Up,
    Move,
    Cancel,
    PointerDown,
    PointerUp,
}

enum class InputTouchToolType {
    Unknown,
    Finger,
    Stylus,
    Eraser,
    Mouse,
}

enum class InputResetScope {
    Unknown,
    ThisDevice,
    AllDevices,
}

enum class InputResetReason {
    Unknown,
    SessionStop,
    DeviceDisconnected,
    FocusLost,
    ErrorRecovery,
    UserRequest,
}

enum class InputDeliveryClass {
    FreshState,
    CriticalTransition,
    Reset,
}

sealed interface WarpnectInputEvent

data class InputKeyEvent(
    val deviceSlot: Int,
    val usagePage: Int,
    val usageId: Int,
    val action: InputKeyAction,
    val repeatCount: Int,
    val modifierMask: Int,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if (usagePage !in 0..0xFFFF || usageId !in 0..0xFFFF) {
            return InputModelError.InvalidNormalizedValue
        }
        if (action != InputKeyAction.Down && action != InputKeyAction.Up) {
            return InputModelError.InvalidKeyAction
        }
        if (repeatCount !in 0..0xFFFF || (action == InputKeyAction.Up && repeatCount != 0)) {
            return InputModelError.InvalidKeyAction
        }
        if ((modifierMask and INPUT_MODIFIER_MASK_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidModifierMask
        }
        return InputModelError.None
    }
}

data class InputTouchContact(
    val pointerId: Int,
    val toolType: InputTouchToolType,
    val pointerFlags: Int,
    val xNormalized: Int,
    val yNormalized: Int,
    val pressure: Int = 0,
    val size: Int = 0,
) {
    fun validate(): InputModelError {
        if (pointerId !in 0 until INPUT_MAX_TOUCH_CONTACTS) {
            return InputModelError.InvalidActionPointer
        }
        if (!InputNormalization.isNormalizedU16(xNormalized) ||
            !InputNormalization.isNormalizedU16(yNormalized) ||
            !InputNormalization.isNormalizedU16(pressure) ||
            !InputNormalization.isNormalizedU16(size)
        ) {
            return InputModelError.InvalidNormalizedValue
        }
        if ((pointerFlags and INPUT_TOUCH_POINTER_FLAGS_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidPointerFlags
        }
        if ((pointerFlags and INPUT_TOUCH_PRESSURE_VALID) == 0 && pressure != 0) {
            return InputModelError.InvalidPointerFlags
        }
        if ((pointerFlags and INPUT_TOUCH_SIZE_VALID) == 0 && size != 0) {
            return InputModelError.InvalidPointerFlags
        }
        return InputModelError.None
    }
}

data class InputTouchFrame(
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val action: InputTouchAction,
    val actionPointerId: Int,
    val contacts: List<InputTouchContact>,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (deviceKind !in TOUCH_DEVICE_KINDS) return InputModelError.InvalidDeviceKind
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if (action == InputTouchAction.Unknown) return InputModelError.InvalidTouchAction
        if (contacts.size > INPUT_MAX_TOUCH_CONTACTS ||
            (contacts.isEmpty() && action != InputTouchAction.Cancel)
        ) {
            return InputModelError.InvalidPointerCount
        }
        val seen = BooleanArray(INPUT_MAX_TOUCH_CONTACTS)
        for (contact in contacts) {
            val contactError = contact.validate()
            if (contactError != InputModelError.None) return contactError
            if (seen[contact.pointerId]) return InputModelError.DuplicatePointerId
            seen[contact.pointerId] = true
        }
        return if (action.usesTransitionPointer()) {
            if (actionPointerId == INPUT_NO_ACTION_POINTER_ID ||
                contacts.none { it.pointerId == actionPointerId }
            ) {
                InputModelError.InvalidActionPointer
            } else {
                InputModelError.None
            }
        } else if (actionPointerId != INPUT_NO_ACTION_POINTER_ID) {
            InputModelError.InvalidActionPointer
        } else {
            InputModelError.None
        }
    }
}

data class InputPointerAbsolute(
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val xNormalized: Int,
    val yNormalized: Int,
    val buttonMask: Int,
    val pointerFlags: Int,
    val pressure: Int = 0,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (deviceKind !in ABSOLUTE_POINTER_DEVICE_KINDS) return InputModelError.InvalidDeviceKind
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if (!InputNormalization.isNormalizedU16(xNormalized) ||
            !InputNormalization.isNormalizedU16(yNormalized) ||
            !InputNormalization.isNormalizedU16(pressure)
        ) {
            return InputModelError.InvalidNormalizedValue
        }
        if ((buttonMask and INPUT_POINTER_BUTTON_MASK_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidPointerButtonMask
        }
        if ((pointerFlags and INPUT_POINTER_ABSOLUTE_FLAGS_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidPointerFlags
        }
        if ((pointerFlags and INPUT_POINTER_ABSOLUTE_PRESSURE_VALID) == 0 && pressure != 0) {
            return InputModelError.InvalidPointerFlags
        }
        return InputModelError.None
    }
}

data class InputPointerRelative(
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val deltaXQ16_16: Int,
    val deltaYQ16_16: Int,
    val buttonMask: Int,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (deviceKind !in RELATIVE_POINTER_DEVICE_KINDS) return InputModelError.InvalidDeviceKind
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if ((buttonMask and INPUT_POINTER_BUTTON_MASK_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidPointerButtonMask
        }
        return InputModelError.None
    }
}

data class InputScroll(
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val horizontalQ8_8: Int,
    val verticalQ8_8: Int,
    val buttonMask: Int,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (deviceKind !in RELATIVE_POINTER_DEVICE_KINDS) return InputModelError.InvalidDeviceKind
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if (horizontalQ8_8 !in Short.MIN_VALUE..Short.MAX_VALUE ||
            verticalQ8_8 !in Short.MIN_VALUE..Short.MAX_VALUE ||
            (horizontalQ8_8 == 0 && verticalQ8_8 == 0)
        ) {
            return InputModelError.InvalidScroll
        }
        if ((buttonMask and INPUT_POINTER_BUTTON_MASK_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidPointerButtonMask
        }
        return InputModelError.None
    }
}

data class InputGamepadState(
    val deviceSlot: Int,
    val buttonMask: Int,
    val leftX: Int,
    val leftY: Int,
    val rightX: Int,
    val rightY: Int,
    val leftTrigger: Int,
    val rightTrigger: Int,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (!InputDeviceSlots.isValidDeviceSlot(deviceSlot)) return InputModelError.InvalidDeviceSlot
        if ((buttonMask and INPUT_GAMEPAD_BUTTON_MASK_ALLOWED.inv()) != 0) {
            return InputModelError.InvalidGamepadButtonMask
        }
        if (!InputNormalization.isGamepadAxis(leftX) ||
            !InputNormalization.isGamepadAxis(leftY) ||
            !InputNormalization.isGamepadAxis(rightX) ||
            !InputNormalization.isGamepadAxis(rightY)
        ) {
            return InputModelError.InvalidGamepadAxis
        }
        if (!InputNormalization.isNormalizedU16(leftTrigger) ||
            !InputNormalization.isNormalizedU16(rightTrigger)
        ) {
            return InputModelError.InvalidNormalizedValue
        }
        return InputModelError.None
    }
}

data class InputResetState(
    val deviceKind: InputDeviceKind,
    val deviceSlot: Int,
    val scope: InputResetScope,
    val reason: InputResetReason,
) : WarpnectInputEvent {
    fun validate(): InputModelError {
        if (scope != InputResetScope.ThisDevice && scope != InputResetScope.AllDevices) {
            return InputModelError.InvalidResetScope
        }
        return if (scope == InputResetScope.AllDevices) {
            if (deviceKind == InputDeviceKind.Unknown && deviceSlot == INPUT_RESERVED_DEVICE_SLOT) {
                InputModelError.None
            } else {
                InputModelError.InvalidDeviceSlot
            }
        } else if (deviceKind != InputDeviceKind.Unknown &&
            InputDeviceSlots.isValidDeviceSlot(deviceSlot)
        ) {
            InputModelError.None
        } else {
            InputModelError.InvalidDeviceSlot
        }
    }
}

object InputDeviceSlots {
    fun isValidDeviceSlot(slot: Int): Boolean = slot in 0 until INPUT_RESERVED_DEVICE_SLOT
}

object InputNormalization {
    fun isNormalizedU16(value: Int): Boolean = value in 0..0xFFFF

    fun normalizedU16FromUnit(value: Double): Int? {
        if (!value.isFinite() || value < 0.0 || value > 1.0) return null
        return (value * 65_535.0).roundToInt()
    }

    fun unitFromNormalizedU16(value: Int): Double? = if (isNormalizedU16(value)) {
        value.toDouble() / 65_535.0
    } else {
        null
    }

    fun q1616FromNormalizedDelta(value: Double): Int? {
        if (!value.isFinite()) return null
        val scaled = (value * 65_536.0).roundToLong()
        return if (scaled in Int.MIN_VALUE..Int.MAX_VALUE) scaled.toInt() else null
    }

    fun normalizedDeltaFromQ1616(value: Int): Double = value.toDouble() / 65_536.0

    fun q88FromScrollUnits(value: Double): Int? {
        if (!value.isFinite()) return null
        val scaled = (value * 256.0).roundToInt()
        return if (scaled in Short.MIN_VALUE..Short.MAX_VALUE) scaled else null
    }

    fun scrollUnitsFromQ88(value: Int): Double? = if (value in Short.MIN_VALUE..Short.MAX_VALUE) {
        value.toDouble() / 256.0
    } else {
        null
    }

    fun isGamepadAxis(value: Int): Boolean = value in -32767..32767

    fun gamepadAxisFromUnit(value: Double): Int? {
        if (!value.isFinite() || value < -1.0 || value > 1.0) return null
        return (value * 32_767.0).roundToInt()
    }

    fun unitFromGamepadAxis(value: Int): Double? = if (isGamepadAxis(value)) {
        value.toDouble() / 32_767.0
    } else {
        null
    }

    fun triggerFromUnit(value: Double): Int? {
        if (!value.isFinite() || value < 0.0 || value > 1.0) return null
        return (value * 65_535.0).roundToInt()
    }

    fun unitFromTrigger(value: Int): Double? = unitFromNormalizedU16(value)
}

fun InputKeyEvent.deliveryClass(): InputDeliveryClass = InputDeliveryClass.CriticalTransition

fun InputTouchFrame.deliveryClass(): InputDeliveryClass =
    if (action.usesTransitionPointer() || action == InputTouchAction.Cancel) {
        InputDeliveryClass.CriticalTransition
    } else {
        InputDeliveryClass.FreshState
    }

fun InputPointerAbsolute.deliveryClass(): InputDeliveryClass = InputDeliveryClass.FreshState

fun InputPointerRelative.deliveryClass(): InputDeliveryClass = InputDeliveryClass.FreshState

fun InputScroll.deliveryClass(): InputDeliveryClass = InputDeliveryClass.FreshState

fun InputGamepadState.deliveryClass(): InputDeliveryClass = InputDeliveryClass.FreshState

fun InputResetState.deliveryClass(): InputDeliveryClass = InputDeliveryClass.Reset

private val TOUCH_DEVICE_KINDS = setOf(
    InputDeviceKind.Touchscreen,
    InputDeviceKind.Touchpad,
    InputDeviceKind.Stylus,
)

private val ABSOLUTE_POINTER_DEVICE_KINDS = setOf(
    InputDeviceKind.Mouse,
    InputDeviceKind.Stylus,
    InputDeviceKind.Touchpad,
)

private val RELATIVE_POINTER_DEVICE_KINDS = setOf(
    InputDeviceKind.Mouse,
    InputDeviceKind.Touchpad,
)

private fun InputTouchAction.usesTransitionPointer(): Boolean = when (this) {
    InputTouchAction.Down,
    InputTouchAction.Up,
    InputTouchAction.PointerDown,
    InputTouchAction.PointerUp,
    -> true
    else -> false
}
