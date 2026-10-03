package io.warpnect.platform.privileged.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedRuntimeRegistryTest {
    @After
    fun resetRegistry() {
        PrivilegedRuntimeRegistry.resetForTests()
    }

    @Test
    fun `automatic DirectShell registration is lazy and selected for normal Host acquisition`() {
        val runtime = FakeRuntime(readySnapshot())
        var factoryCalls = 0

        assertTrue(
            PrivilegedRuntimeRegistry.registerAutomaticDirectShell(
                PrivilegedRuntimeFactory {
                    factoryCalls += 1
                    runtime
                },
            ),
        )

        assertEquals(0, factoryCalls)
        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, PrivilegedRuntimeRegistry.snapshot().kind)

        val result = PrivilegedRuntimeRegistry.acquireForHost()

        assertTrue(result.ready)
        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, result.kind)
        assertEquals(1, factoryCalls)
        assertEquals(1, runtime.acquireCalls)
    }

    @Test
    fun `automatic DirectShell factory failure is terminal instead of falling back to Shizuku`() {
        assertTrue(
            PrivilegedRuntimeRegistry.registerAutomaticDirectShell(
                PrivilegedRuntimeFactory { error("factory_failure") },
            ),
        )

        val result = PrivilegedRuntimeRegistry.acquireForHost()

        assertFalse(result.ready)
        assertEquals(PrivilegedRuntimeKind.NONE, result.kind)
        assertEquals(PrivilegedRuntimeFailure.DIRECTSHELL_LAUNCH_FAILED, result.failure)
        assertEquals(PrivilegedRuntimePhase.FAILED, result.phase)
    }

    @Test
    fun `Host release retains automatic DirectShell selection for the next enable`() {
        val runtime = FakeRuntime(readySnapshot())
        assertTrue(PrivilegedRuntimeRegistry.registerAutomaticDirectShell(PrivilegedRuntimeFactory { runtime }))

        assertTrue(PrivilegedRuntimeRegistry.acquireForHost().ready)
        PrivilegedRuntimeRegistry.releaseForHost()
        val restarted = PrivilegedRuntimeRegistry.acquireForHost()

        assertEquals(1, runtime.releaseCalls)
        assertTrue(restarted.ready)
        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, restarted.kind)
        assertEquals(2, runtime.acquireCalls)
    }

    @Test
    fun `registered runtime owner loss updates snapshot and notifies observers without fallback`() {
        val runtime = FakeRuntime(readySnapshot())
        assertTrue(PrivilegedRuntimeRegistry.installDirectShell(runtime))
        assertTrue(PrivilegedRuntimeRegistry.acquireForHost().ready)
        val observed = mutableListOf<PrivilegedRuntimeSnapshot>()
        val observation = PrivilegedRuntimeRegistry.observeHealth { observed += it }
        val lost = readySnapshot().copy(
            state = PrivilegedRuntimeState.UNAVAILABLE,
            phase = PrivilegedRuntimePhase.FAILED,
            failure = PrivilegedRuntimeFailure.DIRECTSHELL_OWNER_LOST,
        )

        PrivilegedRuntimeRegistry.reportHealth(runtime, lost)

        assertEquals(lost, PrivilegedRuntimeRegistry.snapshot())
        assertEquals(listOf(lost), observed)
        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, PrivilegedRuntimeRegistry.acquireForHost().kind)
        assertFalse(PrivilegedRuntimeRegistry.acquireForHost().ready)
        observation.close()
    }

    @Test
    fun `failed consumed helper replacement withdraws ready health with exact prerequisite reason`() {
        val runtime = FakeRuntime(readySnapshot())
        assertTrue(PrivilegedRuntimeRegistry.installDirectShell(runtime))
        assertTrue(PrivilegedRuntimeRegistry.acquireForHost().ready)
        val observed = mutableListOf<PrivilegedRuntimeSnapshot>()
        val observation = PrivilegedRuntimeRegistry.observeHealth { observed += it }
        val failed = readySnapshot().copy(
            state = PrivilegedRuntimeState.UNAVAILABLE,
            phase = PrivilegedRuntimePhase.WIRELESS_DEBUGGING_DISABLED,
            failure = PrivilegedRuntimeFailure.DIRECTSHELL_WIRELESS_DEBUGGING_DISABLED,
            detail = "direct_shell=feature_replacement_failed role=PcmSharedRing",
        )
        PrivilegedRuntimeRegistry.reportHealth(runtime, failed)
        assertEquals(listOf(failed), observed)
        assertFalse(PrivilegedRuntimeRegistry.snapshot().ready)
        assertEquals(failed, PrivilegedRuntimeRegistry.snapshot())
        assertEquals(1, runtime.acquireCalls)
        observation.close()
    }

    @Test
    fun `stale runtime generation cannot withdraw current runtime`() {
        val runtime = FakeRuntime(readySnapshot())
        assertTrue(PrivilegedRuntimeRegistry.installDirectShell(runtime))
        assertTrue(PrivilegedRuntimeRegistry.acquireForHost().ready)
        var events = 0
        val observation = PrivilegedRuntimeRegistry.observeHealth { events++ }

        PrivilegedRuntimeRegistry.reportHealth(
            runtime,
            readySnapshot().copy(
                generation = 6L,
                state = PrivilegedRuntimeState.UNAVAILABLE,
                failure = PrivilegedRuntimeFailure.DIRECTSHELL_OWNER_LOST,
            ),
        )

        assertTrue(PrivilegedRuntimeRegistry.snapshot().ready)
        assertEquals(0, events)
        observation.close()
    }

    @Test
    fun `late owner loss after normal Host release cannot revive runtime health`() {
        val runtime = FakeRuntime(readySnapshot())
        assertTrue(PrivilegedRuntimeRegistry.installDirectShell(runtime))
        assertTrue(PrivilegedRuntimeRegistry.acquireForHost().ready)
        PrivilegedRuntimeRegistry.releaseForHost()

        PrivilegedRuntimeRegistry.reportHealth(
            runtime,
            readySnapshot().copy(
                state = PrivilegedRuntimeState.UNAVAILABLE,
                failure = PrivilegedRuntimeFailure.DIRECTSHELL_OWNER_LOST,
            ),
        )

        assertEquals(PrivilegedRuntimeState.RELEASED, PrivilegedRuntimeRegistry.snapshot().state)
        assertEquals(
            PrivilegedRuntimeFailure.DIRECTSHELL_RUNTIME_RELEASED,
            PrivilegedRuntimeRegistry.snapshot().failure,
        )
    }

    private fun readySnapshot() = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_SHELL,
        state = PrivilegedRuntimeState.READY,
        phase = PrivilegedRuntimePhase.RUNTIME_READY,
        generation = 7L,
    )

    private class FakeRuntime(
        private val result: PrivilegedRuntimeSnapshot,
    ) : PrivilegedRuntime {
        override val kind = PrivilegedRuntimeKind.DIRECT_SHELL
        var acquireCalls = 0
        var releaseCalls = 0

        override fun acquireForHost(): PrivilegedRuntimeSnapshot {
            acquireCalls += 1
            return result
        }

        override fun releaseForHost() {
            releaseCalls += 1
        }
    }
}
