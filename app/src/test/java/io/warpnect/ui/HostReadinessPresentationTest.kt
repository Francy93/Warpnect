package io.warpnect.ui

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanner
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.readiness.HostReadinessEvidenceSource
import io.warpnect.platform.privileged.readiness.HostReadinessPhase
import io.warpnect.platform.privileged.readiness.HostReadinessPlanCommitState
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PreparedPrivilegedCapabilityPlan
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostReadinessPresentationTest {
    @Test
    fun `checking shows truthful non-dismissible preparation copy`() {
        val presentation = requireNotNull(
            hostReadinessPresentation(
                HostReadinessUiState(
                    HostReadinessState.Checking(1L, HostReadinessPhase.CHECKING_PRIVILEGED_RUNTIME),
                    hostActionVisible = true,
                ),
            ),
        )

        assertEquals("Preparing Host", presentation.title)
        assertEquals("Checking privileged runtime...", presentation.detail)
        assertFalse(presentation.dismissible)
    }

    @Test
    fun `ready gives a short automatic success transition`() {
        val presentation = requireNotNull(hostReadinessPresentation(readyUiState()))

        assertEquals("Host ready", presentation.title)
        assertTrue(presentation.autoDismiss)
        assertFalse(presentation.dismissible)
    }

    @Test
    fun `background cached advisory completion never opens Host dialog`() {
        val ready = readyUiState()
        val prepared = (ready.readiness as HostReadinessState.Ready).prepared.copy(
            evidenceSource = HostReadinessEvidenceSource.EXISTING_COMPATIBILITY_CACHE,
            commitState = HostReadinessPlanCommitState.ADVISORY,
        )

        assertNull(hostReadinessPresentation(HostReadinessUiState(HostReadinessState.Ready(prepared), false)))
    }

    @Test
    fun `user action required remains explicit and dismissible`() {
        val presentation = requireNotNull(
            hostReadinessPresentation(
                HostReadinessUiState(
                    HostReadinessState.UserActionRequired(
                        generation = 1L,
                        runtimeState = runtimeState(),
                        reason = PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
                    ),
                    hostActionVisible = true,
                ),
            ),
        )

        assertEquals("Host setup required", presentation.title)
        assertEquals("Host setup required: enable Wireless debugging", presentation.detail)
        assertTrue(presentation.dismissible)
    }

    @Test
    fun `unsupported remains terminal until explicit dismissal and hidden state has no dialog`() {
        val visible = requireNotNull(
            hostReadinessPresentation(
                HostReadinessUiState(
                    HostReadinessState.Unsupported(
                        generation = 1L,
                        runtimeState = runtimeState(),
                        reason = PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
                    ),
                    hostActionVisible = true,
                ),
            ),
        )

        assertEquals("Host mode unavailable", visible.title)
        assertTrue(visible.dismissible)
        assertNull(
            hostReadinessPresentation(
                HostReadinessUiState(
                    HostReadinessState.Unsupported(
                        generation = 1L,
                        runtimeState = runtimeState(),
                        reason = PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
                    ),
                    hostActionVisible = false,
                ),
            ),
        )
    }

    private companion object {
        fun readyUiState(): HostReadinessUiState {
            val runtimeState = runtimeState()
            val matrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
                val state = if (provider == PrivilegedProvider.DIRECT_SHELL) {
                    CapabilityQualificationState.VALIDATED
                } else if (provider == PrivilegedProvider.DIRECT_ROOT) {
                    CapabilityQualificationState.NOT_IMPLEMENTED
                } else {
                    CapabilityQualificationState.DEFERRED
                }
                val provenance = CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE)
                if (state.isUsable) {
                    CapabilityQualification.usable(provider, capability, state, "test_usable", provenance)
                } else {
                    CapabilityQualification.unavailable(provider, capability, state, "test_unavailable", provenance)
                }
            }
            val plan = (PrivilegedCapabilityPlanner.select(matrix) as PrivilegedCapabilityPlanResult.Complete).plan
            return HostReadinessUiState(
                readiness = HostReadinessState.Ready(
                    PreparedPrivilegedCapabilityPlan(
                        generation = 1L,
                        runtimeState = runtimeState,
                        runtimeSnapshot = PrivilegedRuntimeSnapshot(
                            kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                            state = PrivilegedRuntimeState.READY,
                        ),
                        matrix = matrix,
                        plan = plan,
                        evidenceSource = HostReadinessEvidenceSource.CURRENT_HOST_ADMISSION,
                        commitState = HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
                        preparedAtMonotonicMs = 1L,
                    ),
                ),
                hostActionVisible = true,
            )
        }

        fun runtimeState() = PrivilegedRuntimeStateSnapshot(
            generation = 1L,
            observedAtMonotonicMs = 1L,
            directRoot = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
                PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
                "root_not_implemented",
            ),
            directShell = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.UNKNOWN,
                detail = "shell_unknown",
            ),
            shizuku = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.SHIZUKU,
                PrivilegedRuntimeAvailabilityState.UNKNOWN,
                detail = "shizuku_unknown",
            ),
        )
    }
}
