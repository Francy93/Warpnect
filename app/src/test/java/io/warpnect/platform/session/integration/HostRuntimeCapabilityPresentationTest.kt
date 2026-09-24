package io.warpnect.platform.session.integration

import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRuntimeCapabilityPresentationTest {
    @Test
    fun `unsupported DirectShell platform has an explicit Host capability message`() {
        assertEquals(
            "Host unavailable: DirectShell requires Android 11 or later",
            hostRuntimeFailureMessage(PrivilegedRuntimeFailure.DIRECTSHELL_UNSUPPORTED_PLATFORM),
        )
    }

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

    @Test
    fun `Host capability is unavailable when SystemAudio is not qualified`() {
        val resources = AndroidSessionUiResources()

        resources.publishRoleCapability(
            role = io.warpnect.session.SessionRole.Host,
            videoAvailable = true,
            systemAudioAvailable = false,
            inputAvailable = true,
        )

        assertEquals(
            DeviceRoleCapability.Unavailable("Host SystemAudio unavailable"),
            resources.hostCapability.value,
        )
    }

    @Test
    fun `Host becomes available only when its complete plan is admitted`() {
        val resources = AndroidSessionUiResources()

        resources.markRoleCapabilityChecking(io.warpnect.session.SessionRole.Host)
        resources.publishHostPlanAdmitted()

        assertEquals(DeviceRoleCapability.Available, resources.hostCapability.value)
    }
}
