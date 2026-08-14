package io.warpnect

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun shutdownClosesAnOptionalSessionManagerWithoutCreatingSessionRuntime() {
        val sessionManager = SessionManager(
            SessionManagerConfig(
                localDeviceId = DeviceId.requireValid(0uL, 1uL),
            ),
        )
        val orchestrator = CoreOrchestrator(sessionManager = sessionManager)

        orchestrator.shutdown()

        assertTrue(sessionManager.snapshot().closed)
    }
}
