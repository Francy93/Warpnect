package io.warpnect.platform.input.mapping

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHidKeyboardMappingTableTest {
    @Test
    fun everyCaptureSupportedKeyRoundTripsThroughTheCanonicalTable() {
        for (keyCode in AndroidHidKeyboardMappingTable.supportedAndroidKeyCodes()) {
            val usage = requireNotNull(AndroidHidKeyboardMappingTable.androidToHid(keyCode))
            assertEquals(keyCode, AndroidHidKeyboardMappingTable.hidToAndroid(usage.usagePage, usage.usageId))
        }
    }

    @Test
    fun representativeKeyboardSetMapsBidirectionally() {
        val representative = listOf(
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_NUMPAD_0,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        for (keyCode in representative) {
            val usage = requireNotNull(AndroidHidKeyboardMappingTable.androidToHid(keyCode))
            assertEquals(keyCode, AndroidHidKeyboardMappingTable.hidToAndroid(usage.usagePage, usage.usageId))
        }
        assertNull(AndroidHidKeyboardMappingTable.hidToAndroid(0x0007, 0x0068))
    }
}
