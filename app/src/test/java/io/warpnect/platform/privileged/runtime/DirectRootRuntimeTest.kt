package io.warpnect.platform.privileged.runtime

import io.warpnect.platform.privileged.planning.AndroidPrivilegedCapabilityMatrixAdapter
import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanner
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.readiness.DirectShellRuntimeEnvironment
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateMapper
import io.warpnect.shizuku.PrivilegedAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootRuntimeTest {
    @Test
    fun `production bootstrap is absent not a root authorization prompt`() {
        val state = DirectRootSnapshot()
        assertEquals(DirectRootState.PRODUCTION_BOOTSTRAP_NOT_IMPLEMENTED, state.state)
        assertFalse(state.ready)
    }

    @Test
    fun `implemented lab bootstrap without a helper is not ready`() {
        val runtime = DirectRootRuntime(Bootstrap())
        assertEquals(DirectRootState.LAB_BOOTSTRAP_AVAILABLE, runtime.snapshot.state)
        assertFalse(runtime.snapshot.ready)
    }

    @Test
    fun `valid authenticated root session becomes an authority without remediation`() {
        val runtime = DirectRootRuntime(Bootstrap(), DirectRootRuntimeRegistry::publish)
        try {
            assertTrue(runtime.acquireAuthority().ready)
            val authority = DirectRootRuntimeRegistry.snapshot()
            assertTrue(authority.available)
            assertEquals(RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT, authority.provenance)
            assertTrue(authority.supportedActions.isEmpty())
        } finally {
            runtime.close()
            DirectRootRuntimeRegistry.publish(DirectRootSnapshot())
        }
    }

    @Test
    fun `uid2000 is rejected`() = rejects(identity().copy(uid = 2000, gid = 2000))

    @Test
    fun `wrong generation is rejected`() = rejects(identity().copy(generation = 11))

    @Test
    fun `laboratory helper cannot be presented as production root authorization`() =
        rejects(identity().copy(provenance = RootBootstrapProvenance.PRODUCTION_ROOT_MANAGER))

    @Test
    fun `wrong implementation is rejected`() = rejects(identity().copy(implementation = "other"))

    @Test
    fun `wrong APK hash is rejected`() = rejects(identity().copy(artifactSha256 = "b".repeat(64)))

    @Test
    fun `protocol mismatch is rejected`() = rejects(
        identity().copy(protocolVersion = DirectRootRuntime.PROTOCOL_VERSION + 1),
    )

    @Test
    fun `failed bootstrap does not become READY`() {
        val bootstrap = Bootstrap(fail = true)
        val runtime = DirectRootRuntime(bootstrap)
        assertEquals(DirectRootState.BOOTSTRAP_FAILED, runtime.acquireAuthority().state)
        assertTrue(bootstrap.canceled)
    }

    @Test
    fun `helper loss clears identity and authority`() {
        val bootstrap = Bootstrap()
        val runtime = DirectRootRuntime(bootstrap)
        runtime.acquireAuthority()
        bootstrap.control.lost = true
        assertFalse(runtime.checkLiveness())
        assertEquals(DirectRootState.HELPER_LOST, runtime.snapshot.state)
        assertTrue(bootstrap.canceled)
    }

    @Test
    fun `shutdown releases one owner and forbids reacquiring old generation`() {
        val bootstrap = Bootstrap()
        val runtime = DirectRootRuntime(bootstrap)
        runtime.acquireAuthority()
        runtime.close()
        runtime.close()
        assertEquals(1, bootstrap.control.shutdowns)
        assertEquals(DirectRootState.RELEASED, runtime.snapshot.state)
        assertTrue(bootstrap.canceled)
        assertTrue(runCatching { runtime.acquireAuthority() }.isFailure)
    }

    @Test
    fun `repeated acquisition does not create a duplicate owner`() {
        val bootstrap = Bootstrap()
        val runtime = DirectRootRuntime(bootstrap)
        runtime.acquireAuthority()
        runtime.acquireAuthority()
        assertEquals(1, bootstrap.acquires)
        runtime.close()
    }

    @Test
    fun `ready root observer cannot turn capability cells into Host support`() {
        val root = DirectRootRuntime(Bootstrap()).acquireAuthority()
        val shell = PrivilegedRuntimeSnapshot(PrivilegedRuntimeKind.DIRECT_SHELL, PrivilegedRuntimeState.IDLE)
        val observed = PrivilegedRuntimeStateMapper.snapshot(
            1,
            1,
            DirectShellRuntimeEnvironment(26, null),
            shell,
            PrivilegedAccessState.Unavailable,
            root,
        )
        assertEquals(PrivilegedRuntimeAvailabilityState.READY, observed.directRoot.state)
        assertFalse(observed.directRoot.candidateForHostAcquisition)
        val matrix = AndroidPrivilegedCapabilityMatrixAdapter.build(shell, null)
        PrivilegedCapability.entries.forEach {
            val cell = matrix.qualification(PrivilegedProvider.DIRECT_ROOT, it)
            if (it == PrivilegedCapability.INPUT) {
                assertFalse(cell.state.isUsable)
            } else {
                assertEquals(CapabilityQualificationState.NOT_IMPLEMENTED, cell.state)
            }
        }
        assertTrue(PrivilegedCapabilityPlanner.select(matrix) is PrivilegedCapabilityPlanResult.HostIncompatible)
    }

    @Test
    fun `implementation absent and production bootstrap absent are distinct`() {
        assertTrue(DirectRootSnapshot(DirectRootState.IMPLEMENTATION_UNAVAILABLE).state != DirectRootSnapshot().state)
    }

    @Test
    fun `root authority does not change established DirectShell or Shizuku complete plans`() {
        listOf(PrivilegedProvider.DIRECT_SHELL, PrivilegedProvider.SHIZUKU).forEach { selected ->
            val matrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
                CapabilityQualification(
                    provider,
                    capability,
                    if (provider == selected) {
                        CapabilityQualificationState.QUALIFIED
                    } else {
                        CapabilityQualificationState.NOT_IMPLEMENTED
                    },
                    "test",
                    CapabilityQualificationProvenance(CapabilityQualificationSource.PROVIDER_ADAPTER),
                )
            }
            val result = PrivilegedCapabilityPlanner.select(matrix) as PrivilegedCapabilityPlanResult.Complete
            assertEquals(setOf(selected), result.plan.participatingProviders.toSet())
        }
    }

    @Test
    fun `root state observations retain absent failed and released distinctions`() {
        val shell = PrivilegedRuntimeSnapshot(PrivilegedRuntimeKind.DIRECT_SHELL, PrivilegedRuntimeState.IDLE)
        listOf(
            DirectRootState.LAB_BOOTSTRAP_AVAILABLE,
            DirectRootState.BOOTSTRAP_FAILED,
            DirectRootState.RELEASED,
        ).forEach {
            val root = DirectRootSnapshot(state = it)
            val observed = PrivilegedRuntimeStateMapper.snapshot(
                1,
                1,
                DirectShellRuntimeEnvironment(26, null),
                shell,
                PrivilegedAccessState.Unavailable,
                root,
            )
            assertEquals(it, observed.directRoot.rootFoundation?.state)
            assertFalse(observed.directRoot.candidateForHostAcquisition)
            assertTrue(observed.directRoot.state != PrivilegedRuntimeAvailabilityState.READY)
        }
    }

    private fun rejects(identity: RootHelperIdentity) {
        val bootstrap = Bootstrap(identity)
        val runtime = DirectRootRuntime(bootstrap)
        assertEquals(DirectRootState.BOOTSTRAP_FAILED, runtime.acquireAuthority().state)
        assertTrue(bootstrap.control.closed)
        assertTrue(bootstrap.canceled)
    }

    private class Bootstrap(
        identity: RootHelperIdentity = identity(),
        private val fail: Boolean = false,
    ) : RootRuntimeBootstrap {
        override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
        override val generation = 10L
        override val expectedArtifactSha256 = "a".repeat(64)
        val control = Control(identity)
        var canceled = false
        var acquires = 0

        override fun acquire(): RootControlSession {
            acquires++
            check(!fail)
            return control
        }

        override fun cancel() {
            canceled = true
        }
    }

    private class Control(override val identity: RootHelperIdentity) : RootControlSession {
        var closed = false
        var lost = false
        var shutdowns = 0

        override fun ping() = check(!lost)

        override fun shutdown() {
            shutdowns++
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        private fun identity() = RootHelperIdentity(
            123, 0, 0, "u:r:su:s0", 26, 10, DirectRootRuntime.PROTOCOL_VERSION, DirectRootRuntime.IMPLEMENTATION,
            "a".repeat(
                64,
            ),
        )
    }
}
