package io.warpnect.input.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputModelTest {
    @Test
    fun normalizationHelpersUseBoundedIntegerRepresentations() {
        assertEquals(0, InputNormalization.normalizedU16FromUnit(0.0))
        assertEquals(65_535, InputNormalization.normalizedU16FromUnit(1.0))
        assertEquals(32_768, InputNormalization.normalizedU16FromUnit(0.5))
        assertNull(InputNormalization.normalizedU16FromUnit(Double.NaN))
        assertNull(InputNormalization.normalizedU16FromUnit(1.1))

        assertEquals(65_536, InputNormalization.q1616FromNormalizedDelta(1.0))
        assertEquals(-65_536, InputNormalization.q1616FromNormalizedDelta(-1.0))
        assertEquals(0.5, InputNormalization.normalizedDeltaFromQ1616(32_768), 0.0)

        assertEquals(256, InputNormalization.q88FromScrollUnits(1.0))
        assertEquals(128, InputNormalization.q88FromScrollUnits(0.5))
        assertEquals(-256, InputNormalization.q88FromScrollUnits(-1.0))

        assertEquals(-32_767, InputNormalization.gamepadAxisFromUnit(-1.0))
        assertEquals(0, InputNormalization.gamepadAxisFromUnit(0.0))
        assertEquals(32_767, InputNormalization.gamepadAxisFromUnit(1.0))
        assertNull(InputNormalization.unitFromGamepadAxis(-32_768))

        assertEquals(0, InputNormalization.triggerFromUnit(0.0))
        assertEquals(65_535, InputNormalization.triggerFromUnit(1.0))
    }

    @Test
    fun keyEventUsesHidUsageAndPortableModifiers() {
        val key = InputKeyEvent(
            deviceSlot = 2,
            usagePage = 0x0007,
            usageId = 0x0004,
            action = InputKeyAction.Down,
            repeatCount = 0,
            modifierMask = INPUT_MODIFIER_LEFT_CONTROL or INPUT_MODIFIER_RIGHT_SHIFT,
        )

        assertEquals(InputModelError.None, key.validate())
        assertEquals(InputDeliveryClass.CriticalTransition, key.deliveryClass())

        assertEquals(
            InputModelError.InvalidKeyAction,
            key.copy(action = InputKeyAction.Up, repeatCount = 1).validate(),
        )
        assertEquals(
            InputModelError.InvalidModifierMask,
            key.copy(modifierMask = 0x0100).validate(),
        )
        assertEquals(
            InputModelError.InvalidDeviceSlot,
            key.copy(deviceSlot = INPUT_RESERVED_DEVICE_SLOT).validate(),
        )
    }

    @Test
    fun touchFrameRequiresBoundedUniqueContactsAndActionPointerRules() {
        val first = InputTouchContact(
            pointerId = 7,
            toolType = InputTouchToolType.Finger,
            pointerFlags = INPUT_TOUCH_PRESSURE_VALID or INPUT_TOUCH_SIZE_VALID,
            xNormalized = 0,
            yNormalized = 65_535,
            pressure = 30_000,
            size = 2_000,
        )
        val second = InputTouchContact(
            pointerId = 3,
            toolType = InputTouchToolType.Stylus,
            pointerFlags = INPUT_TOUCH_PRESSURE_VALID,
            xNormalized = 32_768,
            yNormalized = 32_767,
            pressure = 12_345,
        )
        val frame = InputTouchFrame(
            deviceKind = InputDeviceKind.Touchscreen,
            deviceSlot = 3,
            action = InputTouchAction.PointerDown,
            actionPointerId = 7,
            contacts = listOf(first, second),
        )

        assertEquals(InputModelError.None, frame.validate())
        assertEquals(InputDeliveryClass.CriticalTransition, frame.deliveryClass())
        assertEquals(
            InputModelError.DuplicatePointerId,
            frame.copy(contacts = listOf(first, second.copy(pointerId = 7))).validate(),
        )
        assertEquals(
            InputModelError.InvalidActionPointer,
            frame.copy(actionPointerId = 8).validate(),
        )
        assertEquals(
            InputModelError.InvalidActionPointer,
            frame.copy(
                action = InputTouchAction.Move,
                actionPointerId = 7,
            ).validate(),
        )
        assertEquals(
            InputModelError.InvalidPointerFlags,
            first.copy(pointerFlags = 0, pressure = 1).validate(),
        )
        assertEquals(
            InputModelError.InvalidPointerCount,
            frame.copy(contacts = List(INPUT_MAX_TOUCH_CONTACTS + 1) { first.copy(pointerId = 0) })
                .validate(),
        )
    }

    @Test
    fun pointerAndScrollUseNormalizedCoordinatesAndButtonSnapshots() {
        val absolute = InputPointerAbsolute(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = 0,
            xNormalized = 0,
            yNormalized = 65_535,
            buttonMask = INPUT_POINTER_BUTTON_PRIMARY or INPUT_POINTER_BUTTON_FORWARD,
            pointerFlags = INPUT_POINTER_ABSOLUTE_PRESSURE_VALID,
            pressure = 10_000,
        )
        assertEquals(InputModelError.None, absolute.validate())
        assertEquals(InputDeliveryClass.FreshState, absolute.deliveryClass())
        assertEquals(
            InputModelError.InvalidPointerButtonMask,
            absolute.copy(buttonMask = 0x0020).validate(),
        )

        val relative = InputPointerRelative(
            deviceKind = InputDeviceKind.Touchpad,
            deviceSlot = 1,
            deltaXQ16_16 = 65_536,
            deltaYQ16_16 = -65_536,
            buttonMask = 0,
        )
        assertEquals(InputModelError.None, relative.validate())

        val scroll = InputScroll(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = 0,
            horizontalQ8_8 = 128,
            verticalQ8_8 = -256,
            buttonMask = INPUT_POINTER_BUTTON_PRIMARY,
        )
        assertEquals(InputModelError.None, scroll.validate())
        assertEquals(
            InputModelError.InvalidScroll,
            scroll.copy(horizontalQ8_8 = 0, verticalQ8_8 = 0).validate(),
        )
    }

    @Test
    fun gamepadIsCompleteBoundedStateSnapshot() {
        val gamepad = InputGamepadState(
            deviceSlot = 4,
            buttonMask = INPUT_GAMEPAD_BUTTON_A or
                INPUT_GAMEPAD_BUTTON_Y or
                INPUT_GAMEPAD_BUTTON_DPAD_RIGHT,
            leftX = -32_767,
            leftY = 0,
            rightX = 32_767,
            rightY = 1_234,
            leftTrigger = 1_000,
            rightTrigger = 65_535,
        )

        assertEquals(InputModelError.None, gamepad.validate())
        assertEquals(InputDeliveryClass.FreshState, gamepad.deliveryClass())
        assertEquals(
            InputModelError.InvalidGamepadAxis,
            gamepad.copy(leftX = -32_768).validate(),
        )
        assertEquals(
            InputModelError.InvalidGamepadButtonMask,
            gamepad.copy(buttonMask = 0x0002_0000).validate(),
        )
    }

    @Test
    fun resetStateSupportsPerDeviceAndAllDevices() {
        val thisDevice = InputResetState(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = 4,
            scope = InputResetScope.ThisDevice,
            reason = InputResetReason.DeviceDisconnected,
        )
        val allDevices = InputResetState(
            deviceKind = InputDeviceKind.Unknown,
            deviceSlot = INPUT_RESERVED_DEVICE_SLOT,
            scope = InputResetScope.AllDevices,
            reason = InputResetReason.SessionStop,
        )

        assertEquals(InputModelError.None, thisDevice.validate())
        assertEquals(InputModelError.None, allDevices.validate())
        assertEquals(InputDeliveryClass.Reset, allDevices.deliveryClass())
        assertEquals(
            InputModelError.InvalidDeviceSlot,
            allDevices.copy(deviceSlot = 0).validate(),
        )
        assertEquals(
            InputModelError.InvalidDeviceSlot,
            thisDevice.copy(deviceSlot = INPUT_RESERVED_DEVICE_SLOT).validate(),
        )
    }
}
