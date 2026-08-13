package io.warpnect.platform.input.transport

import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputReceiverError
import io.warpnect.input.transport.InputReceiverWaitResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSclInputReceiverControllerTest {
    @Test
    fun usesOnePersistentNativeOrderBridgeAndDecodesPortableInput() {
        val nativeApi = FakeNativeApi()
        val controller = NativeSclInputReceiverController(nativeApi)

        assertEquals(InputReceiverError.NotPrepared, controller.start().error)
        assertTrue(controller.prepare(loopbackConfig()).isSuccess)
        assertTrue(controller.start().isSuccess)

        val first = controller.waitForInputEvent(1_000)
        val second = controller.waitForInputEvent(1_000)

        val firstEvent = (first as InputReceiverWaitResult.EventReady).event
        assertEquals(42L, firstEvent.sequenceNumber)
        assertEquals(123_456L, firstEvent.sourceEventTimeUs)
        assertEquals(InputKeyEvent(7, 7, 40, InputKeyAction.Down, 2, 0x81), firstEvent.event)
        assertSame(nativeApi.bridgeBuffers[0], nativeApi.bridgeBuffers[1])
        assertEquals(ByteOrder.nativeOrder(), nativeApi.bridgeBuffers.first().order())

        assertTrue(controller.stop().isSuccess)
        assertEquals(1, nativeApi.destroyCalls)
        controller.close()
    }

    @Test
    fun rejectsNonNumericOrEphemeralEndpointConfigurationsBeforeNativeCreate() {
        val nativeApi = FakeNativeApi()
        val controller = NativeSclInputReceiverController(nativeApi)

        assertEquals(
            InputReceiverError.InvalidEndpoint,
            controller.prepare(loopbackConfig(expectedRemoteAddress = "sender.example")).error,
        )
        assertEquals(
            InputReceiverError.InvalidEndpoint,
            controller.prepare(loopbackConfig(localPort = 0)).error,
        )
        assertEquals(0, nativeApi.createCalls)
    }

    private fun loopbackConfig(
        localPort: Int = 40_001,
        expectedRemoteAddress: String = "127.0.0.1",
    ): InputReceiverConfig = InputReceiverConfig(
        localAddress = "0.0.0.0",
        localPort = localPort,
        expectedRemoteAddress = expectedRemoteAddress,
        expectedRemotePort = 40_002,
    )

    private class FakeNativeApi : InputReceiverNativeApi {
        var createCalls = 0
        var destroyCalls = 0
        val bridgeBuffers = mutableListOf<ByteBuffer>()

        override fun create(
            localAddress: String,
            localPort: Int,
            expectedRemoteAddress: String,
            expectedRemotePort: Int,
            maxWireDatagramSize: Int,
        ): Long = (++createCalls).toLong()

        override fun destroy(handle: Long): Int {
            destroyCalls += 1
            return InputReceiverError.None.code
        }

        override fun waitForEvent(handle: Long, timeoutUs: Long, bridgeBuffer: ByteBuffer): Int {
            bridgeBuffers += bridgeBuffer
            bridgeBuffer.putInt(0, 1)
            bridgeBuffer.putInt(4, 1)
            bridgeBuffer.putInt(8, 7)
            bridgeBuffer.putLong(16, 42L)
            bridgeBuffer.putLong(24, 123_456L)
            bridgeBuffer.putInt(32, 7)
            bridgeBuffer.putInt(36, 40)
            bridgeBuffer.putInt(40, InputKeyAction.Down.ordinal)
            bridgeBuffer.putInt(44, 2)
            bridgeBuffer.putInt(48, 0x81)
            return 1
        }

        override fun interrupt(handle: Long): Int = InputReceiverError.None.code

        override fun wake(handle: Long): Int = InputReceiverError.None.code

        override fun snapshot(handle: Long): LongArray = LongArray(23).apply {
            this[0] = 1L
            this[2] = 40_001L
            this[20] = InputReceiverError.None.code.toLong()
            this[21] = 1L
        }
    }
}
