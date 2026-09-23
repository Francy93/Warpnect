package io.warpnect.platform.session.integration

import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRuntimeCapabilityPresentationTest {
    @Test
    fun `Wireless Debugging disabled has a recoverable Host capability message`() {
        assertEquals(
            "Host setup required: enable Wireless debugging",
            hostRuntimeFailureMessage(PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED),
        )
    }

    @Test
    fun `pairing failure asks for pairing rather than leaving capability checking`() {
        assertEquals(
            "Host setup required: pair Wireless debugging",
            hostRuntimeFailureMessage(PrivilegedRuntimeFailure.DIRECTSHELL_NOT_PAIRED),
        )
    }

    @Test
    fun `Host capability is cleared when its privileged runtime is released`() {
        val resources = AndroidSessionUiResources()

        resources.publishRoleCapability(
            role = io.warpnect.session.SessionRole.Host,
            videoAvailable = true,
            inputAvailable = true,
        )
        resources.clearHostCapability()

        assertEquals(DeviceRoleCapability.NotChecked, resources.hostCapability.value)
    }
}
