package io.warpnect.platform.input.transport

import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.transport.InputTransportConfig
import io.warpnect.input.transport.InputTransportError
import io.warpnect.input.transport.InputTransportState
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSclInputTransportControllerTest {
    @Test
    fun lifecycleIsExplicitAndRestartCreatesAnIndependentNativeSender() {
        val nativeApi = FakeNativeApi()
        val controller = NativeSclInputTransportController.forTesting(nativeApi)
        val key = InputKeyEvent(2, 7, 4, InputKeyAction.Down, 0, 0)

        assertEquals(InputTransportError.NotPrepared, controller.start().error)
        assertEquals(InputTransportError.NotRunning, controller.submit(100, key).error)

        assertTrue(controller.prepare(loopbackConfig()).isSuccess)
        assertEquals(InputTransportState.Prepared, controller.snapshot().state)
        assertEquals("0.0.0.0", controller.snapshot().boundLocalAddress)
        assertEquals(50_001, controller.snapshot().boundLocalPort)
        assertEquals(InputTransportError.NotRunning, controller.submit(101, key).error)

        assertTrue(controller.start().isSuccess)
        assertTrue(controller.submit(102, key).isSuccess)
        assertEquals(1, nativeApi.keySubmissions)
        assertTrue(controller.stop().isSuccess)
        assertEquals(InputTransportState.Stopped, controller.snapshot().state)
        assertEquals(InputTransportError.NotRunning, controller.submit(103, key).error)

        assertTrue(controller.prepare(loopbackConfig(initialInputSequence = 9)).isSuccess)
        assertTrue(controller.start().isSuccess)
        assertTrue(controller.stop().isSuccess)
        assertEquals(2, nativeApi.createCalls)
        assertEquals(2, nativeApi.destroyCalls)

        controller.close()
        assertEquals(InputTransportState.Closed, controller.snapshot().state)
        assertEquals(InputTransportError.Closed, controller.prepare(loopbackConfig()).error)
    }

    @Test
    fun invalidConfigurationNeverCreatesTheNativeSender() {
        val nativeApi = FakeNativeApi()
        val controller = NativeSclInputTransportController.forTesting(nativeApi)

        assertEquals(
            InputTransportError.InvalidEndpoint,
            controller.prepare(InputTransportConfig("", 9000)).error,
        )
        assertEquals(
            InputTransportError.InvalidDatagramBudget,
            controller.prepare(InputTransportConfig("127.0.0.1", 9000, maxWireDatagramSize = 416)).error,
        )
        assertEquals(0, nativeApi.createCalls)
        assertNull(controller.snapshot().boundLocalPort)
    }

    private fun loopbackConfig(initialInputSequence: Long = 0): InputTransportConfig = InputTransportConfig(
        remoteAddress = "127.0.0.1",
        remotePort = 9000,
        initialInputSequence = initialInputSequence,
    )

    private class FakeNativeApi : InputTransportNativeApi {
        var createCalls: Int = 0
        var destroyCalls: Int = 0
        var keySubmissions: Int = 0

        override fun create(
            remoteAddress: String,
            remotePort: Int,
            localPort: Int,
            maxWireDatagramSize: Int,
            initialInputSequence: Long,
        ): Long {
            createCalls += 1
            return createCalls.toLong()
        }

        override fun destroy(handle: Long): Int {
            destroyCalls += 1
            return InputTransportError.None.ordinal
        }

        override fun submitKey(
            handle: Long,
            eventTimeUs: Long,
            deviceSlot: Int,
            usagePage: Int,
            usageId: Int,
            action: Int,
            repeatCount: Int,
            modifierMask: Int,
        ): Int {
            keySubmissions += 1
            return InputTransportError.None.ordinal
        }

        override fun submitTouchFrame(
            handle: Long,
            eventTimeUs: Long,
            deviceKind: Int,
            deviceSlot: Int,
            action: Int,
            actionPointerId: Int,
            pointerCount: Int,
            contactScratch: ByteBuffer,
        ): Int = InputTransportError.None.ordinal

        override fun submitPointerAbsolute(
            handle: Long,
            eventTimeUs: Long,
            deviceKind: Int,
            deviceSlot: Int,
            xNormalized: Int,
            yNormalized: Int,
            buttonMask: Int,
            pointerFlags: Int,
            pressure: Int,
        ): Int = InputTransportError.None.ordinal

        override fun submitPointerRelative(
            handle: Long,
            eventTimeUs: Long,
            deviceKind: Int,
            deviceSlot: Int,
            deltaXQ1616: Int,
            deltaYQ1616: Int,
            buttonMask: Int,
        ): Int = InputTransportError.None.ordinal

        override fun submitScroll(
            handle: Long,
            eventTimeUs: Long,
            deviceKind: Int,
            deviceSlot: Int,
            horizontalQ88: Int,
            verticalQ88: Int,
            buttonMask: Int,
        ): Int = InputTransportError.None.ordinal

        override fun submitGamepadState(
            handle: Long,
            eventTimeUs: Long,
            deviceSlot: Int,
            buttonMask: Int,
            leftX: Int,
            leftY: Int,
            rightX: Int,
            rightY: Int,
            leftTrigger: Int,
            rightTrigger: Int,
        ): Int = InputTransportError.None.ordinal

        override fun submitReset(
            handle: Long,
            eventTimeUs: Long,
            deviceKind: Int,
            deviceSlot: Int,
            scope: Int,
            reason: Int,
        ): Int = InputTransportError.None.ordinal

        override fun snapshot(handle: Long): LongArray = LongArray(NATIVE_SNAPSHOT_VALUES).apply {
            this[30] = 1L
            this[32] = 50_001L
            this[33] = 1L
        }

        private companion object {
            const val NATIVE_SNAPSHOT_VALUES = 34
        }
    }
}
