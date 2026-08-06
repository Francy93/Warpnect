package io.warpnect

import org.junit.Assert.assertSame
import org.junit.Test

class CoreOrchestratorTest {
    @Test
    fun defaultRoleIsReceiver() {
        val orchestrator = CoreOrchestrator()

        assertSame(WarpnectRole.Receiver, orchestrator.role.value)
    }

    @Test
    fun roleTransitionsAreExplicit() {
        val orchestrator = CoreOrchestrator()

        orchestrator.enterTransmitterMode()
        assertSame(WarpnectRole.Transmitter, orchestrator.role.value)

        orchestrator.enterIdle()
        assertSame(WarpnectRole.Idle, orchestrator.role.value)

        orchestrator.enterReceiverMode()
        assertSame(WarpnectRole.Receiver, orchestrator.role.value)
    }
}
