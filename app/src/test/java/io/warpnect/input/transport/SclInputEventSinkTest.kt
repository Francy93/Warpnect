package io.warpnect.input.transport

import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
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
import io.warpnect.platform.input.transport.InputTouchScratch
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SclInputEventSinkTest {
    @Test
    fun sinkForwardsEveryPortableEventSynchronously() {
        val transport = FakeTransport()
        val sink = SclInputEventSink(transport)
        val events = listOf(
            InputKeyEvent(1, 7, 4, InputKeyAction.Down, 0, 2),
            InputTouchFrame(
                InputDeviceKind.Touchscreen,
                2,
                InputTouchAction.Move,
                INPUT_NO_ACTION_POINTER_ID,
                listOf(InputTouchContact(0, InputTouchToolType.Finger, 0, 10, 20)),
            ),
            InputPointerAbsolute(InputDeviceKind.Mouse, 3, 30, 40, 1, 0),
            InputPointerRelative(InputDeviceKind.Mouse, 3, 65_536, -65_536, 1),
            InputScroll(InputDeviceKind.Mouse, 3, 256, -128, 1),
            InputGamepadState(4, 1, 1, -2, 3, -4, 5, 6),
            InputResetState(
                InputDeviceKind.Unknown,
                65_535,
                InputResetScope.AllDevices,
                InputResetReason.FocusLost,
            ),
        )

        events.forEachIndexed { index, event ->
            assertEquals(InputSinkResult.Accepted, sink.onInputEvent(10_000L + index, event))
            assertEquals(10_000L + index, transport.lastEventTimeUs)
            assertSame(event, transport.lastEvent)
        }
        assertEquals(events.size, transport.submissionCount)
    }

    @Test
    fun transportFailureBecomesRejectedSinkResult() {
        val transport = FakeTransport(error = InputTransportError.WouldBlock)
        val result = SclInputEventSink(transport).onInputEvent(
            77,
            InputKeyEvent(0, 7, 4, InputKeyAction.Up, 0, 0),
        )

        assertEquals(InputSinkResult.Rejected("Input transport WouldBlock"), result)
    }

    @Test
    fun touchScratchUsesOneNativeOrderDirectRecordBuffer() {
        val scratch = InputTouchScratch()
        val firstBuffer = scratch.buffer
        val frame = InputTouchFrame(
            InputDeviceKind.Touchscreen,
            1,
            InputTouchAction.Move,
            INPUT_NO_ACTION_POINTER_ID,
            listOf(
                InputTouchContact(4, InputTouchToolType.Stylus, 3, 100, 200, 300, 400),
                InputTouchContact(7, InputTouchToolType.Finger, 0, 500, 600),
            ),
        )

        scratch.write(frame)
        assertTrue(firstBuffer.isDirect)
        assertEquals(896, firstBuffer.capacity())
        assertEquals(ByteOrder.nativeOrder(), firstBuffer.order())
        val values = firstBuffer.duplicate().order(ByteOrder.nativeOrder())
        assertEquals(4, values.int)
        assertEquals(InputTouchToolType.Stylus.ordinal, values.int)
        assertEquals(3, values.int)
        assertEquals(100, values.int)
        assertEquals(200, values.int)
        assertEquals(300, values.int)
        assertEquals(400, values.int)

        scratch.write(frame.copy(contacts = frame.contacts.take(1)))
        assertSame(firstBuffer, scratch.buffer)
    }

    @Test
    fun transportConfigRequiresTheFullInputDatagramBudget() {
        assertEquals(
            InputTransportError.InvalidDatagramBudget,
            InputTransportConfig("127.0.0.1", 9000, maxWireDatagramSize = 416).validate(),
        )
        assertEquals(
            InputTransportError.None,
            InputTransportConfig("127.0.0.1", 9000, maxWireDatagramSize = 417).validate(),
        )
    }

    private class FakeTransport(
        private val error: InputTransportError = InputTransportError.None,
    ) : InputTransportController {
        var lastEventTimeUs: Long = -1L
        var lastEvent: WarpnectInputEvent? = null
        var submissionCount: Int = 0

        override fun prepare(config: InputTransportConfig): InputTransportResult =
            InputTransportResult(InputTransportError.None, InputTransportSnapshot())

        override fun start(): InputTransportResult =
            InputTransportResult(InputTransportError.None, InputTransportSnapshot())

        override fun submit(eventTimeUs: Long, event: WarpnectInputEvent): InputTransportResult {
            lastEventTimeUs = eventTimeUs
            lastEvent = event
            submissionCount += 1
            return InputTransportResult(error, InputTransportSnapshot(lastError = error))
        }

        override fun stop(): InputTransportResult =
            InputTransportResult(InputTransportError.None, InputTransportSnapshot())

        override fun snapshot(): InputTransportSnapshot = InputTransportSnapshot()

        override fun close() = Unit
    }
}
