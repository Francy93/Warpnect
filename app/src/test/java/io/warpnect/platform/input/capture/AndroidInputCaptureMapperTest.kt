package io.warpnect.platform.input.capture

import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.capture.InputCaptureError
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_A
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_RIGHT
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_CONTROL
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_SHIFT
import io.warpnect.input.model.INPUT_POINTER_BUTTON_BACK
import io.warpnect.input.model.INPUT_POINTER_BUTTON_PRIMARY
import io.warpnect.input.model.InputDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidInputCaptureMapperTest {
    @Test
    fun keyboardMapperUsesHidUsagePage() {
        assertEquals(AndroidHidUsage(0x0007, 0x04), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_A))
        assertEquals(AndroidHidUsage(0x0007, 0x1D), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_Z))
        assertEquals(AndroidHidUsage(0x0007, 0x1E), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_1))
        assertEquals(AndroidHidUsage(0x0007, 0x27), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_0))
        assertEquals(AndroidHidUsage(0x0007, 0x28), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(AndroidHidUsage(0x0007, 0x29), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(AndroidHidUsage(0x0007, 0x2C), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_SPACE))
        assertEquals(AndroidHidUsage(0x0007, 0x2B), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_TAB))
        assertEquals(AndroidHidUsage(0x0007, 0x52), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(AndroidHidUsage(0x0007, 0x3A), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_F1))
        assertEquals(AndroidHidUsage(0x0007, 0x45), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_F12))
        assertEquals(AndroidHidUsage(0x0007, 0xE0), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(AndroidHidUsage(0x0007, 0xE5), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(AndroidHidUsage(0x0007, 0x59), AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_NUMPAD_1))
        assertNull(AndroidKeyboardHidMapper.mapKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun modifierTrackerKeepsLeftAndRightStateIndependently() {
        val tracker = AndroidKeyboardModifierTracker()

        assertEquals(INPUT_MODIFIER_LEFT_CONTROL, tracker.update(2, KeyEvent.KEYCODE_CTRL_LEFT, down = true))
        assertEquals(
            INPUT_MODIFIER_LEFT_CONTROL or INPUT_MODIFIER_RIGHT_SHIFT,
            tracker.update(2, KeyEvent.KEYCODE_SHIFT_RIGHT, down = true),
        )
        assertEquals(INPUT_MODIFIER_RIGHT_SHIFT, tracker.update(2, KeyEvent.KEYCODE_CTRL_LEFT, down = false))
        tracker.clear()
        assertEquals(0, tracker.update(2, KeyEvent.KEYCODE_A, down = true))
    }

    @Test
    fun deviceRegistryIsBoundedAndDoesNotReuseSlotsDuringLifecycle() {
        val registry = AndroidInputDeviceRegistry(maxTrackedLogicalDevices = 2)

        assertEquals(0, registry.slotFor(10, InputDeviceKind.Keyboard))
        assertEquals(1, registry.slotFor(10, InputDeviceKind.Gamepad))
        assertEquals(0, registry.slotFor(10, InputDeviceKind.Keyboard))
        assertNull(registry.slotFor(11, InputDeviceKind.Mouse))
        assertEquals(1L, registry.registryFullCount)

        val removed = registry.removeAndroidDevice(10)
        assertEquals(2, removed.size)
        assertEquals(2, registry.slotFor(11, InputDeviceKind.Mouse))

        registry.clearForNewLifecycle()
        assertEquals(0, registry.slotFor(11, InputDeviceKind.Mouse))
    }

    @Test
    fun gamepadButtonsAndAxesNormalizeWithoutDeadzone() {
        assertEquals(INPUT_GAMEPAD_BUTTON_A, AndroidGamepadMapper.buttonMaskForKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(
            INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER,
            AndroidGamepadMapper.buttonMaskForKeyCode(KeyEvent.KEYCODE_BUTTON_L1),
        )
        assertEquals(0, AndroidGamepadMapper.buttonMaskForKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN))

        assertEquals(-32_767, AndroidGamepadMapper.normalizeStick(-1f, AndroidAxisRange(-1f, 1f)))
        assertEquals(0, AndroidGamepadMapper.normalizeStick(0f, AndroidAxisRange(-1f, 1f)))
        assertEquals(32_767, AndroidGamepadMapper.normalizeStick(1f, AndroidAxisRange(-1f, 1f)))
        assertEquals(-32_767, AndroidGamepadMapper.normalizeStick(0f, AndroidAxisRange(0f, 255f)))
        assertEquals(32_767, AndroidGamepadMapper.normalizeStick(255f, AndroidAxisRange(0f, 255f)))
        assertEquals(1, AndroidGamepadMapper.normalizeStick(0.00003f, AndroidAxisRange(-1f, 1f)))
        assertEquals(0, AndroidGamepadMapper.normalizeTrigger(0f, AndroidAxisRange(0f, 255f)))
        assertEquals(65_535, AndroidGamepadMapper.normalizeTrigger(255f, AndroidAxisRange(0f, 255f)))
        assertEquals(INPUT_GAMEPAD_BUTTON_DPAD_RIGHT, AndroidGamepadMapper.hatMask(1f, 0f))
    }

    @Test
    fun pointerAndScrollHelpersUseNormalizedIntegerRanges() {
        assertEquals(0, AndroidPointerMapper.normalizedCoordinate(0f, 200))
        assertEquals(32_768, AndroidPointerMapper.normalizedCoordinate(100f, 200))
        assertEquals(65_535, AndroidPointerMapper.normalizedCoordinate(250f, 200))
        assertEquals(32_768, AndroidPointerMapper.relativeQ1616(100f, 200))
        assertEquals(128, AndroidPointerMapper.scrollQ88(0.5f))
        assertEquals(
            INPUT_POINTER_BUTTON_PRIMARY or INPUT_POINTER_BUTTON_BACK,
            AndroidPointerMapper.buttonMask(
                MotionEvent.BUTTON_PRIMARY or MotionEvent.BUTTON_BACK,
            ),
        )
    }

    @Test
    fun captureConfigRejectsUnboundedOrEmptyProfiles() {
        assertEquals(InputCaptureError.None, InputCaptureConfig().validate())
        assertEquals(
            InputCaptureError.InvalidConfiguration,
            InputCaptureConfig(enabledKinds = emptySet()).validate(),
        )
        assertEquals(
            InputCaptureError.InvalidConfiguration,
            InputCaptureConfig(maxTrackedLogicalDevices = 0).validate(),
        )
    }
}
