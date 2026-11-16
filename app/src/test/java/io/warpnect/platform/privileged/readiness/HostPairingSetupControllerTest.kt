package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPairingSetupControllerTest {
    @Test
    fun `only pairing reason offers setup`() {
        PrivilegedRuntimeFailureReason.entries.forEach { reason ->
            assertEquals(
                if (reason == PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED) 1L else null,
                hostPairingSetupGeneration(pairingRequiredUiState(reason = reason)),
            )
        }
    }

    @Test
    fun `passive observation has no action or launch`() {
        val state = pairingRequiredUiState().copy(hostActionVisible = false)
        val controller = HostPairingSetupController { state }
        assertNull(controller.attempt.value)
        assertNull(hostPairingSetupGeneration(state))
        assertFalse(controller.request(1, true) { error("passive launch") })
    }

    @Test
    fun `inconsistent snapshot generation is unavailable`() {
        val state = pairingRequiredUiState()
        val readiness = state.readiness as HostReadinessState.AuthorizationRequired
        assertNull(hostPairingSetupGeneration(state.copy(readiness = readiness.copy(generation = 2))))
    }

    @Test
    fun `other provider authorization cannot authorize DirectShell setup`() {
        val state = pairingRequiredUiState()
        val readiness = state.readiness as HostReadinessState.AuthorizationRequired
        val runtime = readiness.runtimeState
        assertNull(
            hostPairingSetupGeneration(
                state.copy(
                    readiness = readiness.copy(
                        runtimeState = runtime.copy(
                            directShell = runtime.directShell.copy(state = PrivilegedRuntimeAvailabilityState.READY),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `checking failed unsupported and stale never offer pairing`() {
        val state = pairingRequiredUiState()
        val runtime = (state.readiness as HostReadinessState.AuthorizationRequired).runtimeState
        val reason = PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
        listOf(
            HostReadinessState.NotStarted,
            HostReadinessState.Checking(1, HostReadinessPhase.CHECKING_PRIVILEGED_RUNTIME),
            HostReadinessState.Failed(1, runtime, reason),
            HostReadinessState.Unsupported(1, runtime, reason),
            HostReadinessState.Stale(1, runtime, reason),
        ).forEach { assertNull(hostPairingSetupGeneration(state.copy(readiness = it))) }
    }

    @Test
    fun `explicit user-action pairing is supported`() {
        val state = pairingRequiredUiState()
        val readiness = state.readiness as HostReadinessState.AuthorizationRequired
        assertEquals(
            1L,
            hostPairingSetupGeneration(
                state.copy(
                    readiness = HostReadinessState.UserActionRequired(1, readiness.runtimeState, readiness.reason),
                ),
            ),
        )
    }

    @Test
    fun `unavailable adapter never invokes launch`() {
        val controller = HostPairingSetupController { pairingRequiredUiState() }
        assertFalse(controller.request(1, false) { error("no adapter") })
        assertNull(controller.attempt.value)
    }

    @Test
    fun `explicit launch happens once including double tap`() {
        val controller = HostPairingSetupController { pairingRequiredUiState() }
        var launches = 0
        repeat(2) {
            controller.request(1, true) {
                launches++
                HostPairingSetupLaunchResult.STARTED
            }
        }
        assertEquals(1, launches)
        assertEquals(HostPairingSetupAttempt(1, HostPairingSetupPhase.OPEN), controller.attempt.value)
    }

    @Test
    fun `rotation reuses owner and cannot launch twice`() {
        val owner = HostPairingSetupController { pairingRequiredUiState() }
        var launches = 0
        var token = ""
        fun recreatedUiTap() = owner.request(1, true) {
            launches++
            token = it
            HostPairingSetupLaunchResult.STARTED
        }
        recreatedUiTap()
        recreatedUiTap()
        assertEquals(1, launches)
        assertTrue(owner.returned(token))
        assertFalse(recreatedUiTap())
    }

    @Test
    fun `cancellation leaves readiness unchanged and does not retry`() {
        val state = pairingRequiredUiState()
        val controller = HostPairingSetupController { state }
        val token = startSetup(controller)
        assertTrue(controller.returned(token))
        assertEquals(HostPairingSetupPhase.RETURNED, controller.attempt.value?.phase)
        assertEquals(1L, hostPairingSetupGeneration(state))
        assertFalse(controller.request(1, true) { error("automatic retry") })
    }

    @Test
    fun `missing Activity finishes unavailable without retry`() {
        val controller = HostPairingSetupController { pairingRequiredUiState() }
        assertTrue(controller.request(1, true) { HostPairingSetupLaunchResult.UNAVAILABLE })
        assertEquals(HostPairingSetupPhase.UNAVAILABLE, controller.attempt.value?.phase)
        assertFalse(controller.returned("missing_activity"))
        assertFalse(controller.request(1, true) { error("automatic retry") })
    }

    @Test
    fun `denied launch finishes safely without retry`() {
        val controller = HostPairingSetupController { pairingRequiredUiState() }
        controller.request(1, true) { HostPairingSetupLaunchResult.PERMISSION_DENIED }
        assertEquals(HostPairingSetupPhase.PERMISSION_DENIED, controller.attempt.value?.phase)
        assertFalse(controller.request(1, true) { error("automatic retry") })
    }

    @Test
    fun `stale tap cannot use a newer readiness result`() {
        var state = pairingRequiredUiState()
        val controller = HostPairingSetupController { state }
        state = pairingRequiredUiState(2)
        assertFalse(controller.request(1, true) { error("stale tap") })
        assertTrue(controller.request(2, true) { HostPairingSetupLaunchResult.STARTED })
    }

    @Test
    fun `hidden dialog invalidates a captured tap`() {
        var state = pairingRequiredUiState()
        val controller = HostPairingSetupController { state }
        state = state.copy(hostActionVisible = false)
        assertFalse(controller.request(1, true) { error("dismissed tap") })
    }

    @Test
    fun `stale return clears only its own flight without publishing readiness`() {
        var state = pairingRequiredUiState()
        val controller = HostPairingSetupController { state }
        val token = startSetup(controller)
        state = pairingRequiredUiState(2)
        assertFalse(controller.request(2, true) { error("overlapping Activity results") })
        assertFalse(controller.returned(token))
        assertNull(controller.attempt.value)
        assertEquals(2L, hostPairingSetupGeneration(state))
        assertTrue(controller.request(2, true) { HostPairingSetupLaunchResult.STARTED })
        assertFalse(controller.returned(token))
        assertEquals(HostPairingSetupAttempt(2, HostPairingSetupPhase.OPEN), controller.attempt.value)
    }

    @Test
    fun `fresh explicit Host request may launch after cancellation`() {
        var state = pairingRequiredUiState()
        val controller = HostPairingSetupController { state }
        val token = startSetup(controller)
        controller.returned(token)
        state = pairingRequiredUiState(2)
        assertTrue(controller.request(2, true) { HostPairingSetupLaunchResult.STARTED })
    }

    @Test
    fun `closed owner ignores completion and further taps`() {
        val controller = HostPairingSetupController { pairingRequiredUiState() }
        val token = startSetup(controller)
        controller.close()
        assertNull(controller.attempt.value)
        assertFalse(controller.returned(token))
        assertFalse(controller.request(1, true) { error("closed launch") })
    }

    @Test
    fun `previous process token cannot complete reused readiness generation`() {
        val previousOwner = HostPairingSetupController { pairingRequiredUiState() }
        val previousToken = startSetup(previousOwner)
        previousOwner.close()
        val newOwner = HostPairingSetupController { pairingRequiredUiState() }
        val newToken = startSetup(newOwner)
        assertFalse(newOwner.returned(previousToken))
        assertEquals(HostPairingSetupPhase.OPEN, newOwner.attempt.value?.phase)
        assertTrue(newOwner.returned(newToken))
    }

    private fun startSetup(controller: HostPairingSetupController): String {
        var token = ""
        assertTrue(
            controller.request(1, true) {
                token = it
                HostPairingSetupLaunchResult.STARTED
            },
        )
        return token
    }
}

internal fun pairingRequiredUiState(
    generation: Long = 1,
    reason: PrivilegedRuntimeFailureReason = PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED,
): HostReadinessUiState {
    val runtime = PrivilegedRuntimeStateSnapshot(
        generation,
        observedAtMonotonicMs = 1,
        directRoot = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.DIRECT_ROOT,
            PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
        ),
        directShell = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.DIRECT_SHELL,
            PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
            reason,
        ),
        shizuku = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.SHIZUKU,
            PrivilegedRuntimeAvailabilityState.UNKNOWN,
        ),
    )
    return HostReadinessUiState(
        HostReadinessState.AuthorizationRequired(generation, runtime, reason),
        hostActionVisible = true,
    )
}
