package io.warpnect.platform.input.mapping

import android.view.KeyEvent
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_ALT
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_CONTROL
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_GUI
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_SHIFT
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_ALT
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_CONTROL
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_GUI
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_SHIFT

data class AndroidHidUsage(
    val usagePage: Int,
    val usageId: Int,
)

/**
 * Single canonical Android keycode <-> HID keyboard usage table shared by capture and target
 * mapping. Both indexes are derived from the same entries so their mappings cannot drift.
 */
object AndroidHidKeyboardMappingTable {
    const val KEYBOARD_USAGE_PAGE: Int = 0x0007

    private val mappings: List<KeyMapping> by lazy {
        buildList {
            for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                add(KeyMapping(keyCode, 0x04 + keyCode - KeyEvent.KEYCODE_A))
            }
            for (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
                add(KeyMapping(keyCode, 0x1E + keyCode - KeyEvent.KEYCODE_1))
            }
            add(KeyMapping(KeyEvent.KEYCODE_0, 0x27))
            add(KeyMapping(KeyEvent.KEYCODE_ENTER, 0x28))
            add(KeyMapping(KeyEvent.KEYCODE_ESCAPE, 0x29))
            add(KeyMapping(KeyEvent.KEYCODE_DEL, 0x2A))
            add(KeyMapping(KeyEvent.KEYCODE_TAB, 0x2B))
            add(KeyMapping(KeyEvent.KEYCODE_SPACE, 0x2C))
            add(KeyMapping(KeyEvent.KEYCODE_MINUS, 0x2D))
            add(KeyMapping(KeyEvent.KEYCODE_EQUALS, 0x2E))
            add(KeyMapping(KeyEvent.KEYCODE_LEFT_BRACKET, 0x2F))
            add(KeyMapping(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x30))
            add(KeyMapping(KeyEvent.KEYCODE_BACKSLASH, 0x31))
            add(KeyMapping(KeyEvent.KEYCODE_SEMICOLON, 0x33))
            add(KeyMapping(KeyEvent.KEYCODE_APOSTROPHE, 0x34))
            add(KeyMapping(KeyEvent.KEYCODE_GRAVE, 0x35))
            add(KeyMapping(KeyEvent.KEYCODE_COMMA, 0x36))
            add(KeyMapping(KeyEvent.KEYCODE_PERIOD, 0x37))
            add(KeyMapping(KeyEvent.KEYCODE_SLASH, 0x38))
            add(KeyMapping(KeyEvent.KEYCODE_CAPS_LOCK, 0x39))
            for (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
                add(KeyMapping(keyCode, 0x3A + keyCode - KeyEvent.KEYCODE_F1))
            }
            add(KeyMapping(KeyEvent.KEYCODE_SYSRQ, 0x46))
            add(KeyMapping(KeyEvent.KEYCODE_SCROLL_LOCK, 0x47))
            add(KeyMapping(KeyEvent.KEYCODE_BREAK, 0x48))
            add(KeyMapping(KeyEvent.KEYCODE_INSERT, 0x49))
            add(KeyMapping(KeyEvent.KEYCODE_MOVE_HOME, 0x4A))
            add(KeyMapping(KeyEvent.KEYCODE_PAGE_UP, 0x4B))
            add(KeyMapping(KeyEvent.KEYCODE_FORWARD_DEL, 0x4C))
            add(KeyMapping(KeyEvent.KEYCODE_MOVE_END, 0x4D))
            add(KeyMapping(KeyEvent.KEYCODE_PAGE_DOWN, 0x4E))
            add(KeyMapping(KeyEvent.KEYCODE_DPAD_RIGHT, 0x4F))
            add(KeyMapping(KeyEvent.KEYCODE_DPAD_LEFT, 0x50))
            add(KeyMapping(KeyEvent.KEYCODE_DPAD_DOWN, 0x51))
            add(KeyMapping(KeyEvent.KEYCODE_DPAD_UP, 0x52))
            add(KeyMapping(KeyEvent.KEYCODE_NUM_LOCK, 0x53))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x54))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x55))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x56))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_ADD, 0x57))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x58))
            for (keyCode in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9) {
                add(KeyMapping(keyCode, 0x59 + keyCode - KeyEvent.KEYCODE_NUMPAD_1))
            }
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_0, 0x62))
            add(KeyMapping(KeyEvent.KEYCODE_NUMPAD_DOT, 0x63))
            add(KeyMapping(KeyEvent.KEYCODE_CTRL_LEFT, 0xE0, INPUT_MODIFIER_LEFT_CONTROL))
            add(KeyMapping(KeyEvent.KEYCODE_SHIFT_LEFT, 0xE1, INPUT_MODIFIER_LEFT_SHIFT))
            add(KeyMapping(KeyEvent.KEYCODE_ALT_LEFT, 0xE2, INPUT_MODIFIER_LEFT_ALT))
            add(KeyMapping(KeyEvent.KEYCODE_META_LEFT, 0xE3, INPUT_MODIFIER_LEFT_GUI))
            add(KeyMapping(KeyEvent.KEYCODE_CTRL_RIGHT, 0xE4, INPUT_MODIFIER_RIGHT_CONTROL))
            add(KeyMapping(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xE5, INPUT_MODIFIER_RIGHT_SHIFT))
            add(KeyMapping(KeyEvent.KEYCODE_ALT_RIGHT, 0xE6, INPUT_MODIFIER_RIGHT_ALT))
            add(KeyMapping(KeyEvent.KEYCODE_META_RIGHT, 0xE7, INPUT_MODIFIER_RIGHT_GUI))
        }
    }

    private val byAndroidKeyCode: Map<Int, KeyMapping> by lazy { mappings.associateBy(KeyMapping::androidKeyCode) }
    private val byHidUsage: Map<Int, KeyMapping> by lazy { mappings.associateBy(KeyMapping::usageId) }

    fun androidToHid(keyCode: Int): AndroidHidUsage? = byAndroidKeyCode[keyCode]?.let {
        AndroidHidUsage(KEYBOARD_USAGE_PAGE, it.usageId)
    }

    fun hidToAndroid(usagePage: Int, usageId: Int): Int? =
        if (usagePage == KEYBOARD_USAGE_PAGE) byHidUsage[usageId]?.androidKeyCode else null

    fun modifierMaskForAndroidKeyCode(keyCode: Int): Int = byAndroidKeyCode[keyCode]?.modifierMask ?: 0

    /** Cold-path capability/query helper; ordinary capture and injection use direct lookups. */
    fun supportedAndroidKeyCodes(): List<Int> = mappings.map(KeyMapping::androidKeyCode)

    private data class KeyMapping(
        val androidKeyCode: Int,
        val usageId: Int,
        val modifierMask: Int = 0,
    )
}
