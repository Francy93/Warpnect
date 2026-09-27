package io.warpnect.platform.capture

import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import java.util.concurrent.atomic.AtomicBoolean

internal class DirectRootVideoCaptureGateway(
    private val runtime: DirectRootRuntime,
    private val onServiceDied: () -> Unit = {},
) : PrivilegedCaptureGateway {
    private val generation = runtime.snapshot.generation

    @Volatile private var closed = false

    @Volatile private var active = false

    @Volatile private var latest = CaptureSessionSnapshot()
    private val lossReported = AtomicBoolean()
    private val observation = runtime.observe {
        if (active && (!runtime.snapshot.ready || runtime.snapshot.generation != generation)) {
            active = false
            latest = latest.copy(lastError = CaptureError.PrivilegedServiceDied)
            notifyLoss()
        }
    }

    private fun control(): RootVideoControl? = generation?.let { runtime.videoControl(it) }.takeIf { !closed }

    override suspend fun queryCapabilities(): CaptureCapabilities = try {
        control()?.videoCapabilities() ?: unavailable()
    } catch (_: Exception) {
        lost()
        unavailable()
    }

    override suspend fun requestPermission(): CapturePermissionResult =
        CapturePermissionResult(if (control() != null) CaptureError.None else CaptureError.PrivilegedServiceDied)

    override suspend fun startCapture(request: CaptureRequest, target: Surface): CaptureError {
        if (!target.isValid) return CaptureError.InvalidTargetSurface
        return startMirror { it.startVideo(request, target) }
    }

    internal fun startMirror(start: (RootVideoControl) -> CaptureError): CaptureError {
        if (active) return CaptureError.AlreadyRunning
        return call { backend ->
            start(backend).also { active = it == CaptureError.None }
        }
    }

    override suspend fun updateCapture(request: CaptureRequest): CaptureError =
        if (!active) CaptureError.NotRunning else call { it.updateVideo(request) }

    override suspend fun stopCapture(): CaptureError {
        if (!active) return CaptureError.None
        return call { it.stopVideo() }.also { active = false }
    }

    private fun call(operation: (RootVideoControl) -> CaptureError): CaptureError {
        val backend = control() ?: return CaptureError.PrivilegedServiceDied
        return try {
            operation(backend).also { latest = backend.videoSnapshot() }
        } catch (_: Exception) {
            lost()
            CaptureError.PrivilegedServiceDied
        }
    }

    private fun lost() {
        active = false
        latest = latest.copy(lastError = CaptureError.PrivilegedServiceDied)
        runtime.videoConnectionLost()
        notifyLoss()
    }

    private fun notifyLoss() {
        if (!lossReported.compareAndSet(false, true)) return
        onServiceDied()
    }

    override fun snapshot(): CaptureSessionSnapshot = latest.copy(
        lastError = if (active && control() == null) CaptureError.PrivilegedServiceDied else latest.lastError,
    )

    override fun close() {
        if (closed) return
        if (active) call { it.stopVideo() }
        active = false
        closed = true
        observation.close()
    }

    private fun unavailable() = CaptureCapabilities(
        CapturePrivilegeState.UserServiceUnavailable,
        CaptureBackend.None,
        false,
        emptyList(),
        false,
        runtime.snapshot.identity?.api ?: 0,
        CaptureError.PrivilegedServiceDied,
    )
}
