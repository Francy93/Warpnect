package io.warpnect.debug.directshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSetupPermissionActionTest {
    @Test
    fun `explicit first entry requests missing permission`() {
        val gate = PairingSetupPermissionGate()
        assertEquals(PairingSetupPermissionAction.REQUEST_PERMISSIONS, gate.action(missing = true))
        assertTrue(gate.requestIssued)
    }

    @Test
    fun `permission denial never automatically requests again`() {
        val gate = PairingSetupPermissionGate()
        gate.action(missing = true)
        repeat(2) { assertEquals(PairingSetupPermissionAction.DENIED, gate.action(missing = true)) }
    }

    @Test
    fun `rotation after request does not prompt again`() {
        val previous = PairingSetupPermissionGate()
        previous.action(missing = true)
        val restored = PairingSetupPermissionGate(previous.requestIssued)
        assertEquals(PairingSetupPermissionAction.DENIED, restored.action(missing = true))
    }

    @Test
    fun `granted permissions allow existing notification`() {
        val gate = PairingSetupPermissionGate()
        gate.action(missing = true)
        assertEquals(PairingSetupPermissionAction.SHOW_NOTIFICATION, gate.action(missing = false))
    }

    @Test
    fun `explicit retry may request permission again`() {
        val gate = PairingSetupPermissionGate(requestIssued = true)
        assertEquals(PairingSetupPermissionAction.DENIED, gate.action(missing = true))
        assertEquals(
            PairingSetupPermissionAction.REQUEST_PERMISSIONS,
            gate.action(missing = true, explicitRetry = true),
        )
    }
}
