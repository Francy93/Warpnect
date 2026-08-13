package io.warpnect.input.transport

import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputModelError
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.input.model.WarpnectInputEvent
import java.nio.ByteBuffer

/** Decodes the private native-order receiver record. It never parses Input Payload V1 wire bytes. */
object InputReceiverBridgeDecoder {
    private const val MESSAGE_TYPE_OFFSET = 0
    private const val DEVICE_KIND_OFFSET = 4
    private const val DEVICE_SLOT_OFFSET = 8
    private const val SEQUENCE_OFFSET = 16
    private const val SOURCE_TIME_OFFSET = 24
    private const val BODY_OFFSET = 32
    private const val TOUCH_CONTACTS_OFFSET = 64
    private const val TOUCH_CONTACT_STRIDE = 28

    fun decode(buffer: ByteBuffer): InputReceivedEvent? {
        if (!buffer.isDirect || buffer.capacity() < INPUT_RECEIVER_BRIDGE_REQUIRED_BYTES) return null
        val messageType = InputMessageTypeMetadata.entries.getOrNull(buffer.getInt(MESSAGE_TYPE_OFFSET) - 1)
            ?: return null
        val deviceKind = InputDeviceKind.entries.getOrNull(buffer.getInt(DEVICE_KIND_OFFSET)) ?: return null
        val deviceSlot = buffer.getInt(DEVICE_SLOT_OFFSET)
        val sequenceNumber = buffer.getLong(SEQUENCE_OFFSET)
        val sourceEventTimeUs = buffer.getLong(SOURCE_TIME_OFFSET)
        if (deviceSlot !in 0 until 65_535 || sequenceNumber < 0L || sourceEventTimeUs < 0L) return null

        val event = when (messageType) {
            InputMessageTypeMetadata.Key -> InputKeyEvent(
                deviceSlot = deviceSlot,
                usagePage = buffer.getInt(BODY_OFFSET),
                usageId = buffer.getInt(BODY_OFFSET + 4),
                action = InputKeyAction.entries.getOrNull(buffer.getInt(BODY_OFFSET + 8)) ?: return null,
                repeatCount = buffer.getInt(BODY_OFFSET + 12),
                modifierMask = buffer.getInt(BODY_OFFSET + 16),
            )
            InputMessageTypeMetadata.TouchFrame -> decodeTouchFrame(buffer, deviceKind, deviceSlot) ?: return null
            InputMessageTypeMetadata.PointerAbsolute -> InputPointerAbsolute(
                deviceKind = deviceKind,
                deviceSlot = deviceSlot,
                xNormalized = buffer.getInt(BODY_OFFSET),
                yNormalized = buffer.getInt(BODY_OFFSET + 4),
                buttonMask = buffer.getInt(BODY_OFFSET + 8),
                pointerFlags = buffer.getInt(BODY_OFFSET + 12),
                pressure = buffer.getInt(BODY_OFFSET + 16),
            )
            InputMessageTypeMetadata.PointerRelative -> InputPointerRelative(
                deviceKind = deviceKind,
                deviceSlot = deviceSlot,
                deltaXQ16_16 = buffer.getInt(BODY_OFFSET),
                deltaYQ16_16 = buffer.getInt(BODY_OFFSET + 4),
                buttonMask = buffer.getInt(BODY_OFFSET + 8),
            )
            InputMessageTypeMetadata.Scroll -> InputScroll(
                deviceKind = deviceKind,
                deviceSlot = deviceSlot,
                horizontalQ8_8 = buffer.getInt(BODY_OFFSET),
                verticalQ8_8 = buffer.getInt(BODY_OFFSET + 4),
                buttonMask = buffer.getInt(BODY_OFFSET + 8),
            )
            InputMessageTypeMetadata.GamepadState -> InputGamepadState(
                deviceSlot = deviceSlot,
                buttonMask = buffer.getInt(BODY_OFFSET),
                leftX = buffer.getInt(BODY_OFFSET + 4),
                leftY = buffer.getInt(BODY_OFFSET + 8),
                rightX = buffer.getInt(BODY_OFFSET + 12),
                rightY = buffer.getInt(BODY_OFFSET + 16),
                leftTrigger = buffer.getInt(BODY_OFFSET + 20),
                rightTrigger = buffer.getInt(BODY_OFFSET + 24),
            )
            InputMessageTypeMetadata.ResetState -> InputResetState(
                deviceKind = deviceKind,
                deviceSlot = deviceSlot,
                scope = InputResetScope.entries.getOrNull(buffer.getInt(BODY_OFFSET)) ?: return null,
                reason = InputResetReason.entries.getOrNull(buffer.getInt(BODY_OFFSET + 4)) ?: return null,
            )
        }
        if (event.validationError() != InputModelError.None) return null
        return InputReceivedEvent(messageType, deviceKind, deviceSlot, sequenceNumber, sourceEventTimeUs, event)
    }

    private fun decodeTouchFrame(buffer: ByteBuffer, deviceKind: InputDeviceKind, deviceSlot: Int): InputTouchFrame? {
        val action = InputTouchAction.entries.getOrNull(buffer.getInt(BODY_OFFSET)) ?: return null
        val actionPointerId = buffer.getInt(BODY_OFFSET + 4)
        val count = buffer.getInt(BODY_OFFSET + 8)
        if (count !in 0..32) return null
        val contacts = ArrayList<InputTouchContact>(count)
        repeat(count) { index ->
            val offset = TOUCH_CONTACTS_OFFSET + index * TOUCH_CONTACT_STRIDE
            val toolType = InputTouchToolType.entries.getOrNull(buffer.getInt(offset + 4)) ?: return null
            contacts += InputTouchContact(
                pointerId = buffer.getInt(offset),
                toolType = toolType,
                pointerFlags = buffer.getInt(offset + 8),
                xNormalized = buffer.getInt(offset + 12),
                yNormalized = buffer.getInt(offset + 16),
                pressure = buffer.getInt(offset + 20),
                size = buffer.getInt(offset + 24),
            )
        }
        return InputTouchFrame(
            deviceKind = deviceKind,
            deviceSlot = deviceSlot,
            action = action,
            actionPointerId = actionPointerId,
            contacts = contacts,
        )
    }

    private fun WarpnectInputEvent.validationError(): InputModelError = when (this) {
        is InputKeyEvent -> validate()
        is InputTouchFrame -> validate()
        is InputPointerAbsolute -> validate()
        is InputPointerRelative -> validate()
        is InputScroll -> validate()
        is InputGamepadState -> validate()
        is InputResetState -> validate()
    }
}
