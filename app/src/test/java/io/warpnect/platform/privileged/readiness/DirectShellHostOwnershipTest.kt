package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlan
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeFailure
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellHostOwnershipTest {
    @Test
    fun `frozen plan alone does not publish Host`() {
        val gate = HostAdmissionPublicationGate()
        prepared()

        assertFalse(gate.directShellOwnershipLost(runtime().copy(state = PrivilegedRuntimeState.UNAVAILABLE)))
    }

    @Test
    fun `published DirectShell Host withdraws on owner loss or generation change`() {
        val gate = HostAdmissionPublicationGate()
        assertTrue(gate.publish(prepared()) { true })

        assertFalse(gate.directShellOwnershipLost(runtime()))
        assertTrue(gate.directShellOwnershipLost(runtime().copy(state = PrivilegedRuntimeState.UNAVAILABLE)))
        assertTrue(gate.directShellOwnershipLost(runtime().copy(generation = 12L)))
        assertTrue(
            gate.directShellOwnershipLost(
                runtime().copy(failure = PrivilegedRuntimeFailure.DIRECTSHELL_OWNER_LOST),
            ),
        )

        gate.clear()
        assertFalse(gate.directShellOwnershipLost(runtime().copy(state = PrivilegedRuntimeState.UNAVAILABLE)))
    }

    private fun runtime() = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_SHELL,
        state = PrivilegedRuntimeState.READY,
        generation = 11L,
    )

    private fun prepared(): PreparedPrivilegedCapabilityPlan {
        val qualifications = PrivilegedCapability.entries.map { capability ->
            CapabilityQualification.usable(
                PrivilegedProvider.DIRECT_SHELL,
                capability,
                CapabilityQualificationState.QUALIFIED,
                "direct_shell_test",
                CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE, 11L),
            )
        }
        val matrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
            qualifications.firstOrNull { it.provider == provider && it.capability == capability }
                ?: CapabilityQualification.unavailable(
                    provider,
                    capability,
                    CapabilityQualificationState.NOT_AVAILABLE,
                    "not_selected",
                    CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE),
                )
        }
        val plan = PrivilegedCapabilityPlan(
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            PrivilegedProvider.DIRECT_SHELL,
            qualifications,
        )
        val state = PrivilegedRuntimeStateSnapshot(
            1L,
            1L,
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
            PrivilegedRuntimeAvailability(PrivilegedRuntimeKind.DIRECT_SHELL, PrivilegedRuntimeAvailabilityState.READY),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.SHIZUKU,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
        )
        return PreparedPrivilegedCapabilityPlan(
            1L,
            state,
            runtime(),
            matrix,
            plan,
            HostReadinessEvidenceSource.CURRENT_HOST_ADMISSION,
            commitState = HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
            preparedAtMonotonicMs = 1L,
        )
    }
}
