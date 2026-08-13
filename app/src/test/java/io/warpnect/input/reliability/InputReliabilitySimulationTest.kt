package io.warpnect.input.reliability

import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.WarpnectInputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputReliabilitySimulationTest {
    @Test
    fun tenThousandCriticalTransitionScenariosConvergeWithoutDuplicateInjection() {
        val controller = InputStateConvergenceController(InputReliabilityConfig.ultraLowLatencyConvergent())
        val delivered = mutableListOf<WarpnectInputEvent>()
        val sink = InputConvergenceSink {
            delivered += it.event
            InputConvergenceDispatchResult(accepted = true)
        }
        var sequence = 0L

        repeat(10_000) { iteration ->
            val key = InputKeyEvent(
                deviceSlot = iteration % 32,
                usagePage = 7,
                usageId = 4 + (iteration % 26),
                action = InputKeyAction.Down,
                repeatCount = 0,
                modifierMask = 0,
            )
            val eventTimeUs = iteration.toLong() * 10L
            controller.process(InputEventEnvelope(sequence++, eventTimeUs, key), sink)
            controller.process(InputEventEnvelope(sequence++, eventTimeUs, key), sink)
            val up = key.copy(action = InputKeyAction.Up)
            controller.process(InputEventEnvelope(sequence++, eventTimeUs + 1L, up), sink)
            controller.process(InputEventEnvelope(sequence++, eventTimeUs + 1L, up), sink)
        }

        assertEquals(20_000, delivered.size)
        assertEquals(20_000L, controller.snapshot().semanticDuplicateDrops)
        assertEquals(0L, controller.snapshot().staleSequenceDrops)
        assertTrue(controller.snapshot().trackedSlots <= 32)
        assertTrue(controller.snapshot().trackedKeys <= 32 * 64)
    }
}
