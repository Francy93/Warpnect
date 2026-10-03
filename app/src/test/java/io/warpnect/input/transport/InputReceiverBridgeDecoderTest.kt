package io.warpnect.input.transport

import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputReceiverBridgeDecoderTest {
    @Test fun focusLossAllDevicesResetUsesReservedSlotWithoutFailingReceiver() {
        val buffer = resetBuffer(InputDeviceKind.Unknown, 65_535, InputResetScope.AllDevices)
        assertEquals(
            InputResetState(InputDeviceKind.Unknown, 65_535, InputResetScope.AllDevices, InputResetReason.FocusLost),
            InputReceiverBridgeDecoder.decode(buffer)?.event,
        )
    }

    @Test fun reservedSlotStillRejectedForDeviceScopedResetAndKey() {
        assertNull(InputReceiverBridgeDecoder.decode(resetBuffer(InputDeviceKind.Keyboard, 65_535)))
        val key = resetBuffer(InputDeviceKind.Keyboard, 65_535)
        key.putInt(0, InputMessageTypeMetadata.Key.ordinal + 1)
        key.putInt(32, 7).putInt(36, 40).putInt(40, 0)
        assertNull(InputReceiverBridgeDecoder.decode(key))
    }

    @Test fun globalResetCannotUseOrdinaryDeviceSlot() {
        assertNull(
            InputReceiverBridgeDecoder.decode(resetBuffer(InputDeviceKind.Unknown, 0, InputResetScope.AllDevices)),
        )
    }

    @Test fun ordinaryDeviceResetRemainsValidAndOutOfRangeSlotIsRejected() {
        assertEquals(
            InputResetState(InputDeviceKind.Keyboard, 0, InputResetScope.ThisDevice, InputResetReason.FocusLost),
            InputReceiverBridgeDecoder.decode(resetBuffer(InputDeviceKind.Keyboard, 0))?.event,
        )
        assertNull(InputReceiverBridgeDecoder.decode(resetBuffer(InputDeviceKind.Keyboard, 65_536)))
    }

    private fun resetBuffer(
        kind: InputDeviceKind,
        slot: Int,
        scope: InputResetScope = InputResetScope.ThisDevice,
    ): ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_RECEIVER_BRIDGE_REQUIRED_BYTES).order(ByteOrder.nativeOrder()).apply {
            putInt(0, InputMessageTypeMetadata.ResetState.ordinal + 1)
            putInt(4, kind.ordinal)
            putInt(8, slot)
            putLong(16, 2)
            putLong(24, 1)
            putInt(32, scope.ordinal)
            putInt(36, InputResetReason.FocusLost.ordinal)
        }
}
