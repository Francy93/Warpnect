package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputInjectionStateTrackerTest {
    @Test
    fun keyUsesLocalUptimeAndFailedUpRetainsStateForReset() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)
        val down = key(action = AndroidInjectionConstants.KEY_ACTION_DOWN, sourceTimeUs = 123_456)

        assertEquals(InputInjectionServiceResult.SubmittedAsync, tracker.injectKey(down))
        dispatcher.keyResults += InputInjectionServiceResult.InjectionRejected
        assertEquals(
            InputInjectionServiceResult.InjectionRejected,
            tracker.injectKey(down.copy(action = AndroidInjectionConstants.KEY_ACTION_UP, sourceEventTimeUs = 123_457)),
        )
        assertEquals(100L, dispatcher.keyCalls.first().eventTimeMs)
        assertEquals(100L, dispatcher.keyCalls.first().downTimeMs)
        assertEquals(101L, dispatcher.keyCalls[1].eventTimeMs)
        assertEquals(100L, dispatcher.keyCalls[1].downTimeMs)

        assertEquals(InputInjectionServiceResult.ResetComplete, tracker.reset(InputResetScope.AllSlots, 0, 1))
        assertEquals(AndroidInjectionConstants.KEY_ACTION_UP, dispatcher.keyCalls.last().event.action)
        val snapshot = tracker.snapshot(InputInjectionState.Running, true, true, true)
        assertEquals(123_457L, snapshot.lastSourceEventTimeUs)
        assertEquals(1, snapshot.syntheticKeyUps)
        assertEquals(0, snapshot.activePressedKeys)
    }

    @Test
    fun orphanUpDoesNotAllocateStateAndStillSubmits() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)

        assertEquals(
            InputInjectionServiceResult.SubmittedAsync,
            tracker.injectKey(key(action = AndroidInjectionConstants.KEY_ACTION_UP)),
        )
        val snapshot = tracker.snapshot(InputInjectionState.Running, true, true, true)
        assertEquals(1, snapshot.orphanKeyUps)
        assertEquals(0, snapshot.trackedStateSlots)
    }

    @Test
    fun touchRequiresDownAndResetUsesLatestBoundedSnapshot() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)
        val move = touch(action = AndroidInjectionConstants.MOTION_ACTION_MOVE)
        assertEquals(InputInjectionServiceResult.InvalidTouchSequence, tracker.injectTouch(move))

        assertEquals(
            InputInjectionServiceResult.SubmittedAsync,
            tracker.injectTouch(touch(action = AndroidInjectionConstants.MOTION_ACTION_DOWN)),
        )
        assertEquals(InputInjectionServiceResult.SubmittedAsync, tracker.injectTouch(move))
        assertEquals(InputInjectionServiceResult.ResetComplete, tracker.reset(InputResetScope.ThisSlot, 1, 1))
        val cancel = dispatcher.touchCalls.last().event
        assertEquals(AndroidInjectionConstants.MOTION_ACTION_CANCEL, cancel.actionMasked)
        assertEquals(20f, cancel.pointers[0].xPx)
        assertEquals(1, tracker.snapshot(InputInjectionState.Running, true, true, true).syntheticTouchCancels)
    }

    @Test
    fun pointerUpRemovesLiftedContactFromTheResetSnapshot() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)
        assertEquals(
            InputInjectionServiceResult.SubmittedAsync,
            tracker.injectTouch(touch(action = AndroidInjectionConstants.MOTION_ACTION_DOWN)),
        )
        val contacts = arrayOf(contact(0, 20f), contact(1, 40f))
        assertEquals(
            InputInjectionServiceResult.SubmittedAsync,
            tracker.injectTouch(
                touch(
                    action = AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN,
                    pointers = contacts,
                    actionIndex = 1,
                ),
            ),
        )
        assertEquals(
            InputInjectionServiceResult.SubmittedAsync,
            tracker.injectTouch(
                touch(
                    action = AndroidInjectionConstants.MOTION_ACTION_POINTER_UP,
                    pointers = contacts,
                    actionIndex = 1,
                ),
            ),
        )

        tracker.reset(InputResetScope.ThisSlot, 1, 1)
        val cancel = dispatcher.touchCalls.last().event
        assertEquals(AndroidInjectionConstants.MOTION_ACTION_CANCEL, cancel.actionMasked)
        assertEquals(1, cancel.pointers.size)
        assertEquals(0, cancel.pointers.single().pointerId)
    }

    @Test
    fun resetNeutralizesPointerAndJoystickWithoutUnboundedState() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)
        assertEquals(InputInjectionServiceResult.SubmittedAsync, tracker.injectPointer(pointer(buttonState = 1)))
        assertEquals(InputInjectionServiceResult.SubmittedAsync, tracker.injectJoystick(joystick(leftX = 0.5f)))

        assertEquals(InputInjectionServiceResult.ResetComplete, tracker.reset(InputResetScope.AllSlots, 0, 1))
        assertEquals(AndroidInjectionConstants.MOTION_ACTION_CANCEL, dispatcher.pointerCalls.last().event.action)
        assertEquals(0, dispatcher.pointerCalls.last().event.buttonState)
        assertTrue(dispatcher.joystickCalls.last().event.leftX == 0f)
        val snapshot = tracker.snapshot(InputInjectionState.Running, true, true, true)
        assertEquals(1, snapshot.syntheticPointerReleases)
        assertEquals(1, snapshot.syntheticJoystickNeutralEvents)
        assertEquals(0, snapshot.trackedStateSlots)
    }

    @Test
    fun stateAndPressedKeyCapacityAreImmediateFailures() {
        val dispatcher = RecordingDispatcher()
        val tracker = InputInjectionStateTracker(
            InputInjectionConfig(maxTrackedInjectionSlots = 1, maxPressedKeysPerSlot = 1),
            { 100L },
            dispatcher,
        )
        assertEquals(InputInjectionServiceResult.SubmittedAsync, tracker.injectKey(key(stateSlot = 1, keyCode = 10)))
        assertEquals(
            InputInjectionServiceResult.PressedKeyCapacityReached,
            tracker.injectKey(key(stateSlot = 1, keyCode = 11)),
        )
        assertEquals(
            InputInjectionServiceResult.StateSlotCapacityReached,
            tracker.injectKey(key(stateSlot = 2, keyCode = 12)),
        )
        assertEquals(1, dispatcher.keyCalls.size)
    }

    @Test
    fun invalidTouchIdsAreRejectedBeforeDispatcher() {
        val dispatcher = RecordingDispatcher()
        val tracker = tracker(dispatcher)
        val invalid = touch(
            action = AndroidInjectionConstants.MOTION_ACTION_DOWN,
            pointers = arrayOf(contact(0), contact(0)),
        )
        assertEquals(InputInjectionServiceResult.InvalidEvent, tracker.injectTouch(invalid))
        assertTrue(dispatcher.touchCalls.isEmpty())
        assertEquals(
            InputInjectionError.InvalidEvent,
            tracker.snapshot(InputInjectionState.Running, true, true, true).lastError,
        )
    }

    private fun tracker(dispatcher: RecordingDispatcher): InputInjectionStateTracker {
        var now = 99L
        return InputInjectionStateTracker(InputInjectionConfig(), { ++now }, dispatcher)
    }

    private fun key(
        action: Int = AndroidInjectionConstants.KEY_ACTION_DOWN,
        sourceTimeUs: Long = 1L,
        stateSlot: Int = 1,
        keyCode: Int = 42,
    ): AndroidKeyInjectionEvent = AndroidKeyInjectionEvent(
        stateSlot,
        sourceTimeUs,
        action,
        keyCode,
        source = AndroidInjectionConstants.SOURCE_KEYBOARD,
        displayId = 0,
    )

    private fun contact(pointerId: Int = 0, x: Float = 20f): AndroidTouchPointer = AndroidTouchPointer(
        pointerId,
        1,
        x,
        30f,
        1f,
        0.5f,
    )

    private fun touch(
        action: Int,
        pointers: Array<AndroidTouchPointer> = arrayOf(contact()),
        actionIndex: Int = 0,
    ): AndroidTouchInjectionEvent = AndroidTouchInjectionEvent(
        stateSlot = 1,
        sourceEventTimeUs = 2L,
        actionMasked = action,
        actionIndex = actionIndex,
        pointers = pointers,
        source = AndroidInjectionConstants.SOURCE_TOUCHSCREEN,
        displayId = 0,
    )

    private fun pointer(buttonState: Int): AndroidPointerInjectionEvent = AndroidPointerInjectionEvent(
        stateSlot = 2,
        sourceEventTimeUs = 3L,
        action = AndroidInjectionConstants.MOTION_ACTION_MOVE,
        xPx = 1f,
        yPx = 2f,
        buttonState = buttonState,
        source = AndroidInjectionConstants.SOURCE_MOUSE,
        displayId = 0,
    )

    private fun joystick(leftX: Float): AndroidJoystickInjectionEvent = AndroidJoystickInjectionEvent(
        stateSlot = 3,
        sourceEventTimeUs = 4L,
        leftX = leftX,
        leftY = 0f,
        rightX = 0f,
        rightY = 0f,
        leftTrigger = 0f,
        rightTrigger = 0f,
        hatX = 0f,
        hatY = 0f,
        source = AndroidInjectionConstants.SOURCE_JOYSTICK,
        displayId = 0,
    )

    private class RecordingDispatcher : AndroidInputEventDispatcher {
        data class KeyCall(val event: AndroidKeyInjectionEvent, val eventTimeMs: Long, val downTimeMs: Long)
        data class TouchCall(val event: AndroidTouchInjectionEvent, val eventTimeMs: Long, val downTimeMs: Long)
        data class PointerCall(val event: AndroidPointerInjectionEvent, val eventTimeMs: Long, val downTimeMs: Long)
        data class JoystickCall(val event: AndroidJoystickInjectionEvent, val eventTimeMs: Long)

        val keyCalls = mutableListOf<KeyCall>()
        val touchCalls = mutableListOf<TouchCall>()
        val pointerCalls = mutableListOf<PointerCall>()
        val joystickCalls = mutableListOf<JoystickCall>()
        val keyResults = ArrayDeque<InputInjectionServiceResult>()

        override fun submitKey(
            event: AndroidKeyInjectionEvent,
            eventTimeMs: Long,
            downTimeMs: Long,
            mode: InputInjectionMode,
            targetUid: Int,
        ): InputInjectionServiceResult {
            keyCalls += KeyCall(event, eventTimeMs, downTimeMs)
            return if (keyResults.isEmpty()) InputInjectionServiceResult.SubmittedAsync else keyResults.removeFirst()
        }

        override fun submitTouch(
            event: AndroidTouchInjectionEvent,
            eventTimeMs: Long,
            downTimeMs: Long,
            mode: InputInjectionMode,
            targetUid: Int,
        ): InputInjectionServiceResult {
            touchCalls += TouchCall(event, eventTimeMs, downTimeMs)
            return InputInjectionServiceResult.SubmittedAsync
        }

        override fun submitPointer(
            event: AndroidPointerInjectionEvent,
            eventTimeMs: Long,
            downTimeMs: Long,
            mode: InputInjectionMode,
            targetUid: Int,
        ): InputInjectionServiceResult {
            pointerCalls += PointerCall(event, eventTimeMs, downTimeMs)
            return InputInjectionServiceResult.SubmittedAsync
        }

        override fun submitJoystick(
            event: AndroidJoystickInjectionEvent,
            eventTimeMs: Long,
            mode: InputInjectionMode,
            targetUid: Int,
        ): InputInjectionServiceResult {
            joystickCalls += JoystickCall(event, eventTimeMs)
            return InputInjectionServiceResult.SubmittedAsync
        }
    }
}
