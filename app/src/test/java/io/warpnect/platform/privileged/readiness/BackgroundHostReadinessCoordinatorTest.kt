package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanner
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundHostReadinessCoordinatorTest {
    @Test
    fun `app start schedules readiness without executing it on the caller`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork()
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)

        assertTrue(coordinator.startBackgroundReadiness())
        assertEquals(0, work.observeCalls)
        assertEquals(1, dispatcher.pendingCount)
        assertState<HostReadinessState.Checking>(coordinator.state.value)

        dispatcher.runAll()

        assertEquals(1, work.observeCalls)
        assertState<HostReadinessState.Stale>(coordinator.state.value)
    }

    @Test
    fun `enable Host joins background observation and starts one preparation`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork()
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)
        var firstPlan: PreparedPrivilegedCapabilityPlan? = null
        var secondPlan: PreparedPrivilegedCapabilityPlan? = null

        assertTrue(coordinator.startBackgroundReadiness())
        assertTrue(coordinator.requestHostPreparation { firstPlan = it })
        assertTrue(coordinator.requestHostPreparation { secondPlan = it })
        assertEquals(1, dispatcher.pendingCount)

        dispatcher.runAll()

        assertEquals(1, work.observeCalls)
        assertEquals(1, work.prepareCalls)
        assertTrue(firstPlan != null)
        assertNull(secondPlan)
        assertState<HostReadinessState.Ready>(coordinator.state.value)
    }

    @Test
    fun `stale generation cannot overwrite a newer observation`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork()
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)

        assertTrue(coordinator.startBackgroundReadiness())
        coordinator.invalidate()
        assertTrue(coordinator.startBackgroundReadiness())

        dispatcher.runNext()
        assertEquals(0, work.observeCalls)
        dispatcher.runNext()

        val state = assertState<HostReadinessState.Stale>(coordinator.state.value)
        assertEquals(3L, state.generation)
        assertEquals(1, work.observeCalls)
    }

    @Test
    fun `wireless debugging disabled remains user action required instead of failed qualification`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork(
            runtimeState = runtimeState(
                directShell = availability(
                    PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE,
                    PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
                ),
            ),
        )
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)

        coordinator.startBackgroundReadiness()
        dispatcher.runAll()

        val state = assertState<HostReadinessState.UserActionRequired>(coordinator.state.value)
        assertEquals(PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED, state.reason)
        assertEquals(0, work.prepareCalls)
    }

    @Test
    fun `legacy API DirectShell is represented as unsupported without root probing`() {
        val environment = DirectShellRuntimeEnvironment(
            apiLevel = 26,
            wirelessDebuggingExplicitlyDisabled = null,
        )
        val snapshot = PrivilegedRuntimeStateMapper.snapshot(
            generation = 1L,
            observedAtMonotonicMs = 1L,
            directShellEnvironment = environment,
            runtime = runtimeSnapshot(),
            shizukuAccess = io.warpnect.shizuku.PrivilegedAccessState.Unavailable,
        )

        assertEquals(PrivilegedRuntimeAvailabilityState.UNSUPPORTED, snapshot.directShell.state)
        assertEquals(PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED, snapshot.directRoot.state)
    }

    @Test
    fun `healthy DirectShell plan stays single provider and does not request Shizuku`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork(
            preparedProviders = mapOf(
                PrivilegedCapability.VIDEO to PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO to PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.INPUT to PrivilegedProvider.DIRECT_SHELL,
            ),
        )
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)
        var prepared: PreparedPrivilegedCapabilityPlan? = null

        coordinator.requestHostPreparation { prepared = it }
        dispatcher.runAll()

        val plan = requireNotNull(prepared).plan
        assertEquals(io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanShape.SINGLE, plan.shape)
        assertEquals(listOf(PrivilegedProvider.DIRECT_SHELL), plan.participatingProviders)
        assertEquals(0, work.shizukuPreparationCalls)
    }

    @Test
    fun `DirectShell incomplete path retains staged Shizuku composite result`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork(
            preparedProviders = mapOf(
                PrivilegedCapability.VIDEO to PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.SYSTEM_AUDIO to PrivilegedProvider.DIRECT_SHELL,
                PrivilegedCapability.INPUT to PrivilegedProvider.SHIZUKU,
            ),
            shizukuPreparationCalls = 1,
        )
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)
        var prepared: PreparedPrivilegedCapabilityPlan? = null

        coordinator.requestHostPreparation { prepared = it }
        dispatcher.runAll()

        assertEquals(PrivilegedProvider.SHIZUKU, requireNotNull(prepared).plan.inputProvider)
        assertEquals(1, work.shizukuPreparationCalls)
    }

    @Test
    fun `stale preparation releases its resources exactly once`() {
        val dispatcher = QueuedDispatcher()
        lateinit var coordinator: BackgroundHostReadinessCoordinator
        val work = FakeWork(
            beforePreparedResult = { coordinator.invalidate() },
        )
        coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)

        coordinator.requestHostPreparation { error("stale preparation must not start Host") }
        dispatcher.runAll()

        assertEquals(1, work.prepareCalls)
        assertEquals(1, work.releaseCalls)
        assertFalse(coordinator.state.value is HostReadinessState.Ready)
    }

    @Test
    fun `dismiss hides terminal presentation without erasing the structured result`() {
        val dispatcher = QueuedDispatcher()
        val work = FakeWork(
            runtimeState = runtimeState(
                directShell = availability(
                    PrivilegedRuntimeAvailabilityState.DISABLED_RECOVERABLE,
                    PrivilegedRuntimeFailureReason.WIRELESS_DEBUGGING_DISABLED,
                ),
            ),
        )
        val coordinator = BackgroundHostReadinessCoordinator(dispatcher, work)

        coordinator.requestHostPreparation { error("disabled runtime cannot prepare") }
        dispatcher.runAll()
        coordinator.dismissHostActionState()

        assertState<HostReadinessState.UserActionRequired>(coordinator.state.value)
        assertFalse(coordinator.uiState.value.hostActionVisible)
    }

    private class QueuedDispatcher : HostReadinessDispatcher {
        private val actions = ArrayDeque<() -> Unit>()

        val pendingCount: Int get() = actions.size

        override fun dispatch(action: () -> Unit): Boolean {
            actions.addLast(action)
            return true
        }

        fun runNext() {
            actions.removeFirst().invoke()
        }

        fun runAll() {
            while (actions.isNotEmpty()) runNext()
        }
    }

    private class FakeWork(
        private val runtimeState: PrivilegedRuntimeStateSnapshot = runtimeState(),
        private val preparedProviders: Map<PrivilegedCapability, PrivilegedProvider> = mapOf(
            PrivilegedCapability.VIDEO to PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapability.SYSTEM_AUDIO to PrivilegedProvider.DIRECT_SHELL,
            PrivilegedCapability.INPUT to PrivilegedProvider.DIRECT_SHELL,
        ),
        var shizukuPreparationCalls: Int = 0,
        private val beforePreparedResult: () -> Unit = {},
    ) : HostReadinessWork {
        var observeCalls = 0
        var prepareCalls = 0
        var releaseCalls = 0

        override fun observeVolatileRuntime(generation: Long): PrivilegedRuntimeStateSnapshot {
            observeCalls += 1
            return runtimeState.copy(generation = generation)
        }

        override fun prepareForHost(
            generation: Long,
            runtimeState: PrivilegedRuntimeStateSnapshot,
        ): HostReadinessState {
            prepareCalls += 1
            beforePreparedResult()
            val matrix = matrix(preparedProviders)
            val plan = (PrivilegedCapabilityPlanner.select(matrix) as PrivilegedCapabilityPlanResult.Complete).plan
            return HostReadinessState.Ready(
                PreparedPrivilegedCapabilityPlan(
                    generation = generation,
                    runtimeState = runtimeState,
                    acquiredRuntime = runtimeSnapshot(),
                    matrix = matrix,
                    plan = plan,
                    evidenceSource = HostReadinessEvidenceSource.CURRENT_HOST_ADMISSION,
                    commitState = HostReadinessPlanCommitState.FROZEN_AT_HOST_ADMISSION,
                    preparedAtMonotonicMs = 1L,
                ),
            )
        }

        override fun releasePreparedResources() {
            releaseCalls += 1
        }
    }

    private companion object {
        inline fun <reified T> assertState(value: HostReadinessState): T {
            assertTrue(value is T)
            return value as T
        }

        fun runtimeState(
            directShell: PrivilegedRuntimeAvailability = availability(PrivilegedRuntimeAvailabilityState.UNKNOWN),
        ): PrivilegedRuntimeStateSnapshot = PrivilegedRuntimeStateSnapshot(
            generation = 1L,
            observedAtMonotonicMs = 1L,
            directRoot = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
                PrivilegedRuntimeFailureReason.PLATFORM_UNSUPPORTED,
                "root_not_implemented",
            ),
            directShell = directShell,
            shizuku = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.SHIZUKU,
                PrivilegedRuntimeAvailabilityState.UNKNOWN,
                detail = "shizuku_unknown",
            ),
        )

        fun availability(
            state: PrivilegedRuntimeAvailabilityState,
            reason: PrivilegedRuntimeFailureReason? = null,
        ): PrivilegedRuntimeAvailability = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.DIRECT_SHELL,
            state,
            reason,
            "directshell_test",
        )

        fun runtimeSnapshot() = PrivilegedRuntimeSnapshot(
            kind = PrivilegedRuntimeKind.DIRECT_SHELL,
            state = PrivilegedRuntimeState.READY,
            generation = 7L,
        )

        fun matrix(providers: Map<PrivilegedCapability, PrivilegedProvider>): PrivilegedCapabilityMatrix =
            PrivilegedCapabilityMatrix.complete { provider, capability ->
                val selected = providers.getValue(capability)
                val state = when {
                    provider == selected -> CapabilityQualificationState.VALIDATED
                    provider == PrivilegedProvider.DIRECT_ROOT -> CapabilityQualificationState.NOT_IMPLEMENTED
                    else -> CapabilityQualificationState.DEFERRED
                }
                val provenance = CapabilityQualificationProvenance(
                    source = CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
                    runtimeGeneration = 7L,
                )
                if (state.isUsable) {
                    CapabilityQualification.usable(provider, capability, state, "test_usable", provenance)
                } else {
                    CapabilityQualification.unavailable(provider, capability, state, "test_unavailable", provenance)
                }
            }
    }
}
