package io.warpnect.debug.directshell

import android.content.Context
import android.os.Process
import android.util.Log
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.input.injection.AndroidInputInjectionController
import io.warpnect.platform.input.injection.DebugInputInjectionControllerOverride
import io.warpnect.platform.input.injection.PrivilegedInputInjectionGateway
import io.warpnect.platform.input.injection.unavailableInputInjectionSnapshot
import java.io.IOException
import java.util.Arrays

/**
 * Explicitly armed, process-local DEBUG source for DirectShell-backed Host Input Session tests.
 *
 * It is intentionally not a production capability provider. A development harness must launch a
 * shell server first, then arm this source with the fresh server generation and HMAC secret.
 */
internal class DirectShellInputSessionSource private constructor(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
) : DebugInputInjectionControllerOverride {
    private val secret = DirectShellProbeProtocol.requireSecret(secret)

    @Volatile
    private var cleared = false

    override fun createController(context: Context): InputInjectionController =
        AndroidInputInjectionController.fromGatewayFactory { onServiceDied ->
            DirectShellInputSessionGateway(
                port = port,
                generationId = generationId,
                secret = copySecret(),
                onServiceDied = onServiceDied,
            )
        }

    fun probe(): RuntimeProbe = probeServer(port, generationId, copySecret())

    fun describe(): String = "generation=$generationId port=$port cleared=$cleared"

    fun shutdown(): String {
        val shutdownSecret = copySecret()
        if (shutdownSecret.size != DirectShellProbeProtocol.SECRET_BYTES) return "secret_cleared"
        return try {
            DirectShellInputSessionClient.connectLoopback(port, shutdownSecret).use { client ->
                val response = client.call(DirectShellProbeProtocol.Command.SHUTDOWN)
                "server_${response.status.name.lowercase()}"
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
        fun create(port: Int, generationId: Long, secret: ByteArray): DirectShellInputSessionSource? {
            if (port !in MIN_PORT..MAX_PORT || generationId <= 0L) return null
            return try {
                DirectShellInputSessionSource(port, generationId, secret)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        internal fun probeServer(port: Int, generationId: Long, secret: ByteArray): RuntimeProbe {
            if (secret.size != DirectShellProbeProtocol.SECRET_BYTES || generationId <= 0L) {
                return RuntimeProbe(false, InputInjectionError.ServiceUnavailable, "invalid_configuration")
            }
            return try {
                DirectShellInputSessionClient.connectLoopback(port, secret).use { client ->
                    val ping = client.call(DirectShellProbeProtocol.Command.PING)
                    if (ping.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(false, InputInjectionError.ServiceUnavailable, "ping_${ping.status.name}")
                    }
                    val runtime = client.call(DirectShellProbeProtocol.Command.GET_RUNTIME_INFO)
                    if (runtime.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(
                            false,
                            InputInjectionError.ServiceUnavailable,
                            "runtime_${runtime.status.name}",
                        )
                    }
                    val facts = runtime.payload.toString(Charsets.UTF_8).lineSequence()
                        .mapNotNull { line ->
                            val separator = line.indexOf('=')
                            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                        }
                        .toMap()
                    val uid = facts["uid"]?.toIntOrNull()
                    val shellIdentity = uid == Process.SHELL_UID &&
                        facts["uid_kind"] == "SHELL" &&
                        facts["package_name"] == SHELL_PACKAGE &&
                        facts["op_package_name"] == SHELL_PACKAGE &&
                        facts["attribution_uid"] == Process.SHELL_UID.toString() &&
                        facts["attribution_package"] == SHELL_PACKAGE
                    if (!shellIdentity) {
                        return RuntimeProbe(false, InputInjectionError.ServiceUnavailable, "shell_identity_rejected")
                    }
                    val capabilities = client.call(
                        DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES,
                        DirectShellInputSessionCodec.generationRequest(generationId),
                    )
                    if (capabilities.status != DirectShellProbeProtocol.Status.OK) {
                        return RuntimeProbe(
                            false,
                            InputInjectionError.ServiceUnavailable,
                            "capabilities_${capabilities.status.name}",
                        )
                    }
                    val decoded = DirectShellInputSessionCodec.decodeCapabilities(capabilities.payload, generationId)
                    val value = decoded.value
                        ?: return RuntimeProbe(
                            false,
                            InputInjectionError.ServiceUnavailable,
                            "capabilities_${decoded.error}",
                        )
                    if (!value.isQualifiedForHostInput()) {
                        return RuntimeProbe(
                            false,
                            value.lastError.takeUnless { it == InputInjectionError.None }
                                ?: InputInjectionError.InputApiUnavailable,
                            "input_api_${value.lastError.name}",
                        )
                    }
                    RuntimeProbe(
                        true,
                        InputInjectionError.None,
                        "shell_uid=$uid api=${facts["api"] ?: "unknown"}",
                    )
                }
            } catch (exception: IOException) {
                RuntimeProbe(
                    false,
                    InputInjectionError.ServiceUnavailable,
                    "control_${exception.javaClass.simpleName}",
                )
            } catch (exception: DirectShellProbeProtocol.ProtocolException) {
                RuntimeProbe(
                    false,
                    InputInjectionError.ServiceUnavailable,
                    "protocol_${exception.javaClass.simpleName}",
                )
            } finally {
                Arrays.fill(secret, 0.toByte())
            }
        }

        private const val SHELL_PACKAGE = "com.android.shell"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65_535

        private fun InputInjectionCapabilities.isQualifiedForHostInput(): Boolean {
            return serviceAvailable &&
                inputManagerApiResolved &&
                displayTargetingSupported &&
                keyInjectionSupported &&
                touchInjectionSupported &&
                pointerInjectionSupported &&
                joystickInjectionSupported &&
                lastError == InputInjectionError.None
        }
    }

    internal data class RuntimeProbe(
        val available: Boolean,
        val error: InputInjectionError,
        val detail: String,
    )
}

/**
 * Session-owned normal-process gateway for the DirectShell Input integration spike.
 *
 * A single authenticated connection preserves request order for the life of one receiver. A
 * transport or HMAC failure is terminal for that gateway; it does not reconnect or fall back.
 */
internal class DirectShellInputSessionGateway(
    private val port: Int,
    private val generationId: Long,
    secret: ByteArray,
    private val onServiceDied: () -> Unit,
) : PrivilegedInputInjectionGateway {
    private val lock = Any()
    private val secret = DirectShellProbeProtocol.requireSecret(secret)
    private var client: DirectShellInputSessionClient? = null
    private var connectionAttempted = false
    private var runtimeVerified = false
    private var sessionPrepared = false
    private var started = false
    private var closed = false
    private var deathReported = false
    private var lastSnapshot = InputInjectionSnapshot()

    override suspend fun queryCapabilities(): InputInjectionCapabilities = synchronized(lock) {
        if (closed) return@synchronized unavailable(InputInjectionError.Closed)
        val response = callLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES,
            DirectShellInputSessionCodec.generationRequest(generationId),
        ) ?: return@synchronized unavailable(lastSnapshot.lastError)
        if (response.status != DirectShellProbeProtocol.Status.OK) {
            return@synchronized unavailable(markControlFailureLocked(response.status))
        }
        val decoded = DirectShellInputSessionCodec.decodeCapabilities(response.payload, generationId)
        val capabilities = decoded.value ?: return@synchronized unavailable(markMalformedLocked(decoded.error))
        lastSnapshot = lastSnapshot.copy(
            backend = capabilities.backend,
            privilegedUid = capabilities.privilegedUid,
            privilegedUidKind = capabilities.privilegedUidKind,
            apiResolved = capabilities.inputManagerApiResolved,
            targetUidSupported = capabilities.targetUidInjectionSupported,
            displayTargetingSupported = capabilities.displayTargetingSupported,
            lastError = capabilities.lastError,
        )
        Log.i(
            TAG,
            "DIRECT_SHELL_INPUT_SESSION_CAPABILITY generation=$generationId " +
                "available=${capabilities.serviceAvailable} " +
                "backend=${capabilities.backend.name} error=${capabilities.lastError.name} " +
                "shizuku_input_producer=false",
        )
        capabilities
    }

    override suspend fun requestPermission(): InputInjectionPermissionResult = synchronized(lock) {
        val capabilities = queryCapabilitiesLocked()
        InputInjectionPermissionResult(capabilities.lastError, requestIssued = false)
    }

    override suspend fun prepare(config: InputInjectionConfig): InputInjectionError = synchronized(lock) {
        if (closed) return@synchronized InputInjectionError.Closed
        config.validate().takeIf { it != InputInjectionError.None }?.let { return@synchronized it }
        val response = callLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_PREPARE,
            DirectShellInputSessionCodec.encodePrepare(generationId, config),
        ) ?: return@synchronized lastSnapshot.lastError
        val error = resultErrorLocked(response)
        sessionPrepared = true
        if (error == InputInjectionError.None) {
            lastSnapshot = snapshotLocked().copy(
                state = InputInjectionState.Prepared,
                injectionMode = config.injectionMode,
                targetUid = config.targetUid,
                lastError = InputInjectionError.None,
            )
            Log.i(
                TAG,
                "DIRECT_SHELL_INPUT_SESSION_PREPARED generation=$generationId " +
                    "mode=${config.injectionMode.name} shizuku_input_producer=false",
            )
        }
        error
    }

    override fun start(): InputInjectionError = synchronized(lock) {
        if (closed) return@synchronized InputInjectionError.Closed
        val response = callLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_START,
            DirectShellInputSessionCodec.generationRequest(generationId),
        ) ?: return@synchronized lastSnapshot.lastError
        val error = resultErrorLocked(response)
        if (error == InputInjectionError.None) {
            started = true
            lastSnapshot = snapshotLocked().copy(
                state = InputInjectionState.Running,
                lastError = InputInjectionError.None,
            )
            Log.i(
                TAG,
                "DIRECT_SHELL_INPUT_SESSION_RUNNING generation=$generationId shizuku_input_producer=false",
            )
        }
        error
    }

    override fun injectKey(event: AndroidKeyInjectionEvent): Int = synchronized(lock) {
        eventResultLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_KEY,
            DirectShellInputSessionCodec.encodeKey(generationId, event),
        )
    }

    override fun injectTouch(event: AndroidTouchInjectionEvent): Int = synchronized(lock) {
        eventResultLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_TOUCH,
            DirectShellInputSessionCodec.encodeTouch(generationId, event),
        )
    }

    override fun injectPointer(event: AndroidPointerInjectionEvent): Int = synchronized(lock) {
        eventResultLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_POINTER,
            DirectShellInputSessionCodec.encodePointer(generationId, event),
        )
    }

    override fun injectJoystick(event: AndroidJoystickInjectionEvent): Int = synchronized(lock) {
        eventResultLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_JOYSTICK,
            DirectShellInputSessionCodec.encodeJoystick(generationId, event),
        )
    }

    override fun reset(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int = synchronized(lock) {
        eventResultLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_RESET,
            DirectShellInputSessionCodec.encodeReset(generationId, scope, stateSlot, reason),
        )
    }

    override fun stop(resetAll: Boolean): Int = synchronized(lock) {
        if (closed) return@synchronized InputInjectionError.Closed.code
        val response = callLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_STOP,
            DirectShellInputSessionCodec.encodeStop(generationId, resetAll),
        ) ?: return@synchronized lastSnapshot.lastError.code
        val error = resultErrorLocked(response)
        started = false
        if (error == InputInjectionError.None) {
            lastSnapshot = snapshotLocked().copy(
                state = InputInjectionState.Stopped,
                lastError = InputInjectionError.None,
            )
            Log.i(TAG, "DIRECT_SHELL_INPUT_SESSION_STOPPED generation=$generationId")
        }
        return@synchronized error.code
    }

    override fun snapshot(): InputInjectionSnapshot = synchronized(lock) { snapshotLocked() }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            if (started) {
                eventResultLocked(
                    DirectShellProbeProtocol.Command.INPUT_SESSION_RESET,
                    DirectShellInputSessionCodec.encodeReset(
                        generationId,
                        InputResetScope.AllSlots,
                        0,
                        InputResetReason.ErrorRecovery,
                    ),
                )
                eventResultLocked(
                    DirectShellProbeProtocol.Command.INPUT_SESSION_STOP,
                    DirectShellInputSessionCodec.encodeStop(generationId, true),
                )
                started = false
            }
            if (sessionPrepared && runtimeVerified) {
                runCatching { client?.call(DirectShellProbeProtocol.Command.SHUTDOWN) }
            }
            closeClientLocked()
            Arrays.fill(secret, 0.toByte())
            closed = true
            lastSnapshot = lastSnapshot.copy(state = InputInjectionState.Closed)
        }
    }

    private fun queryCapabilitiesLocked(): InputInjectionCapabilities {
        if (closed) return unavailable(InputInjectionError.Closed)
        val response = callLocked(
            DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES,
            DirectShellInputSessionCodec.generationRequest(generationId),
        ) ?: return unavailable(lastSnapshot.lastError)
        if (response.status != DirectShellProbeProtocol.Status.OK) {
            return unavailable(
                markControlFailureLocked(response.status),
            )
        }
        val value = DirectShellInputSessionCodec.decodeCapabilities(response.payload, generationId).value
            ?: return unavailable(markMalformedLocked("capabilities"))
        return value
    }

    private fun snapshotLocked(): InputInjectionSnapshot {
        if (closed) return lastSnapshot
        val activeClient = client ?: return lastSnapshot
        val response = try {
            activeClient.call(
                DirectShellProbeProtocol.Command.INPUT_SESSION_SNAPSHOT,
                DirectShellInputSessionCodec.generationRequest(generationId),
            )
        } catch (_: IOException) {
            markServiceDiedLocked()
            return lastSnapshot
        } catch (_: DirectShellProbeProtocol.ProtocolException) {
            markServiceDiedLocked()
            return lastSnapshot
        }
        if (response.status != DirectShellProbeProtocol.Status.OK) {
            markControlFailureLocked(response.status)
            return lastSnapshot
        }
        val remote = DirectShellInputSessionCodec.decodeSnapshot(response.payload, generationId).value
            ?: run {
                markMalformedLocked("snapshot")
                return lastSnapshot
            }
        lastSnapshot = remote
        return remote
    }

    private fun eventResultLocked(command: DirectShellProbeProtocol.Command, payload: ByteArray): Int {
        if (closed) return InputInjectionError.Closed.code
        val response = callLocked(command, payload) ?: return lastSnapshot.lastError.code
        return resultErrorLocked(response).code
    }

    private fun resultErrorLocked(response: DirectShellInputSessionClient.Response): InputInjectionError {
        if (response.status != DirectShellProbeProtocol.Status.OK) return markControlFailureLocked(response.status)
        val code = DirectShellInputSessionCodec.decodeServiceResult(response.payload, generationId).value
            ?: return markMalformedLocked("service_result")
        val result = InputInjectionServiceResult.fromCode(code)
        if (result.error != InputInjectionError.None) {
            lastSnapshot = lastSnapshot.copy(lastError = result.error)
        }
        return result.error
    }

    private fun callLocked(
        command: DirectShellProbeProtocol.Command,
        payload: ByteArray,
    ): DirectShellInputSessionClient.Response? {
        val activeClient = ensureClientLocked() ?: return null
        return try {
            activeClient.call(command, payload)
        } catch (_: IOException) {
            markServiceDiedLocked()
            null
        } catch (_: DirectShellProbeProtocol.ProtocolException) {
            markServiceDiedLocked()
            null
        }
    }

    private fun ensureClientLocked(): DirectShellInputSessionClient? {
        client?.let { return it }
        if (connectionAttempted) return null
        connectionAttempted = true
        val connected = try {
            DirectShellInputSessionClient.connectLoopback(port, secret)
        } catch (_: IOException) {
            markServiceUnavailableLocked()
            return null
        }
        try {
            val ping = connected.call(DirectShellProbeProtocol.Command.PING)
            val runtime = connected.call(DirectShellProbeProtocol.Command.GET_RUNTIME_INFO)
            if (ping.status != DirectShellProbeProtocol.Status.OK ||
                runtime.status != DirectShellProbeProtocol.Status.OK ||
                !runtimeIsShell(runtime.payload)
            ) {
                connected.close()
                markServiceUnavailableLocked()
                return null
            }
            client = connected
            runtimeVerified = true
            return connected
        } catch (_: IOException) {
            runCatching { connected.close() }
            markServiceUnavailableLocked()
            return null
        } catch (_: DirectShellProbeProtocol.ProtocolException) {
            runCatching { connected.close() }
            markServiceUnavailableLocked()
            return null
        }
    }

    private fun runtimeIsShell(payload: ByteArray): Boolean {
        val facts = payload.toString(Charsets.UTF_8).lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        return facts["uid"] == Process.SHELL_UID.toString() &&
            facts["uid_kind"] == "SHELL" &&
            facts["package_name"] == SHELL_PACKAGE &&
            facts["op_package_name"] == SHELL_PACKAGE &&
            facts["attribution_uid"] == Process.SHELL_UID.toString() &&
            facts["attribution_package"] == SHELL_PACKAGE
    }

    private fun unavailable(error: InputInjectionError): InputInjectionCapabilities {
        lastSnapshot = unavailableInputInjectionSnapshot(error)
        return InputInjectionCapabilities(lastError = error)
    }

    private fun markControlFailureLocked(status: DirectShellProbeProtocol.Status): InputInjectionError {
        val error = if (status == DirectShellProbeProtocol.Status.BAD_STATE) {
            InputInjectionError.RemoteFailure
        } else {
            InputInjectionError.ServiceUnavailable
        }
        lastSnapshot = unavailableInputInjectionSnapshot(error)
        return error
    }

    private fun markMalformedLocked(detail: String?): InputInjectionError {
        Log.w(
            TAG,
            "DIRECT_SHELL_INPUT_SESSION_PROTOCOL_REJECTED generation=$generationId detail=${detail ?: "unknown"}",
        )
        lastSnapshot = unavailableInputInjectionSnapshot(InputInjectionError.RemoteFailure)
        return InputInjectionError.RemoteFailure
    }

    private fun markServiceUnavailableLocked() {
        lastSnapshot = unavailableInputInjectionSnapshot(InputInjectionError.ServiceUnavailable)
    }

    private fun markServiceDiedLocked() {
        closeClientLocked()
        lastSnapshot = unavailableInputInjectionSnapshot(InputInjectionError.ServiceDied)
        if (!deathReported) {
            deathReported = true
            onServiceDied()
        }
    }

    private fun closeClientLocked() {
        runCatching { client?.close() }
        client = null
    }

    private companion object {
        const val TAG = "WarpnectDirectShellInput"
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
