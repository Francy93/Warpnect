package io.warpnect.platform.capture

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState
import io.warpnect.platform.capture.privileged.IPrivilegedCaptureService
import io.warpnect.platform.capture.privileged.PrivilegedCaptureContract
import io.warpnect.platform.capture.privileged.PrivilegedCaptureUserService
import io.warpnect.platform.capture.privileged.toCaptureCapabilities
import io.warpnect.platform.capture.privileged.toCaptureSessionSnapshot
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku

internal class ShizukuCaptureGateway(
    private val context: Context,
    private val onServiceDied: () -> Unit,
) : PrivilegedCaptureGateway {
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedCaptureUserService::class.java.name),
    )
        .daemon(false)
        .debuggable((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        .processNameSuffix("capture")
        .tag("capture")
        .version(PrivilegedCaptureContract.SERVICE_VERSION)

    private var remote: IPrivilegedCaptureService? = null
    private var boundConnection: ServiceConnection? = null
    private var lastSnapshot = CaptureSessionSnapshot()

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        boundConnection = null
        lastSnapshot = lastSnapshot.copy(
            state = if (lastSnapshot.state == CaptureState.Stopped) {
                CaptureState.Stopped
            } else {
                CaptureState.Error
            },
            lastError = CaptureError.ShizukuBinderUnavailable,
        )
        onServiceDied()
    }

    init {
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override suspend fun queryCapabilities(): CaptureCapabilities {
        val readiness = shizukuReadiness()
        if (readiness != CaptureError.None) {
            return unavailableCapabilities(readiness)
        }
        val service = ensureService() ?: return unavailableCapabilities(CaptureError.PrivilegedServiceUnavailable)
        return try {
            service.queryCapabilities().toCaptureCapabilities()
        } catch (_: RemoteException) {
            remote = null
            unavailableCapabilities(CaptureError.PrivilegedServiceDied)
        } catch (_: RuntimeException) {
            unavailableCapabilities(CaptureError.PrivilegedServiceUnavailable)
        }
    }

    override suspend fun requestPermission(): CapturePermissionResult = when (val binderState = shizukuBinderState()) {
        CaptureError.None -> {
            if (hasShizukuPermission()) {
                CapturePermissionResult(CaptureError.None)
            } else {
                try {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    CapturePermissionResult(
                        error = CaptureError.ShizukuPermissionRequired,
                        requestIssued = true,
                    )
                } catch (_: RuntimeException) {
                    CapturePermissionResult(CaptureError.ShizukuBinderUnavailable)
                }
            }
        }
        else -> CapturePermissionResult(binderState)
    }

    override suspend fun startCapture(request: CaptureRequest, target: Surface): CaptureError {
        val readiness = shizukuReadiness()
        if (readiness != CaptureError.None) {
            return readiness
        }
        val service = ensureService() ?: return CaptureError.PrivilegedServiceUnavailable
        return try {
            val error = CaptureError.fromCode(
                service.startCapture(
                    request.sourceDisplayId,
                    request.outputWidth,
                    request.outputHeight,
                    request.followSourceRotation,
                    target,
                ),
            )
            lastSnapshot = service.getState().toCaptureSessionSnapshot()
            error
        } catch (_: RemoteException) {
            remote = null
            CaptureError.PrivilegedServiceDied
        } catch (_: RuntimeException) {
            CaptureError.PrivilegedServiceUnavailable
        }
    }

    override suspend fun updateCapture(request: CaptureRequest): CaptureError {
        val service = remote ?: return CaptureError.PrivilegedServiceUnavailable
        return try {
            val error = CaptureError.fromCode(
                service.updateCapture(
                    request.sourceDisplayId,
                    request.outputWidth,
                    request.outputHeight,
                    request.followSourceRotation,
                ),
            )
            lastSnapshot = service.getState().toCaptureSessionSnapshot()
            error
        } catch (_: RemoteException) {
            remote = null
            CaptureError.PrivilegedServiceDied
        } catch (_: RuntimeException) {
            CaptureError.PrivilegedServiceUnavailable
        }
    }

    override suspend fun stopCapture(): CaptureError {
        val service = remote ?: return CaptureError.None
        return try {
            val error = CaptureError.fromCode(service.stopCapture())
            lastSnapshot = service.getState().toCaptureSessionSnapshot()
            error
        } catch (_: RemoteException) {
            remote = null
            CaptureError.PrivilegedServiceDied
        } catch (_: RuntimeException) {
            CaptureError.PrivilegedServiceUnavailable
        }
    }

    override fun snapshot(): CaptureSessionSnapshot = lastSnapshot

    override fun close() {
        runCatching {
            boundConnection?.let {
                Shizuku.unbindUserService(args, it, true)
            }
        }
        Shizuku.removeBinderDeadListener(binderDeadListener)
        remote = null
        boundConnection = null
    }

    private suspend fun ensureService(): IPrivilegedCaptureService? {
        remote?.let { return it }
        if (shizukuReadiness() != CaptureError.None) {
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val captureService = IPrivilegedCaptureService.Stub.asInterface(service)
                    remote = captureService
                    boundConnection = this
                    if (continuation.isActive) {
                        continuation.resume(captureService)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                    boundConnection = null
                    onServiceDied()
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching {
                    Shizuku.unbindUserService(args, connection, false)
                }
            }
            try {
                Shizuku.bindUserService(args, connection)
            } catch (_: RuntimeException) {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun shizukuReadiness(): CaptureError {
        val binderState = shizukuBinderState()
        if (binderState != CaptureError.None) {
            return binderState
        }
        return if (hasShizukuPermission()) {
            CaptureError.None
        } else {
            CaptureError.ShizukuPermissionRequired
        }
    }

    private fun shizukuBinderState(): CaptureError = try {
        if (Shizuku.pingBinder()) {
            CaptureError.None
        } else {
            CaptureError.ShizukuUnavailable
        }
    } catch (_: RuntimeException) {
        CaptureError.ShizukuUnavailable
    }

    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    private fun unavailableCapabilities(error: CaptureError): CaptureCapabilities = CaptureCapabilities(
        privilegeState = when (error) {
            CaptureError.ShizukuUnavailable,
            CaptureError.ShizukuBinderUnavailable,
            -> CapturePrivilegeState.ShizukuUnavailable
            CaptureError.ShizukuPermissionRequired -> CapturePrivilegeState.PermissionRequired
            CaptureError.ShizukuPermissionDenied -> CapturePrivilegeState.PermissionDenied
            CaptureError.PrivilegedServiceUnavailable,
            CaptureError.PrivilegedServiceDied,
            -> CapturePrivilegeState.UserServiceUnavailable
            else -> CapturePrivilegeState.BackendUnavailable
        },
        backend = CaptureBackend.None,
        backendAvailable = false,
        supportedSourceDisplays = emptyList(),
        supportsDynamicProjection = false,
        platformApiLevel = android.os.Build.VERSION.SDK_INT,
        lastError = error,
    )

    private companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 20_020
    }
}
