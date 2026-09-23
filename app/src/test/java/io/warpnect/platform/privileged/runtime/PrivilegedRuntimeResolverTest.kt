package io.warpnect.platform.privileged.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedRuntimeResolverTest {
    @Test
    fun `uses established Shizuku path only when DirectShell is not armed`() {
        val result = PrivilegedRuntimeResolver(
            directShellRuntime = null,
            allowShizukuFallback = true,
        ).acquireForHost()

        assertEquals(PrivilegedRuntimeKind.SHIZUKU, result.kind)
        assertTrue(result.ready)
        assertEquals(PrivilegedRuntimeFailure.NONE, result.failure)
        assertTrue(result.detail.contains("ROOT_NOT_IMPLEMENTED"))
    }

    @Test
    fun `selects an armed DirectShell runtime before legacy fallback`() {
        val runtime = FakeRuntime(
            PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                state = PrivilegedRuntimeState.READY,
                generation = 42L,
            ),
        )

        val result = PrivilegedRuntimeResolver(runtime, allowShizukuFallback = true).acquireForHost()

        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, result.kind)
        assertTrue(result.ready)
        assertEquals(1, runtime.acquireCalls)
    }

    @Test
    fun `does not silently fall back when an armed DirectShell runtime fails`() {
        val runtime = FakeRuntime(
            PrivilegedRuntimeSnapshot(
                kind = PrivilegedRuntimeKind.DIRECT_SHELL,
                state = PrivilegedRuntimeState.UNAVAILABLE,
                failure = PrivilegedRuntimeFailure.DIRECTSHELL_CONNECT_FAILED,
            ),
        )

        val result = PrivilegedRuntimeResolver(runtime, allowShizukuFallback = true).acquireForHost()

        assertEquals(PrivilegedRuntimeKind.DIRECT_SHELL, result.kind)
        assertFalse(result.ready)
        assertEquals(PrivilegedRuntimeFailure.DIRECTSHELL_CONNECT_FAILED, result.failure)
        assertEquals(1, runtime.acquireCalls)
    }

    private class FakeRuntime(
        private val result: PrivilegedRuntimeSnapshot,
    ) : PrivilegedRuntime {
        override val kind = PrivilegedRuntimeKind.DIRECT_SHELL
        var acquireCalls = 0

        override fun acquireForHost(): PrivilegedRuntimeSnapshot {
            acquireCalls += 1
            return result
        }

        override fun releaseForHost() = Unit
    }
}
