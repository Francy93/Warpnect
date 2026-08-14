package io.warpnect

import io.warpnect.session.DeviceId
import io.warpnect.session.SessionManager
import io.warpnect.session.SessionManagerConfig
import io.warpnect.session.discovery.DefaultLocalDiscoveryController
import io.warpnect.session.discovery.DiscoveryConfig
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseFactory
import io.warpnect.session.discovery.DiscoveryContactEndpointLeaseResult
import io.warpnect.session.discovery.DiscoveryControllerState
import io.warpnect.session.discovery.DiscoveryMonotonicClock
import org.junit.Assert.assertEquals
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

    @Test
    fun shutdownClosesOptionalDiscoveryOwnershipWithoutStartingDiscovery() {
        val discovery = DefaultLocalDiscoveryController(
            config = DiscoveryConfig(),
            backends = emptyList(),
            contactEndpointLeaseFactory = DiscoveryContactEndpointLeaseFactory {
                DiscoveryContactEndpointLeaseResult()
            },
            clock = DiscoveryMonotonicClock { 0L },
        )
        val orchestrator = CoreOrchestrator(localDiscoveryController = discovery)

        orchestrator.shutdown()

        assertEquals(DiscoveryControllerState.Closed, discovery.snapshot().state)
    }
}
