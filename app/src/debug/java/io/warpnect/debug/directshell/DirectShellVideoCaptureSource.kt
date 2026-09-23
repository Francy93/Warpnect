package io.warpnect.debug.directshell

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState
import io.warpnect.platform.capture.DebugCaptureGatewayOverride
import io.warpnect.platform.capture.PrivilegedCaptureGateway
import java.io.IOException
import java.util.Arrays
import kotlinx.coroutines.runBlocking

/**
 * Explicitly armed DEBUG-only capture source that preserves the existing normal-process encoder,
 * sender, and protocol pipeline. It only moves privileged display-mirror control into DirectShell.
 */
internal class DirectShellVideoCaptureSource private constructor(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
) : DebugCaptureGatewayOverride {
    private val secret = DirectShellProbeProtocol.requireSecret(secret)

    @Volatile
    private var cleared = false

    override fun createGateway(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway =
        DirectShellVideoCaptureGateway(
            port = port,
            generationId = generationId,
            secret = copySecret(),
            onServiceDied = onServiceDied,
        )

    fun probe(): RuntimeProbe = probeServer(port, copySecret())

    fun describe(): String = "generation=$generationId port=$port cleared=$cleared"

    fun shutdown(): String {
        val shutdownSecret = copySecret()
        return try {
            DirectShellVideoCaptureProbeClient.connectLoopback(port, shutdownSecret).use { client ->
                "server_${client.call(DirectShellProbeProtocol.Command.SHUTDOWN, "").status.name.lowercase()}"
            }
        } catch (_: IOException) {
            "server_already_unreachable"
        } catch (_: DirectShellProbeProtocol.ProtocolException) {
            "server_protocol_unreachable"
        } finally {
            Arrays.fill(shutdownSecret, 0.toByte())
        }
    }

    fun clear() {
        if (!cleared) {
            Arrays.fill(secret, 0.toByte())
            cleared = true
        }
    }

    private fun copySecret(): ByteArray = if (cleared) ByteArray(0) else secret.clone()

    companion object {
        fun create(port: Int, generationId: Long, secret: ByteArray): DirectShellVideoCaptureSource? {
            if (
                port !in MIN_PORT..MAX_PORT ||
                generationId <= 0L ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            ) {
                return null
            }
            return try {
                DirectShellVideoCaptureSource(port, generationId, secret)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun probeServer(port: Int, secret: ByteArray): RuntimeProbe {
            if (secret.size != DirectShellProbeProtocol.SECRET_BYTES) {
                return RuntimeProbe(false, CaptureError.PrivilegedServiceUnavailable, "invalid_secret")
            }
            return try {
                DirectShellVideoCaptureProbeClient.connectLoopback(port, secret).use { client ->
                    val ping = client.call(DirectShellProbeProtocol.Command.PING, "")
                    if (ping.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(
                            false,
                            CaptureError.PrivilegedServiceUnavailable,
                            "ping_${ping.status.name}",
                        )
                    }
                    val qualification = client.call(
                        DirectShellProbeProtocol.Command.VIDEO_QUALIFY,
                        "",
                    )
                    val qualificationFacts = qualification.payload.facts()
                    if (qualification.status != DirectShellProbeProtocol.Status.OK ||
                        qualificationFacts["last_error"] != CaptureError.None.name
                    ) {
                        return RuntimeProbe(
                            false,
                            CaptureError.CaptureBackendUnavailable,
                            "qualify_${qualification.status.name}_" +
                                (qualificationFacts["classification"] ?: "unknown"),
                        )
                    }
                    val runtime = client.call(
                        DirectShellProbeProtocol.Command.GET_RUNTIME_INFO,
                        "",
                    )
                    val facts = runtime.payload.facts()
                    if (
                        runtime.status != DirectShellProbeProtocol.Status.OK ||
                        facts["uid"]?.toIntOrNull() != Process.SHELL_UID ||
                        facts["uid_kind"] != "SHELL" ||
                        facts["package_name"] != "com.android.shell" ||
                        facts["op_package_name"] != "com.android.shell" ||
                        facts["attribution_uid"]?.toIntOrNull() != Process.SHELL_UID
                    ) {
                        return RuntimeProbe(
                            false,
                            CaptureError.CapturePermissionDenied,
                            "shell_identity_rejected",
                        )
                    }
                    RuntimeProbe(
                        true,
                        CaptureError.None,
                        "shell_uid=${Process.SHELL_UID} backend=${qualificationFacts["backend"] ?: "unknown"}",
                    )
                }
            } catch (exception: IOException) {
                RuntimeProbe(
                    false,
                    CaptureError.PrivilegedServiceUnavailable,
                    "control_${exception.javaClass.simpleName}",
                )
            } catch (exception: DirectShellProbeProtocol.ProtocolException) {
                RuntimeProbe(
                    false,
                    CaptureError.PrivilegedServiceUnavailable,
                    "protocol_${exception.javaClass.simpleName}",
                )
            } finally {
                Arrays.fill(secret, 0.toByte())
            }
        }

        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65_535
    }

    internal data class RuntimeProbe(
        val available: Boolean,
        val error: CaptureError,
        val detail: String,
    )
}

/** Local app-side lifecycle facade for the authenticated DirectShell display-capture control path. */
private class DirectShellVideoCaptureGateway(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
    private val onServiceDied: () -> Unit,
) : PrivilegedCaptureGateway {
    private val lock = Any()
    private val controlLock = Any()
    private val secret = DirectShellProbeProtocol.requireSecret(secret)
    private var snapshot = CaptureSessionSnapshot()
    private var selectedBackend = CaptureBackend.None
    private var externalSurfaceArmed = false
    private var closed = false
    private var serviceDiedReported = false

    override suspend fun queryCapabilities(): CaptureCapabilities = synchronized(lock) {
        if (closed) return@synchronized unavailable(CaptureError.PrivilegedServiceUnavailable)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return@synchronized unavailable(CaptureError.UnsupportedAndroidVersion)
        }
        val control = control(DirectShellProbeProtocol.Command.VIDEO_QUALIFY)
        if (!control.success) return@synchronized unavailable(control.error)
        val facts = control.payload.facts()
        val backend = facts["backend"].toCaptureBackend()
        if (facts["last_error"] != CaptureError.None.name || backend == CaptureBackend.None) {
            return@synchronized unavailable(CaptureError.CaptureBackendUnavailable)
        }
        selectedBackend = backend
        CaptureCapabilities(
            privilegeState = CapturePrivilegeState.Ready,
            backend = backend,
            backendAvailable = true,
            supportedSourceDisplays = emptyList(),
            supportsDynamicProjection = true,
            platformApiLevel = Build.VERSION.SDK_INT,
            lastError = CaptureError.None,
        )
    }

    override suspend fun requestPermission(): CapturePermissionResult =
        CapturePermissionResult(queryCapabilities().lastError)

    override suspend fun startCapture(request: CaptureRequest, target: Surface): CaptureError = synchronized(lock) {
        if (closed) return@synchronized CaptureError.PrivilegedServiceUnavailable
        if (!target.isValid) return@synchronized CaptureError.InvalidTargetSurface
        if (request.outputWidth <= 0 || request.outputHeight <= 0) {
            return@synchronized CaptureError.InvalidCaptureDimensions
        }
        val capabilities = runBlocking { queryCapabilities() }
        if (!capabilities.backendAvailable) return@synchronized capabilities.lastError
        val arm = DirectShellVideoCaptureProbeSession.armExternalSurface(
            generationId,
            copySecret(),
            target,
        )
        if (!arm.success) {
            snapshot = snapshot.copy(
                state = CaptureState.Error,
                lastError = CaptureError.PrivilegedServiceUnavailable,
            )
            return@synchronized CaptureError.PrivilegedServiceUnavailable
        }
        externalSurfaceArmed = true
        val started = control(DirectShellProbeProtocol.Command.VIDEO_START, request.toPayload())
        if (!started.success) {
            finishExternalSurface()
            snapshot = snapshot.copy(state = CaptureState.Error, lastError = started.error)
            return@synchronized started.error
        }
        val facts = started.payload.facts()
        selectedBackend = facts["backend"].toCaptureBackend()
            .takeIf { it != CaptureBackend.None }
            ?: selectedBackend
        snapshot = CaptureSessionSnapshot(
            state = CaptureState.Running,
            backend = selectedBackend,
            sourceDisplayId = request.sourceDisplayId,
            targetWidth = request.outputWidth,
            targetHeight = request.outputHeight,
            lastError = CaptureError.None,
        )
        log(
            "DIRECT_SHELL_VIDEO_CAPTURE_SELECTED generation=$generationId backend=$selectedBackend " +
                "shizuku_video_capture=false",
        )
        CaptureError.None
    }

    override suspend fun updateCapture(request: CaptureRequest): CaptureError = synchronized(lock) {
        if (snapshot.state != CaptureState.Running) return@synchronized CaptureError.NotRunning
        val updated = control(DirectShellProbeProtocol.Command.VIDEO_UPDATE, request.toPayload())
        if (!updated.success) {
            snapshot = snapshot.copy(state = CaptureState.Error, lastError = updated.error)
            return@synchronized updated.error
        }
        snapshot = snapshot.copy(
            sourceDisplayId = request.sourceDisplayId,
            targetWidth = request.outputWidth,
            targetHeight = request.outputHeight,
            reconfigurationCount = snapshot.reconfigurationCount + 1,
            lastError = CaptureError.None,
        )
        CaptureError.None
    }

    override suspend fun stopCapture(): CaptureError = synchronized(lock) {
        var error = CaptureError.None
        if (snapshot.state == CaptureState.Running) {
            val stopped = control(DirectShellProbeProtocol.Command.VIDEO_STOP)
            error = stopped.error
        }
        finishExternalSurface()
        snapshot = snapshot.copy(
            state = if (error == CaptureError.None) CaptureState.Stopped else CaptureState.Error,
            lastError = error,
        )
        error
    }

    override fun snapshot(): CaptureSessionSnapshot = snapshot

    override fun close() {
        if (closed) return
        runBlocking { stopCapture() }
        // The runtime, rather than a capability-only gateway, owns the shell server lifetime.
        synchronized(lock) {
            if (closed) return
            closed = true
            Arrays.fill(secret, 0.toByte())
        }
    }

    private fun finishExternalSurface() {
        if (!externalSurfaceArmed) return
        DirectShellVideoCaptureProbeSession.finish(generationId)
        externalSurfaceArmed = false
    }

    private fun unavailable(error: CaptureError): CaptureCapabilities = CaptureCapabilities(
        privilegeState = CapturePrivilegeState.BackendUnavailable,
        backend = CaptureBackend.None,
        backendAvailable = false,
        supportedSourceDisplays = emptyList(),
        supportsDynamicProjection = false,
        platformApiLevel = Build.VERSION.SDK_INT,
        lastError = error,
    )

    private fun control(command: DirectShellProbeProtocol.Command, payload: String = ""): ControlResult =
        synchronized(controlLock) {
            try {
                DirectShellVideoCaptureProbeClient.connectLoopback(port, copySecret()).use { client ->
                    val response = client.call(command, payload)
                    ControlResult(
                        success = response.status == DirectShellProbeProtocol.Status.OK,
                        error = if (response.status == DirectShellProbeProtocol.Status.OK) {
                            CaptureError.None
                        } else {
                            CaptureError.PrivilegedServiceUnavailable
                        },
                        payload = response.payload,
                    )
                }
            } catch (exception: IOException) {
                reportServiceDied()
                ControlResult(
                    false,
                    CaptureError.PrivilegedServiceDied,
                    "control_${exception.javaClass.simpleName}",
                )
            } catch (exception: DirectShellProbeProtocol.ProtocolException) {
                reportServiceDied()
                ControlResult(
                    false,
                    CaptureError.PrivilegedServiceDied,
                    "protocol_${exception.javaClass.simpleName}",
                )
            }
        }

    private fun reportServiceDied() {
        if (!serviceDiedReported) {
            serviceDiedReported = true
            onServiceDied()
        }
    }

    private fun copySecret(): ByteArray = secret.clone()

    private fun CaptureRequest.toPayload(): String =
        "display_id=$sourceDisplayId;width=$outputWidth;height=$outputHeight;" +
            "follow_rotation=$followSourceRotation"

    private fun String?.toCaptureBackend(): CaptureBackend {
        return runCatching { CaptureBackend.valueOf(this.orEmpty()) }
            .getOrDefault(CaptureBackend.None)
    }

    private fun log(message: String) = Log.i(TAG, message)

    private data class ControlResult(
        val success: Boolean,
        val error: CaptureError,
        val payload: String,
    )

    private companion object {
        const val TAG = "WarpnectDirectShellVideo"
    }
}

private fun String.facts(): Map<String, String> = lineSequence()
    .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
    }
    .toMap()
