package io.warpnect.platform.capture

import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootState
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.privileged.runtime.RootControlSession
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import io.warpnect.platform.privileged.runtime.RootRuntimeBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootVideoCaptureGatewayTest {
    @Test fun `authenticated ready runtime delegates capability probe`() = runBlocking {
        val (runtime, control) = ready()
        assertTrue(DirectRootVideoCaptureGateway(runtime).queryCapabilities().backendAvailable)
        assertEquals(1, control.queries)
        runtime.close()
    }

    @Test fun `absent runtime rejects without probing`() = runBlocking {
        val control = Control()
        val runtime = runtime(control)
        assertFalse(DirectRootVideoCaptureGateway(runtime).queryCapabilities().backendAvailable)
        assertEquals(0, control.queries)
        runtime.close()
    }

    @Test fun `stale gateway cannot reach next generation`() {
        val (runtime, _) = ready()
        val gateway = DirectRootVideoCaptureGateway(runtime)
        runtime.close()
        val (next, fresh) = ready(11)
        assertEquals(CaptureError.PrivilegedServiceDied, gateway.startMirror { error("must_not_run") })
        assertEquals(0, fresh.queries)
        next.close()
    }

    @Test fun `mirror failure does not become active`() = runBlocking {
        val (runtime, control) = ready()
        val gateway = DirectRootVideoCaptureGateway(runtime)
        assertEquals(CaptureError.CaptureCreationFailed, gateway.startMirror { CaptureError.CaptureCreationFailed })
        assertEquals(CaptureError.None, gateway.stopCapture())
        assertEquals(0, control.stops)
        runtime.close()
    }

    @Test fun `normal stop double stop and close preserve single ownership`() = runBlocking {
        val (runtime, control) = ready()
        val gateway = DirectRootVideoCaptureGateway(runtime)
        assertEquals(CaptureError.None, gateway.startMirror { CaptureError.None })
        assertEquals(CaptureError.AlreadyRunning, gateway.startMirror { error("must_not_run") })
        assertEquals(CaptureError.None, gateway.stopCapture())
        assertEquals(CaptureError.None, gateway.stopCapture())
        gateway.close()
        gateway.close()
        assertEquals(1, control.stops)
        runtime.close()
    }

    @Test fun `close while running stops mirror but does not close shared runtime`() {
        val (runtime, control) = ready()
        val gateway = DirectRootVideoCaptureGateway(runtime)
        gateway.startMirror { CaptureError.None }
        gateway.close()
        assertEquals(1, control.stops)
        assertTrue(runtime.snapshot.ready)
        assertEquals(CaptureError.PrivilegedServiceDied, gateway.startMirror { CaptureError.None })
        runtime.close()
    }

    @Test fun `helper loss tears down capture and marks runtime unavailable`() = runBlocking {
        val (runtime, control) = ready()
        var losses = 0
        val gateway = DirectRootVideoCaptureGateway(runtime) { losses++ }
        gateway.startMirror { CaptureError.None }
        control.lost = true
        assertEquals(CaptureError.PrivilegedServiceDied, gateway.updateCapture(CaptureRequest(0, 640, 360)))
        assertEquals(1, losses)
        assertEquals(DirectRootState.HELPER_LOST, runtime.snapshot.state)
        assertEquals(CaptureError.None, gateway.stopCapture())
        runtime.close()
    }

    @Test fun `unavailable mirror backend does not become qualified`() = runBlocking {
        val (runtime, control) = ready()
        control.available = false
        assertFalse(DirectRootVideoCaptureGateway(runtime).queryCapabilities().backendAvailable)
        runtime.close()
    }

    @Test fun `heartbeat loss notifies idle caller exactly once`() = runBlocking {
        val (runtime, control) = ready()
        var losses = 0
        val gateway = DirectRootVideoCaptureGateway(runtime) { losses++ }
        gateway.startMirror { CaptureError.None }
        control.lost = true
        assertFalse(runtime.checkLiveness())
        assertEquals(1, losses)
        assertEquals(CaptureError.PrivilegedServiceDied, gateway.snapshot().lastError)
        assertFalse(runtime.checkLiveness())
        assertEquals(1, losses)
        assertEquals(CaptureError.None, gateway.stopCapture())
        gateway.close()
        runtime.close()
    }

    @Test fun `closed gateway is detached from runtime loss observation`() {
        val (runtime, control) = ready()
        var losses = 0
        val gateway = DirectRootVideoCaptureGateway(runtime) { losses++ }
        gateway.startMirror { CaptureError.None }
        gateway.close()
        control.lost = true
        assertFalse(runtime.checkLiveness())
        assertEquals(0, losses)
        runtime.close()
    }

    private fun ready(generation: Long = 10): Pair<DirectRootRuntime, Control> {
        val control = Control(generation)
        return runtime(control).also { assertTrue(it.acquireAuthority().ready) } to control
    }
    private fun runtime(control: Control) = DirectRootRuntime(object : RootRuntimeBootstrap {
        override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
        override val generation = control.identity.generation
        override val expectedArtifactSha256 = "a".repeat(64)
        override fun acquire() = control
        override fun cancel() = Unit
    })
    private class Control(generation: Long = 10) : RootControlSession, RootVideoControl {
        override val identity =
            RootHelperIdentity(
                100, 0, 0, "u:r:su:s0", 26, generation,
                DirectRootRuntime.PROTOCOL_VERSION, DirectRootRuntime.IMPLEMENTATION,
                "a".repeat(
                    64,
                ),
            )
        var queries = 0
        var stops = 0
        var lost = false
        var available = true
        override fun ping() = check(!lost)
        override fun shutdown() = Unit
        override fun close() = Unit
        override fun videoCapabilities(): CaptureCapabilities {
            check(!lost)
            queries++
            return CaptureCapabilities(
                CapturePrivilegeState.Ready,
                CaptureBackend.SurfaceControlDisplay,
                available,
                emptyList(),
                true,
                26,
                CaptureError.None,
            )
        }
        override fun startVideo(request: CaptureRequest, target: Surface) = CaptureError.None
        override fun updateVideo(request: CaptureRequest): CaptureError {
            check(!lost)
            return CaptureError.None
        }
        override fun stopVideo(): CaptureError {
            stops++
            return CaptureError.None
        }
        override fun videoSnapshot() = CaptureSessionSnapshot()
    }
}
