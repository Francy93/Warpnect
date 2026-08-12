package io.warpnect.input.mapping

import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.video.render.VideoRenderGeometry
import io.warpnect.video.render.VideoViewportGeometry
import io.warpnect.video.render.VideoViewportGeometryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVideoViewportInputMapperTest {
    @Test
    fun matchingAspectPreservesNormalizedTouchCoordinateEdges() {
        val store = VideoViewportGeometryStore(geometry(1920, 1080, 1920, 1080))
        val sink = RecordingSink()
        val mapper = RemoteVideoViewportInputMapper(store, sink)

        assertAccepted(mapper.onInputEvent(10, touch(InputTouchAction.Down, 0, listOf(contact(0, 0, 0)))))
        val edgeCoordinates = listOf(1, 32767, 32768, 65534)
        edgeCoordinates.forEachIndexed { index, coordinate ->
            assertAccepted(
                mapper.onInputEvent(
                    11L + index,
                    touch(
                        InputTouchAction.Move,
                        INPUT_NO_ACTION_POINTER_ID,
                        listOf(contact(0, coordinate, coordinate)),
                    ),
                ),
            )
        }
        assertAccepted(mapper.onInputEvent(20, touch(InputTouchAction.Up, 0, listOf(contact(0, 65535, 65535)))))

        val mapped = sink.events.filterIsInstance<InputTouchFrame>()
        val coordinates = mapped.map { it.contacts.single().xNormalized }
        assertEquals(listOf(0, 1, 32767, 32768, 65534, 65535), coordinates)
        assertEquals(65535, mapped.last().contacts.single().yNormalized)
    }

    @Test
    fun outsideDownIsSuppressedWhileActiveMoveAndUpClampToContent() {
        val store = VideoViewportGeometryStore(geometry(4, 3, 1920, 1080))
        val sink = RecordingSink()
        val mapper = RemoteVideoViewportInputMapper(store, sink)

        assertAccepted(mapper.onInputEvent(1, touch(InputTouchAction.Down, 0, listOf(contact(0, 0, 32768)))))
        assertTrue(sink.events.isEmpty())
        assertEquals(1L, mapper.snapshot().outsideContentDowns)

        assertAccepted(mapper.onInputEvent(2, touch(InputTouchAction.Down, 0, listOf(contact(0, 32768, 32768)))))
        assertAccepted(
            mapper.onInputEvent(
                3,
                touch(InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(contact(0, 0, 32768))),
            ),
        )
        assertAccepted(mapper.onInputEvent(4, touch(InputTouchAction.Up, 0, listOf(contact(0, 0, 32768)))))

        val mapped = sink.events.filterIsInstance<InputTouchFrame>()
        assertEquals(0, mapped[1].contacts.single().xNormalized)
        assertEquals(0, mapped[2].contacts.single().xNormalized)
        assertEquals(2L, mapper.snapshot().activeCoordinatesClamped)
    }

    @Test
    fun outsideAddedPointerResetsActiveGestureWithoutForwardingPartialFrame() {
        val store = VideoViewportGeometryStore(geometry(4, 3, 1920, 1080))
        val sink = RecordingSink()
        val mapper = RemoteVideoViewportInputMapper(store, sink)

        assertAccepted(mapper.onInputEvent(1, touch(InputTouchAction.Down, 0, listOf(contact(0, 32768, 32768)))))
        assertAccepted(
            mapper.onInputEvent(
                2,
                touch(
                    InputTouchAction.PointerDown,
                    1,
                    listOf(contact(0, 32768, 32768), contact(1, 0, 32768)),
                ),
            ),
        )

        assertEquals(2, sink.events.size)
        assertTrue(sink.events[1] is InputResetState)
        assertEquals(InputResetReason.ErrorRecovery, (sink.events[1] as InputResetState).reason)
        assertEquals(1L, mapper.snapshot().eventsSuppressed)
    }

    @Test
    fun relativePointerIsRescaledFromSurfaceToContentWidth() {
        val store = VideoViewportGeometryStore(geometry(4, 3, 1920, 1080))
        val sink = RecordingSink()
        val mapper = RemoteVideoViewportInputMapper(store, sink)

        assertAccepted(
            mapper.onInputEvent(
                1,
                InputPointerRelative(
                    deviceKind = InputDeviceKind.Mouse,
                    deviceSlot = 2,
                    deltaXQ16_16 = 65_536,
                    deltaYQ16_16 = 0,
                    buttonMask = 0,
                ),
            ),
        )

        val mapped = sink.events.single() as InputPointerRelative
        assertEquals(87_381, mapped.deltaXQ16_16)
        assertEquals(0, mapped.deltaYQ16_16)
    }

    @Test
    fun geometryChangeResetsAndSuppressesActiveGestureContinuation() {
        val store = VideoViewportGeometryStore(geometry(1920, 1080, 1920, 1080, surfaceGeneration = 1))
        val sink = RecordingSink()
        val mapper = RemoteVideoViewportInputMapper(store, sink)
        assertAccepted(mapper.onInputEvent(1, touch(InputTouchAction.Down, 0, listOf(contact(0, 32768, 32768)))))

        store.update(geometry(4, 3, 1920, 1080, surfaceGeneration = 2))
        assertAccepted(
            mapper.onInputEvent(
                2,
                touch(InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(contact(0, 32768, 32768))),
            ),
        )

        assertEquals(2, sink.events.size)
        assertTrue(sink.events[1] is InputResetState)
        assertEquals(1L, mapper.snapshot().geometryChangeResets)
        assertEquals(1L, mapper.snapshot().eventsSuppressed)
        assertFalse(mapper.snapshot().closed)
    }

    private fun geometry(
        videoWidth: Int,
        videoHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        surfaceGeneration: Long = 1L,
    ): VideoViewportGeometry = VideoRenderGeometry.viewportGeometry(
        sourceWidth = videoWidth,
        sourceHeight = videoHeight,
        containerWidth = surfaceWidth,
        containerHeight = surfaceHeight,
        surfaceGeneration = surfaceGeneration,
        videoConfigGeneration = 1L,
    )

    private fun touch(action: InputTouchAction, actionPointerId: Int, contacts: List<InputTouchContact>) =
        InputTouchFrame(
            deviceKind = InputDeviceKind.Touchscreen,
            deviceSlot = 1,
            action = action,
            actionPointerId = actionPointerId,
            contacts = contacts,
        )

    private fun contact(pointerId: Int, x: Int, y: Int) = InputTouchContact(
        pointerId = pointerId,
        toolType = InputTouchToolType.Finger,
        pointerFlags = 0,
        xNormalized = x,
        yNormalized = y,
    )

    private fun assertAccepted(result: InputSinkResult) {
        assertTrue(result is InputSinkResult.Accepted)
    }

    private class RecordingSink : InputEventSink {
        val events = mutableListOf<WarpnectInputEvent>()

        override fun onInputEvent(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult {
            events += event
            return InputSinkResult.Accepted
        }
    }
}
