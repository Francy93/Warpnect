package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanner
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootSnapshot
import io.warpnect.platform.privileged.runtime.DirectRootState
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootHostAdmissionIntegrationTest {
    @Test fun `temporary helper outage keeps complete compatibility evidence distinct from liveness`() {
        assertTrue(RootHostEvidence.hasCompleteEvidence(evidence(), root().provenance))
        assertFalse(root().copy(state = DirectRootState.HELPER_LOST).ready)
        assertFalse(RootHostEvidence.hasCompleteEvidence(evidence(), RootBootstrapProvenance.PRODUCTION_ROOT_MANAGER))
        assertFalse(RootHostEvidence.hasCompleteEvidence(evidence().dropLast(1), root().provenance))
    }

    @Test fun `cached Root cells retain evidence keys but bind active generation`() {
        val records = evidence()
        val restored = RootHostEvidence.restore(root(), records)
        assertEquals(listOf(17L, 17L, 17L), restored.map { it.provenance.runtimeGeneration })
        assertEquals(
            records.map {
                it.compatibilityKey.stableId
            }.sorted(),
            restored.mapNotNull { it.provenance.compatibilityKey }.sorted(),
        )
        assertTrue(restored.all { it.provenance.source == CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE })
    }

    @Test(expected = IllegalStateException::class)
    fun `missing Root Audio cannot restore complete Host`() {
        RootHostEvidence.restore(root(), evidence().filter { it.capability != PrivilegedCapability.SYSTEM_AUDIO })
    }

    @Test(expected = IllegalStateException::class)
    fun `lost helper cannot reuse compatibility as liveness`() {
        RootHostEvidence.restore(root().copy(state = DirectRootState.HELPER_LOST), evidence())
    }

    @Test(expected = IllegalStateException::class)
    fun `laboratory evidence cannot authorize production root manager`() {
        RootHostEvidence.restore(root().copy(provenance = RootBootstrapProvenance.PRODUCTION_ROOT_MANAGER), evidence())
    }

    @Test(expected = IllegalStateException::class)
    fun `UID2000 cannot acquire Root Host from cached cells`() {
        RootHostEvidence.restore(root().copy(identity = root().identity!!.copy(uid = 2000)), evidence())
    }

    @Test fun `freeze alone never invokes publication`() {
        val gate = HostAdmissionPublicationGate()
        prepared()
        assertFalse(gate.rootOwnershipLost(root().copy(state = DirectRootState.HELPER_LOST)))
    }

    @Test fun `normal Host success publishes once after frozen admission`() {
        val gate = HostAdmissionPublicationGate()
        var starts = 0
        assertTrue(
            gate.publish(prepared()) {
                starts++
                true
            },
        )
        assertFalse(
            gate.publish(prepared()) {
                starts++
                true
            },
        )
        assertEquals(1, starts)
        assertFalse(gate.rootOwnershipLost(root()))
    }

    @Test fun `normal Host start failure cannot publish frozen plan`() {
        val gate = HostAdmissionPublicationGate()
        assertFalse(gate.publish(prepared()) { false })
        assertFalse(gate.rootOwnershipLost(root().copy(state = DirectRootState.HELPER_LOST)))
    }

    @Test(expected = IllegalStateException::class)
    fun `advisory plan cannot call normal Host start`() {
        HostAdmissionPublicationGate().publish(prepared().copy(commitState = HostReadinessPlanCommitState.ADVISORY)) {
            error("must not publish")
        }
    }

    @Test fun `helper loss after freeze requires withdrawal not provider migration`() {
        val gate = HostAdmissionPublicationGate()
        gate.publish(prepared()) { true }
        assertTrue(gate.rootOwnershipLost(root().copy(state = DirectRootState.HELPER_LOST)))
    }

    @Test fun `new Root generation cannot satisfy frozen Host`() {
        val gate = HostAdmissionPublicationGate()
        gate.publish(prepared()) { true }
        assertTrue(gate.rootOwnershipLost(root().copy(generation = 18)))
    }

    @Test fun `normal disable releases publication generation and next Host may start`() {
        val gate = HostAdmissionPublicationGate()
        gate.publish(prepared()) { true }
        gate.clear()
        assertFalse(gate.rootOwnershipLost(root().copy(state = DirectRootState.RELEASED)))
        assertTrue(gate.publish(prepared()) { true })
    }

    @Test fun `app restart restores evidence but never restores publication`() {
        HostAdmissionPublicationGate().publish(prepared()) { true }
        val restarted = HostAdmissionPublicationGate()
        assertFalse(restarted.rootOwnershipLost(root().copy(state = DirectRootState.HELPER_LOST)))
        assertEquals(3, evidence().size)
    }

    @Test fun `Root prepared background plan is not blocked by unsupported DirectShell`() {
        val fixture = ReadinessFixture()
        fixture.coordinator.startBackgroundReadiness()
        fixture.runAll()
        assertTrue(fixture.coordinator.state.value is HostReadinessState.Ready)
        assertEquals(0, fixture.acquisitions)
        assertEquals(0, fixture.publications)
    }

    @Test fun `normal Host request joins Root background single flight and publishes after acquisition`() {
        val fixture = ReadinessFixture()
        fixture.coordinator.startBackgroundReadiness()
        fixture.enable()
        fixture.enable()
        fixture.runAll()
        assertEquals(1, fixture.acquisitions)
        assertEquals(1, fixture.publications)
    }

    @Test fun `late Root loss fails normal Host without publication or fallback`() {
        val fixture = ReadinessFixture()
        fixture.lateAlive = false
        fixture.enable()
        fixture.runAll()
        assertTrue(fixture.coordinator.state.value is HostReadinessState.Failed)
        assertEquals(0, fixture.publications)
    }

    @Test fun `normal disable clears frozen resource owner`() {
        val fixture = ReadinessFixture()
        fixture.enable()
        fixture.runAll()
        fixture.coordinator.invalidate()
        assertEquals(1, fixture.releases)
        assertTrue(fixture.coordinator.state.value is HostReadinessState.Stale)
    }

    private inner class ReadinessFixture {
        private val queue = ArrayDeque<() -> Unit>()
        var acquisitions = 0
        var publications = 0
        var releases = 0
        var lateAlive = true
        val coordinator = BackgroundHostReadinessCoordinator(
            HostReadinessDispatcher {
                queue.add(it)
                true
            },
            object : HostReadinessWork {
                override fun observeVolatileRuntime(generation: Long) =
                    prepared().runtimeState.copy(generation = generation)
                override fun observeBackgroundReadiness(generation: Long) = BackgroundHostReadinessObservation(
                    observeVolatileRuntime(generation),
                    prepared().copy(
                        generation = generation,
                        runtimeState = observeVolatileRuntime(generation),
                        commitState = HostReadinessPlanCommitState.ADVISORY,
                    ),
                    HostReadinessEvidenceSource.EXISTING_COMPATIBILITY_CACHE,
                )
                override fun prepareForHost(
                    generation: Long,
                    runtimeState: PrivilegedRuntimeStateSnapshot,
                ): HostReadinessState {
                    acquisitions++
                    return if (lateAlive) {
                        HostReadinessState.Ready(prepared().copy(generation = generation, runtimeState = runtimeState))
                    } else {
                        HostReadinessState.Failed(
                            generation,
                            runtimeState,
                            PrivilegedRuntimeFailureReason.ROOT_HELPER_UNAVAILABLE,
                        )
                    }
                }
                override fun releasePreparedResources() {
                    releases++
                }
            },
        )
        fun enable() = coordinator.requestHostPreparation { publications++ }
        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    private fun root() = DirectRootSnapshot(
        DirectRootState.HELPER_READY,
        17,
        RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT,
        RootHelperIdentity(
            50, 0, 0, "u:r:su:s0", 26, 17, DirectRootRuntime.PROTOCOL_VERSION,
            DirectRootRuntime.IMPLEMENTATION, "a".repeat(64),
        ),
    )

    private fun evidence() = PrivilegedCapability.canonicalOrder.map { capability ->
        PersistentCapabilityEvidenceRecord(
            provider = PrivilegedProvider.DIRECT_ROOT,
            capability = capability,
            state = CapabilityQualificationState.QUALIFIED,
            originalSource = CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
            compatibilityKey = PersistentCapabilityEvidenceKey(
                PrivilegedProvider.DIRECT_ROOT,
                capability,
                26,
                "a".repeat(64),
                1,
                DirectRootRuntime.IMPLEMENTATION,
                RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT.name,
                "backend-v1",
            ),
            recordedAtEpochMs = 1,
        )
    }

    private fun prepared(): PreparedPrivilegedCapabilityPlan {
        val matrix = CachedCapabilityEvidenceMatrixBuilder.build(
            evidence(),
        ).withQualifications(RootHostEvidence.restore(root(), evidence()))
        val plan = (PrivilegedCapabilityPlanner.select(matrix) as PrivilegedCapabilityPlanResult.Complete).plan
        val runtimeState = PrivilegedRuntimeStateSnapshot(
            1,
            1,
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.READY,
                rootFoundation = root(),
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.UNSUPPORTED,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.SHIZUKU,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
        )
        return PreparedPrivilegedCapabilityPlan(
            1,
            runtimeState,
            PrivilegedRuntimeSnapshot(PrivilegedRuntimeKind.DIRECT_ROOT, PrivilegedRuntimeState.READY, generation = 17),
            matrix,
            plan,
            HostReadinessEvidenceSource.CURRENT_HOST_ADMISSION,
            commitState = HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
            preparedAtMonotonicMs = 1,
        )
    }
}
