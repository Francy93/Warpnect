package io.warpnect.input.reliability

import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.INPUT_POINTER_BUTTON_PRIMARY
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.input.model.WarpnectInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputStateConvergenceControllerTest {
    @Test
    fun productionProfileIsZeroWaitAndTheBaselineRemainsExplicit() {
        val production = InputReliabilityConfig.ultraLowLatencyConvergent()
        val baseline = InputReliabilityConfig.bestEffortBaseline()

        assertEquals(0L, production.networkReorderWaitUs)
        assertEquals(2, production.criticalCopies)
        assertEquals(3, production.resetCopies)
        assertEquals(64, production.recentTransportSequenceCapacity)
        assertEquals(32, production.recentSemanticDuplicateCapacity)
        assertTrue(production.touchRepairEnabled)
        assertEquals(1, baseline.criticalCopies)
        assertEquals(1, baseline.resetCopies)
        assertEquals(0, baseline.recentTransportSequenceCapacity)
        assertFalse(baseline.touchRepairEnabled)
        assertNotEquals(
            InputReliabilityConfigurationError.None,
            production.copy(networkReorderWaitUs = 1L).validate(),
        )
    }

    @Test
    fun bestEffortBaselinePreservesArrivalOrderWithoutSemanticOrSequenceSuppression() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = InputStateConvergenceController(InputReliabilityConfig.bestEffortBaseline())
        val keyDown = key(InputKeyAction.Down)

        controller.process(envelope(101, 2_000, key(InputKeyAction.Up)), sink(delivered))
        controller.process(envelope(100, 1_000, keyDown), sink(delivered))
        controller.process(envelope(100, 1_000, keyDown), sink(delivered))

        assertEquals(
            listOf(InputKeyAction.Up, InputKeyAction.Down, InputKeyAction.Down),
            delivered.map { (it.event as InputKeyEvent).action },
        )
    }

    @Test
    fun everyNonEmptyCriticalCopySubsetInjectsExactlyOneSemanticTransition() {
        for (copyCount in 1..3) {
            val configurations = 1 until (1 shl copyCount)
            for (deliveryMask in configurations) {
                val delivered = mutableListOf<InputEventEnvelope>()
                val controller = InputStateConvergenceController(
                    InputReliabilityConfig.ultraLowLatencyConvergent().copy(criticalCopies = copyCount),
                )
                val event = key(InputKeyAction.Down)
                repeat(copyCount) { index ->
                    if ((deliveryMask and (1 shl index)) != 0) {
                        controller.process(envelope(100L + index, 1_000L, event), sink(delivered))
                    }
                }
                assertEquals(
                    "copyCount=$copyCount deliveryMask=$deliveryMask",
                    1,
                    delivered.size,
                )
            }
        }
    }

    @Test
    fun everyNonEmptyResetCopySubsetInjectsOneResetAndKeepsTheNewestWatermark() {
        for (copyCount in 1..3) {
            val configurations = 1 until (1 shl copyCount)
            for (deliveryMask in configurations) {
                val delivered = mutableListOf<InputEventEnvelope>()
                val controller = InputStateConvergenceController(
                    InputReliabilityConfig.ultraLowLatencyConvergent().copy(resetCopies = copyCount),
                )
                val reset = InputResetState(
                    InputDeviceKind.Unknown,
                    65_535,
                    InputResetScope.AllDevices,
                    InputResetReason.ErrorRecovery,
                )
                var newestDelivered: Long? = null
                repeat(copyCount) { index ->
                    val sequence = 200L + index
                    if ((deliveryMask and (1 shl index)) != 0) {
                        controller.process(envelope(sequence, 2_000L, reset), sink(delivered))
                        newestDelivered = sequence
                    }
                }
                assertEquals(
                    "copyCount=$copyCount deliveryMask=$deliveryMask",
                    1,
                    delivered.size,
                )
                assertEquals(newestDelivered, controller.snapshot().globalResetSequence)
            }
        }
    }

    @Test
    fun semanticRedundancyIsAppliedOnceButAdvancesItsFreshnessWatermark() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val keyDown = key(InputKeyAction.Down)

        assertEquals(
            InputConvergenceOutcome.Forwarded,
            controller.process(envelope(100, 1_000, keyDown), sink(delivered)).outcome,
        )
        assertEquals(
            InputConvergenceOutcome.SemanticDuplicateDropped,
            controller.process(envelope(101, 1_000, keyDown), sink(delivered)).outcome,
        )
        assertEquals(
            InputConvergenceOutcome.Forwarded,
            controller.process(envelope(102, 1_000, key(InputKeyAction.Up)), sink(delivered)).outcome,
        )

        assertEquals(2, delivered.size)
        assertEquals(1L, controller.snapshot().semanticDuplicateDrops)
    }

    @Test
    fun identicalTimestampDoesNotSuppressADifferentSemanticEvent() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()

        controller.process(envelope(10, 5_000, key(InputKeyAction.Down, usageId = 4)), sink(delivered))
        controller.process(envelope(11, 5_000, key(InputKeyAction.Down, usageId = 5)), sink(delivered))

        assertEquals(2, delivered.size)
        assertEquals(0L, controller.snapshot().semanticDuplicateDrops)
    }

    @Test
    fun sameSemanticEventOutsideTheImmediateCopyRangeIsNotDeduplicated() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val keyDown = key(InputKeyAction.Down)

        controller.process(envelope(100, 5_000, keyDown), sink(delivered))
        controller.process(envelope(103, 5_000, keyDown), sink(delivered))
        val duplicateOfSecond = controller.process(envelope(104, 5_000, keyDown), sink(delivered))

        assertEquals(2, delivered.size)
        assertEquals(InputConvergenceOutcome.SemanticDuplicateDropped, duplicateOfSecond.outcome)
        assertEquals(1L, controller.snapshot().semanticDuplicateDrops)
    }

    @Test
    fun outOfOrderAdjacentRedundantRelativeTransitionDoesNotDoubleItsDelta() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val pressedDelta = InputPointerRelative(
            InputDeviceKind.Mouse,
            2,
            7_000,
            0,
            INPUT_POINTER_BUTTON_PRIMARY,
        )

        controller.process(envelope(101, 8_000, pressedDelta), sink(delivered))
        val duplicate = controller.process(envelope(100, 8_000, pressedDelta), sink(delivered))

        assertEquals(InputConvergenceOutcome.SemanticDuplicateDropped, duplicate.outcome)
        assertEquals(1, delivered.size)
    }

    @Test
    fun sequenceMathRemainsCorrectAcrossUnsignedWrap() {
        assertTrue(InputSequenceMath.isNewer(0xFFFF_FFFFL, 0xFFFF_FFFEL))
        assertTrue(InputSequenceMath.isNewer(0L, 0xFFFF_FFFFL))
        assertTrue(InputSequenceMath.isNewer(1L, 0L))
        assertFalse(InputSequenceMath.isNewer(0xFFFF_FFFEL, 0L))
        assertTrue(InputSequenceMath.isWithinDistance(0L, 0xFFFF_FFFFL, 1))
        assertTrue(InputSequenceMath.isWithinDistance(0xFFFF_FFFEL, 0L, 2))
        assertFalse(InputSequenceMath.isWithinDistance(0xFFFF_FFFDL, 0L, 2))
    }

    @Test
    fun lateKeyDownCannotResurrectANewerKeyUp() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()

        controller.process(envelope(101, 20, key(InputKeyAction.Up)), sink(delivered))
        val lateDown = controller.process(envelope(100, 19, key(InputKeyAction.Down)), sink(delivered))

        assertEquals(InputConvergenceOutcome.StaleSequenceDropped, lateDown.outcome)
        assertEquals(listOf(InputKeyAction.Up), delivered.map { (it.event as InputKeyEvent).action })
    }

    @Test
    fun olderGamepadAndAbsoluteSnapshotsCannotRestoreNewerState() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val neutral = InputGamepadState(3, 0, 0, 0, 0, 0, 0, 0)
        val pressed = neutral.copy(buttonMask = 1)
        val absolute = InputPointerAbsolute(InputDeviceKind.Mouse, 4, 100, 200, 0, 0)

        controller.process(envelope(11, 110, neutral), sink(delivered))
        assertEquals(
            InputConvergenceOutcome.StaleSequenceDropped,
            controller.process(envelope(10, 100, pressed), sink(delivered)).outcome,
        )
        controller.process(envelope(21, 210, absolute), sink(delivered))
        assertEquals(
            InputConvergenceOutcome.StaleSequenceDropped,
            controller.process(envelope(20, 200, absolute.copy(xNormalized = 90)), sink(delivered)).outcome,
        )

        assertEquals(2, delivered.size)
    }

    @Test
    fun uniqueLateRelativeDeltaIsPreservedButItsStaleButtonStateIsRewritten() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val released = InputPointerAbsolute(InputDeviceKind.Mouse, 2, 100, 200, 0, 0)
        val lateDelta = InputPointerRelative(
            InputDeviceKind.Mouse,
            2,
            7_000,
            -3_000,
            INPUT_POINTER_BUTTON_PRIMARY,
        )

        controller.process(envelope(12, 120, released), sink(delivered))
        controller.process(envelope(11, 110, lateDelta), sink(delivered))

        val rewritten = delivered.last().event as InputPointerRelative
        assertEquals(7_000, rewritten.deltaXQ16_16)
        assertEquals(-3_000, rewritten.deltaYQ16_16)
        assertEquals(0, rewritten.buttonMask)
        assertEquals(1L, controller.snapshot().stalePointerButtonRewrites)
    }

    @Test
    fun sameTransportSequenceDoesNotDoubleRelativeMotion() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val delta = InputPointerRelative(InputDeviceKind.Mouse, 2, 1_000, 0, 0)

        controller.process(envelope(30, 300, delta), sink(delivered))
        val duplicate = controller.process(envelope(30, 300, delta), sink(delivered))

        assertEquals(InputConvergenceOutcome.TransportDuplicateDropped, duplicate.outcome)
        assertEquals(1, delivered.size)
    }

    @Test
    fun resetWatermarkBlocksOlderStateAndRedundantResetAdvancesIt() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val reset = InputResetState(
            InputDeviceKind.Unknown,
            65_535,
            InputResetScope.AllDevices,
            InputResetReason.ErrorRecovery,
        )

        controller.process(envelope(100, 100, key(InputKeyAction.Down)), sink(delivered))
        controller.process(envelope(102, 200, reset), sink(delivered))
        assertEquals(
            InputConvergenceOutcome.SemanticDuplicateDropped,
            controller.process(envelope(103, 200, reset), sink(delivered)).outcome,
        )
        assertEquals(
            InputConvergenceOutcome.PreResetSequenceDropped,
            controller.process(envelope(101, 101, key(InputKeyAction.Down, usageId = 5)), sink(delivered)).outcome,
        )

        assertEquals(103L, controller.snapshot().globalResetSequence)
        assertEquals(2, delivered.size)
    }

    @Test
    fun redundantResetAdvancesWatermarkWithoutClearingNewerPostResetPointerState() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val reset = InputResetState(
            InputDeviceKind.Mouse,
            2,
            InputResetScope.ThisDevice,
            InputResetReason.ErrorRecovery,
        )

        controller.process(
            envelope(100, 1_000, InputPointerAbsolute(InputDeviceKind.Mouse, 2, 10, 10, 0, 0)),
            sink(delivered),
        )
        controller.process(envelope(102, 2_000, reset), sink(delivered))
        controller.process(
            envelope(105, 3_000, InputPointerAbsolute(InputDeviceKind.Mouse, 2, 100, 100, 0, 0)),
            sink(delivered),
        )
        assertEquals(
            InputConvergenceOutcome.SemanticDuplicateDropped,
            controller.process(envelope(103, 2_000, reset), sink(delivered)).outcome,
        )
        val stalePointer = controller.process(
            envelope(104, 2_500, InputPointerAbsolute(InputDeviceKind.Mouse, 2, 90, 90, 0, 0)),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.StaleSequenceDropped, stalePointer.outcome)
        assertEquals(3, delivered.size)
    }

    @Test
    fun aDistinctResetOlderThanAcceptedStateIsDroppedWithoutInjection() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val pointer = InputPointerAbsolute(InputDeviceKind.Mouse, 2, 100, 100, 0, 0)
        val reset = InputResetState(
            InputDeviceKind.Unknown,
            65_535,
            InputResetScope.AllDevices,
            InputResetReason.UserRequest,
        )

        controller.process(envelope(105, 3_000, pointer), sink(delivered))
        val staleReset = controller.process(envelope(103, 2_000, reset), sink(delivered))

        assertEquals(InputConvergenceOutcome.StaleSequenceDropped, staleReset.outcome)
        assertEquals(1L, controller.snapshot().staleResetDrops)
        assertEquals(1, delivered.size)
    }

    @Test
    fun touchMoveRepairsAMissingPointerUpWithStablePointerIds() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val one = contact(1, 100)
        val two = contact(2, 200)

        controller.process(touchEnvelope(1, InputTouchAction.Down, 1, listOf(one)), sink(delivered))
        controller.process(touchEnvelope(2, InputTouchAction.PointerDown, 2, listOf(one, two)), sink(delivered))
        val repaired = controller.process(
            touchEnvelope(3, InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(one)),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, repaired.outcome)
        assertEquals(2, repaired.syntheticTouchRepairEvents)
        assertEquals(
            listOf(
                InputTouchAction.Down,
                InputTouchAction.PointerDown,
                InputTouchAction.PointerUp,
                InputTouchAction.Move,
            ),
            delivered.map { (it.event as InputTouchFrame).action },
        )
        assertEquals(2L, controller.snapshot().touchRepairEvents)
    }

    @Test
    fun missingTouchStartRebuildsTheFreshestCompleteStateWithoutWaiting() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val one = contact(1, 100)
        val two = contact(2, 200)

        val result = controller.process(
            touchEnvelope(1, InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(one, two)),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, result.outcome)
        assertEquals(
            listOf(InputTouchAction.Down, InputTouchAction.PointerDown, InputTouchAction.Move),
            delivered.map { (it.event as InputTouchFrame).action },
        )
    }

    @Test
    fun missingPointerDownIsRebuiltFromTheNewestMultiTouchSnapshot() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val one = contact(1, 100)
        val two = contact(2, 200)

        controller.process(touchEnvelope(1, InputTouchAction.Down, 1, listOf(one)), sink(delivered))
        val result = controller.process(
            touchEnvelope(3, InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(one, two)),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, result.outcome)
        assertEquals(
            listOf(InputTouchAction.Down, InputTouchAction.PointerDown, InputTouchAction.Move),
            delivered.map { (it.event as InputTouchFrame).action },
        )
    }

    @Test
    fun nextDownAfterALostUpReleasesTheOldGestureBeforeReusingItsPointerId() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val one = contact(1, 100)

        controller.process(touchEnvelope(1, InputTouchAction.Down, 1, listOf(one)), sink(delivered))
        val result = controller.process(
            touchEnvelope(3, InputTouchAction.Down, 1, listOf(one.copy(xNormalized = 200))),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, result.outcome)
        assertEquals(
            listOf(
                InputTouchAction.Down,
                InputTouchAction.Up,
                InputTouchAction.Down,
                InputTouchAction.Move,
            ),
            delivered.map { (it.event as InputTouchFrame).action },
        )
    }

    @Test
    fun staleTouchFrameCannotResurrectAContactAfterANewerUp() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val one = contact(1, 100)

        controller.process(touchEnvelope(1, InputTouchAction.Down, 1, listOf(one)), sink(delivered))
        controller.process(touchEnvelope(3, InputTouchAction.Up, 1, listOf(one)), sink(delivered))
        val stale = controller.process(
            touchEnvelope(2, InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, listOf(one)),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.StaleSequenceDropped, stale.outcome)
        assertEquals(
            listOf(InputTouchAction.Down, InputTouchAction.Up),
            delivered.map { (it.event as InputTouchFrame).action },
        )
    }

    @Test
    fun fourFingerSnapshotRepairsMissingTransitionsWithBoundedPointerOrder() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val controller = controller()
        val contacts = (1..4).map { pointerId -> contact(pointerId, pointerId * 100) }

        controller.process(
            touchEnvelope(1, InputTouchAction.Down, 1, listOf(contacts.first())),
            sink(delivered),
        )
        val result = controller.process(
            touchEnvelope(5, InputTouchAction.Move, INPUT_NO_ACTION_POINTER_ID, contacts),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, result.outcome)
        assertEquals(
            listOf(
                InputTouchAction.Down,
                InputTouchAction.PointerDown,
                InputTouchAction.PointerDown,
                InputTouchAction.PointerDown,
                InputTouchAction.Move,
            ),
            delivered.map { (it.event as InputTouchFrame).action },
        )
        assertTrue(result.syntheticTouchRepairEvents <= 64)
    }

    @Test
    fun touchRepairOverflowUsesOneLocalResetInsteadOfUnboundedExpansion() {
        val delivered = mutableListOf<InputEventEnvelope>()
        val config = InputReliabilityConfig.ultraLowLatencyConvergent().copy(maxTouchRepairEvents = 1)
        val controller = InputStateConvergenceController(config)

        val result = controller.process(
            touchEnvelope(
                1,
                InputTouchAction.Move,
                INPUT_NO_ACTION_POINTER_ID,
                listOf(contact(1, 100), contact(2, 200)),
            ),
            sink(delivered),
        )

        assertEquals(InputConvergenceOutcome.ForwardedWithTouchRepair, result.outcome)
        assertTrue(delivered.single().event is InputResetState)
        assertEquals(1L, controller.snapshot().touchRepairResets)
    }

    private fun controller(): InputStateConvergenceController =
        InputStateConvergenceController(InputReliabilityConfig.ultraLowLatencyConvergent())

    private fun sink(delivered: MutableList<InputEventEnvelope>): InputConvergenceSink = InputConvergenceSink {
        delivered += it
        InputConvergenceDispatchResult(accepted = true)
    }

    private fun envelope(sequence: Long, timeUs: Long, event: WarpnectInputEvent): InputEventEnvelope =
        InputEventEnvelope(sequence, timeUs, event)

    private fun key(action: InputKeyAction, usageId: Int = 4): InputKeyEvent =
        InputKeyEvent(1, 7, usageId, action, 0, 0)

    private fun contact(pointerId: Int, x: Int): InputTouchContact =
        InputTouchContact(pointerId, InputTouchToolType.Finger, 0, x, 100)

    private fun touchEnvelope(
        sequence: Long,
        action: InputTouchAction,
        actionPointerId: Int,
        contacts: List<InputTouchContact>,
    ): InputEventEnvelope = InputEventEnvelope(
        sequenceNumber = sequence,
        sourceEventTimeUs = sequence * 1_000L,
        event = InputTouchFrame(
            InputDeviceKind.Touchscreen,
            1,
            action,
            actionPointerId,
            contacts,
        ),
    )
}
