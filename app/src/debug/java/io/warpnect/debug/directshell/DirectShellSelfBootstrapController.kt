package io.warpnect.debug.directshell

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.warpnect.debug.input.InputSessionTargetActivity
import io.warpnect.debug.input.InputSessionTargetProbeState
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.min

/**
 * Purpose-limited DEBUG controller for the self-ADB feasibility experiment.
 *
 * It never accepts an arbitrary shell command, path, or process class from a caller. The only
 * commands are identity inspection, installed-artifact verification, and a launch of the known
 * Warpnect probe server.
 */
internal class DirectShellSelfBootstrapController(context: Context) : AutoCloseable {
    data class Result(
        val stage: String,
        val classification: String,
        val detail: String = "",
    ) {
        val successful: Boolean get() = classification.startsWith("PASS_")
    }

    internal data class DirectShellLaunchedServer(
        val launch: DirectShellSelfBootstrapContract.Launch,
        val runtimeInfo: Map<String, String>,
    )

    internal data class DirectShellHostRuntimeServers(
        val audio: DirectShellLaunchedServer,
        val input: DirectShellLaunchedServer,
        val video: DirectShellLaunchedServer,
        val connectionPort: Int,
    )

    internal data class DirectShellHostRuntimeLaunch(
        val result: Result,
        val servers: DirectShellHostRuntimeServers? = null,
    ) {
        val successful: Boolean get() = result.successful && servers != null
    }

    internal data class DirectShellFeatureServerLaunch(
        val result: Result,
        val server: DirectShellLaunchedServer? = null,
    ) {
        val successful: Boolean get() = result.successful && server != null
    }

    private class NestedStageFailure(
        val result: Result,
    ) : IllegalStateException(result.classification)

    private class DirectShellLaunchFailure(
        cause: Throwable,
        val startupDiagnostic: String,
    ) : IOException("directshell_app_process_start_failed", cause)

    private class DirectShellSecretProvisionFailure(
        cause: Throwable,
    ) : IOException("directshell_secret_provision_failed", cause)

    private class WirelessDebuggingDisabledException : IOException("wireless_debugging_disabled")

    private val applicationContext = context.applicationContext
    private val keyStore = DirectShellSelfAdbKeyStore(applicationContext)
    private var connectionManager: DirectShellSelfAdbConnectionManager? = null
    private var verifiedArtifact: DirectShellSelfBootstrapContract.InstalledArtifact? = null
    private var activeRuntime: DirectShellLaunchedServer? = null
    private var connectionHost: String? = null
    private var connectionPort: Int? = null

    fun pair(pairingPortText: String, pairingCodeText: String): Result = guarded("PAIR") {
        requireModernWirelessDebugging()
        val pairingPort = DirectShellSelfBootstrapContract.pairingPort(pairingPortText)
        val pairingCode = DirectShellSelfBootstrapContract.pairingCode(pairingCodeText)
        ensureManager().pair(DirectShellSelfBootstrapContract.LOOPBACK_HOST, pairingPort, pairingCode)
        Result("PAIR", "PASS_PAIRING_COMPLETED", "host=127.0.0.1 pairing_port=$pairingPort")
    }

    fun connect(connectionPortText: String): Result = guarded("CONNECT") {
        requireModernWirelessDebugging()
        val port = DirectShellSelfBootstrapContract.connectionPort(connectionPortText)
        connectEndpoint(DirectShellSelfBootstrapContract.LOOPBACK_HOST, port)
    }

    /**
     * Pairs and exercises a complete minimal bootstrap after notification RemoteInput supplies
     * only the six-digit code. Both ADB service ports are discovered through DNS-SD.
     */
    fun pairAndBootstrapDiscovered(pairingCodeText: String): Result = guarded("SELF_BOOTSTRAP") {
        requireModernWirelessDebugging()
        val pairingCode = DirectShellSelfBootstrapContract.pairingCode(pairingCodeText)
        trace("DISCOVER_PAIRING")
        val pairingEndpoint = DirectShellSelfAdbEndpointDiscovery.discoverPairing(applicationContext)
        disconnectTransport()
        trace("PAIR")
        ensureManager().pair(pairingEndpoint.host, pairingEndpoint.port, pairingCode)
        keyStore.rememberTrustedConnectionServiceName(pairingEndpoint.serviceName)
        Log.i(
            TAG,
            "DIRECT_SHELL_SELF_ADB_DISCOVERY event=trusted_service_recorded " +
                "service=${pairingEndpoint.serviceName}",
        )
        disconnectTransport()

        val connectionEndpoint = discoverAndConnectConnectionEndpoint("SELF_BOOTSTRAP")
        trace("SHELL_IDENTITY")
        inspectShellIdentity().requireSuccess()
        trace("ARTIFACT_VERIFY")
        verifyInstalledArtifact().requireSuccess()
        trace("APP_PROCESS_LAUNCH")
        launchAndAuthenticate().requireSuccess()
        trace("AUTHENTICATED_SHUTDOWN")
        shutdown().requireSuccess()
        Result(
            "SELF_BOOTSTRAP",
            "PASS_PAIR_CONNECT_LAUNCH_HMAC_SHUTDOWN",
            "pairing_port=${pairingEndpoint.port} connection_port=${connectionEndpoint.port} " +
                "shell_uid=2000 artifact=installed_base_apk",
        )
    }

    /** Reuses an already paired per-install ADB host key without requesting another pairing code. */
    fun bootstrapPreviouslyAuthorized(): Result = guarded("SELF_BOOTSTRAP") {
        requireModernWirelessDebugging()
        val connectionEndpoint = discoverAndConnectConnectionEndpoint("SELF_BOOTSTRAP")
        trace("SHELL_IDENTITY")
        inspectShellIdentity().requireSuccess()
        trace("ARTIFACT_VERIFY")
        verifyInstalledArtifact().requireSuccess()
        trace("APP_PROCESS_LAUNCH")
        launchAndAuthenticate().requireSuccess()
        trace("AUTHENTICATED_SHUTDOWN")
        shutdown().requireSuccess()
        Result(
            "SELF_BOOTSTRAP",
            "PASS_REUSED_AUTHORIZATION_CONNECT_LAUNCH_HMAC_SHUTDOWN",
            "connection_port=${connectionEndpoint.port} shell_uid=2000 artifact=installed_base_apk",
        )
    }

    /**
     * Runs one quiet, app-initiated Input capability smoke test through a self-bootstrapped
     * DirectShell server. This stays a DEBUG feasibility probe, not Host capability selection.
     */
    fun bootstrapPreviouslyAuthorizedInputSmoke(): Result = guarded("INPUT_SMOKE") {
        requireModernWirelessDebugging()
        val connectionEndpoint = discoverAndConnectConnectionEndpoint("INPUT_SMOKE")
        trace("SHELL_IDENTITY")
        inspectShellIdentity().requireSuccess()
        trace("ARTIFACT_VERIFY")
        verifyInstalledArtifact().requireSuccess()
        trace("APP_PROCESS_INPUT_LAUNCH")
        val runtime = launchAndAuthenticateInternal(DirectShellSelfBootstrapContract.ServerRole.InputSmoke)
        activeRuntime = runtime
        var shutdownComplete = false
        try {
            trace("INPUT_SMOKE")
            val detail = exerciseInputSmoke(runtime)
            trace("AUTHENTICATED_SHUTDOWN")
            stopRuntime(runtime)
            activeRuntime = null
            shutdownComplete = true
            Result(
                "INPUT_SMOKE",
                "PASS_SELF_BOOTSTRAPPED_DIRECTSHELL_INPUT",
                "connection_port=${connectionEndpoint.port} shell_uid=2000 $detail",
            )
        } finally {
            if (!shutdownComplete && activeRuntime === runtime) {
                runCatching { stopRuntime(runtime) }
                activeRuntime = null
            }
        }
    }

    /**
     * Acquires the three already-qualified DirectShell feature servers through the app's own
     * Wireless Debugging connection. The returned leases intentionally outlive this controller;
     * their authenticated loopback clients, not the ADB connection, own feature control.
     */
    internal fun bootstrapPreviouslyAuthorizedHostRuntime(): DirectShellHostRuntimeLaunch {
        val launched = mutableListOf<DirectShellLaunchedServer>()
        return try {
            requireModernWirelessDebugging()
            val endpoint = discoverAndConnectConnectionEndpoint("HOST_RUNTIME")
            trace("HOST_RUNTIME_SHELL_IDENTITY")
            inspectShellIdentity().requireSuccess()
            trace("HOST_RUNTIME_ARTIFACT_VERIFY")
            verifyInstalledArtifact().requireSuccess()

            trace("HOST_RUNTIME_AUDIO_LAUNCH")
            val audio = launchAndAuthenticateInternal(
                DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing,
                DirectShellSelfBootstrapContract.HOST_RUNTIME_IDLE_TIMEOUT_MILLIS,
            )
            launched += audio
            trace("HOST_RUNTIME_INPUT_LAUNCH")
            val input = launchAndAuthenticateInternal(
                DirectShellSelfBootstrapContract.ServerRole.InputSession,
                DirectShellSelfBootstrapContract.HOST_RUNTIME_IDLE_TIMEOUT_MILLIS,
            )
            launched += input
            trace("HOST_RUNTIME_VIDEO_LAUNCH")
            val video = launchAndAuthenticateInternal(
                DirectShellSelfBootstrapContract.ServerRole.VideoCapture,
                DirectShellSelfBootstrapContract.HOST_RUNTIME_IDLE_TIMEOUT_MILLIS,
            )
            launched += video
            disconnectTransport()
            DirectShellHostRuntimeLaunch(
                result = Result(
                    "HOST_RUNTIME",
                    "PASS_SELF_BOOTSTRAPPED_DIRECTSHELL_FEATURE_SERVERS",
                    "connection_port=${endpoint.port} shell_uid=2000 " +
                        "audio_generation=${audio.launch.generation} " +
                        "input_generation=${input.launch.generation} " +
                        "video_generation=${video.launch.generation}",
                ),
                servers = DirectShellHostRuntimeServers(audio, input, video, endpoint.port),
            )
        } catch (error: Throwable) {
            launched.asReversed().forEach { server -> runCatching { stopRuntime(server) } }
            disconnectTransport()
            val result = Result("HOST_RUNTIME", classify(error), safeDetail(error))
            Log.w(
                TAG,
                "DIRECT_SHELL_SELF_BOOTSTRAP stage=HOST_RUNTIME result=${result.classification} " +
                    "detail=${result.detail}",
                error,
            )
            DirectShellHostRuntimeLaunch(result)
        }
    }

    /** Launches exactly one fixed DirectShell feature server through app-originated local ADB. */
    internal fun bootstrapPreviouslyAuthorizedFeatureServer(
        serverRole: DirectShellSelfBootstrapContract.ServerRole,
        keepAdbConnectedForNextFeature: Boolean = false,
    ): DirectShellFeatureServerLaunch {
        require(
            serverRole in setOf(
                DirectShellSelfBootstrapContract.ServerRole.PcmSharedRing,
                DirectShellSelfBootstrapContract.ServerRole.InputSession,
                DirectShellSelfBootstrapContract.ServerRole.VideoCapture,
            ),
        ) { "invalid_feature_server_role" }
        val stagePrefix = "FEATURE_${serverRole.name.uppercase()}"
        var launched: DirectShellLaunchedServer? = null
        return try {
            traced("${stagePrefix}_WIRELESS_DEBUGGING") { requireModernWirelessDebugging() }
            val port = if (keepAdbConnectedForNextFeature && connectionManager?.isConnected == true) {
                traced("${stagePrefix}_REUSE_CONNECTION") { checkNotNull(connectionPort) }
            } else {
                discoverAndConnectConnectionEndpoint(stagePrefix).port
            }
            traced("${stagePrefix}_SHELL_IDENTITY") { inspectShellIdentity().requireSuccess() }
            traced("${stagePrefix}_ARTIFACT_VERIFY") { verifyInstalledArtifact().requireSuccess() }
            launched = traced("${stagePrefix}_APP_PROCESS_LAUNCH") {
                launchAndAuthenticateInternal(
                    serverRole,
                    DirectShellSelfBootstrapContract.HOST_RUNTIME_IDLE_TIMEOUT_MILLIS,
                )
            }
            if (!keepAdbConnectedForNextFeature) disconnectTransport()
            DirectShellFeatureServerLaunch(
                Result(
                    stagePrefix,
                    "PASS_SELF_BOOTSTRAPPED_DIRECTSHELL_FEATURE_SERVER",
                    "role=${serverRole.name} connection_port=$port " +
                        "generation=${launched.launch.generation} shell_uid=2000",
                ),
                launched,
            )
        } catch (error: Throwable) {
            launched?.let { server -> runCatching { stopRuntime(server) } }
            disconnectTransport()
            val result = Result(stagePrefix, classify(error), safeDetail(error))
            Log.w(
                TAG,
                "DIRECT_SHELL_SELF_BOOTSTRAP stage=${result.stage} result=${result.classification} " +
                    "detail=${result.detail}",
                error,
            )
            DirectShellFeatureServerLaunch(result)
        }
    }

    fun inspectShellIdentity(): Result = guarded("SHELL_IDENTITY") {
        val output = execute(DirectShellSelfBootstrapContract.shellIdentityCommand())
        val compact = compact(output)
        check(output.contains("uid=2000(shell)")) { "shell_uid_not_observed" }
        Result("SHELL_IDENTITY", "PASS_SHELL_UID_2000", compact)
    }

    fun verifyInstalledArtifact(): Result = guarded("ARTIFACT_VERIFY") {
        val artifact = localInstalledArtifact()
        val remoteHash = DirectShellSelfBootstrapContract.parseChecksumOutput(
            execute(DirectShellSelfBootstrapContract.checksumCommand(artifact)),
        )
        check(remoteHash == artifact.sha256) { "artifact_sha256_mismatch" }
        verifiedArtifact = artifact
        Result(
            "ARTIFACT_VERIFY",
            "PASS_SIGNED_APK_VERIFIED",
            "source=installed_base_apk sha256=${artifact.sha256}",
        )
    }

    fun launchAndAuthenticate(): Result = guarded("LAUNCH") {
        val runtime = launchAndAuthenticateInternal()
        activeRuntime = runtime
        disconnectTransport()
        Result(
            "LAUNCH",
            "PASS_APP_PROCESS_HMAC_CONTROL",
            "generation=${runtime.launch.generation} port=${runtime.launch.port} " +
                "uid=${runtime.runtimeInfo["uid"] ?: "unknown"} " +
                "uid_kind=${runtime.runtimeInfo["uid_kind"] ?: "unknown"}",
        )
    }

    fun shutdown(): Result = guarded("SHUTDOWN") {
        val runtime = activeRuntime ?: throw IllegalStateException("no_active_directshell_runtime")
        stopRuntime(runtime)
        activeRuntime = null
        Result("SHUTDOWN", "PASS_AUTHENTICATED_SHUTDOWN", "generation=${runtime.launch.generation}")
    }

    /** Runs two explicit generations without re-pairing and rejects the first secret against B. */
    fun verifyGenerationIsolation(): Result = guarded("GENERATION") {
        check(activeRuntime == null) { "active_runtime_must_be_shutdown" }
        check(connectionPort != null) { "adb_connection_port_unknown" }
        val first = launchAndAuthenticateInternal()
        activeRuntime = first
        val staleSecret = first.launch.secret.clone()
        try {
            stopRuntime(first)
            activeRuntime = null
            disconnectTransport()

            connect(connectionPort.toString()).requireSuccess()
            verifyInstalledArtifact().requireSuccess()
            val second = launchAndAuthenticateInternal()
            activeRuntime = second
            try {
                val staleRejected = DirectShellProbeClient.verifyUnauthenticatedRequestRejected(
                    second.launch.port,
                    staleSecret,
                )
                check(staleRejected == DirectShellProbeClient.NegativeAuthResult.REJECTED) {
                    "stale_generation_secret_was_not_rejected"
                }
                stopRuntime(second)
                activeRuntime = null
                disconnectTransport()
                Result(
                    "GENERATION",
                    "PASS_REUSABLE_AUTHORIZATION_FRESH_GENERATION",
                    listOf(
                        "generation_a=${first.launch.generation}",
                        "generation_b=${second.launch.generation}",
                        "stale_secret=REJECTED",
                    ).joinToString(" "),
                )
            } catch (error: Throwable) {
                activeRuntime = second
                throw error
            }
        } finally {
            staleSecret.fill(0)
        }
    }

    fun disconnectAdb(): Result = guarded("DISCONNECT") {
        disconnectTransport()
        Result("DISCONNECT", "PASS_LOCAL_ADB_DISCONNECTED")
    }

    override fun close() {
        runCatching {
            activeRuntime?.let(::stopRuntime)
        }
        activeRuntime?.launch?.secret?.fill(0)
        activeRuntime = null
        releaseConnectionManager()
    }

    private fun launchAndAuthenticateInternal(
        serverRole: DirectShellSelfBootstrapContract.ServerRole =
            DirectShellSelfBootstrapContract.ServerRole.Control,
        idleTimeoutMillis: Long? = null,
    ): DirectShellLaunchedServer {
        check(activeRuntime == null) { "directshell_runtime_already_active" }
        val artifact = verifiedArtifact ?: throw IllegalStateException("artifact_not_verified")
        val launch = if (idleTimeoutMillis == null) {
            DirectShellSelfBootstrapContract.newLaunch(artifact, serverRole)
        } else {
            DirectShellSelfBootstrapContract.newLaunch(artifact, serverRole, idleTimeoutMillis)
        }
        var secretProvisioned = false
        var launchAttempted = false
        var shellProcessId: Int? = null
        var launched = false
        try {
            traced("${serverRole.name.uppercase()}_SECRET_PROVISION", launch.generation) {
                provisionLaunchSecret(launch)
            }
            secretProvisioned = true
            launchAttempted = true
            shellProcessId = traced("${serverRole.name.uppercase()}_WRAPPER_START", launch.generation) {
                DirectShellSelfBootstrapContract.parseLaunchProcessId(executeLaunch(launch.command))
            }
            val preliminary = traced("${serverRole.name.uppercase()}_HMAC_READY", launch.generation) {
                awaitAuthenticatedRuntime(launch)
            }
            traced("${serverRole.name.uppercase()}_NEGATIVE_AUTH", launch.generation) {
                val negative = DirectShellProbeClient.verifyUnauthenticatedRequestRejected(launch.port, launch.secret)
                check(negative == DirectShellProbeClient.NegativeAuthResult.REJECTED) {
                    "unauthenticated_control_accepted"
                }
            }
            val final = traced("${serverRole.name.uppercase()}_FINAL_IDENTITY", launch.generation) {
                authenticatedRuntime(launch).also { identity ->
                    check(identity["uid"] == "2000" && identity["uid_kind"] == "SHELL") {
                        "runtime_not_shell_uid"
                    }
                }
            }
            launched = true
            return DirectShellLaunchedServer(launch, preliminary + final)
        } catch (error: Throwable) {
            if (!secretProvisioned) {
                runCatching { execute(DirectShellSelfBootstrapContract.cleanupSecretFileCommand(launch)) }
                launch.secret.fill(0)
                throw DirectShellSecretProvisionFailure(error)
            }
            val diagnostic = if (launchAttempted) {
                captureStartupDiagnostic(launch, shellProcessId)
            } else {
                "launch_command_not_issued"
            }
            if (launchAttempted) {
                runCatching { stopRuntime(DirectShellLaunchedServer(launch, emptyMap())) }
            }
            runCatching { execute(DirectShellSelfBootstrapContract.cleanupStartupLogCommand(launch)) }
            runCatching { execute(DirectShellSelfBootstrapContract.cleanupSecretFileCommand(launch)) }
            launch.secret.fill(0)
            throw DirectShellLaunchFailure(error, diagnostic)
        } finally {
            if (launched) {
                runCatching { execute(DirectShellSelfBootstrapContract.cleanupStartupLogCommand(launch)) }
                runCatching { execute(DirectShellSelfBootstrapContract.cleanupSecretFileCommand(launch)) }
            }
        }
    }

    /** Writes exactly one per-launch secret over TLS ADB stream data, never in a shell argument. */
    private fun provisionLaunchSecret(launch: DirectShellSelfBootstrapContract.Launch) {
        val manager = connectionManager?.takeIf { it.isConnected }
            ?: throw IllegalStateException("local_adb_not_connected")
        manager.openStream("shell:${DirectShellSelfBootstrapContract.secretProvisionCommand(launch)}").use { stream ->
            stream.write(launch.secret, 0, launch.secret.size)
            stream.flush()
            check(readBoundedOutput(stream).trim() == "secret_file=READY") { "secret_provision_invalid" }
        }
    }

    private fun captureStartupDiagnostic(
        launch: DirectShellSelfBootstrapContract.Launch,
        shellProcessId: Int?,
    ): String = try {
        val raw = execute(DirectShellSelfBootstrapContract.startupDiagnosticCommand(launch, shellProcessId))
        compact(raw)
    } catch (error: Throwable) {
        "startup_diagnostic_unavailable_${error.javaClass.simpleName}"
    }

    private fun awaitAuthenticatedRuntime(launch: DirectShellSelfBootstrapContract.Launch): Map<String, String> {
        val deadline = SystemClock.elapsedRealtime() + SERVER_START_TIMEOUT_MILLIS
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return authenticatedRuntime(launch)
            } catch (error: IOException) {
                lastError = error
                SystemClock.sleep(SERVER_START_POLL_MILLIS)
            } catch (error: DirectShellProbeProtocol.ProtocolException) {
                lastError = error
                SystemClock.sleep(SERVER_START_POLL_MILLIS)
            }
        }
        throw IOException("directshell_server_start_timeout_${lastError?.javaClass?.simpleName ?: "unknown"}")
    }

    private fun authenticatedRuntime(launch: DirectShellSelfBootstrapContract.Launch): Map<String, String> =
        DirectShellProbeClient.connectLoopback(launch.port, launch.secret).use { client ->
            val result = client.runAuthenticatedSequence(false)
            parseRuntimeInfo(result.runtimeInfo)
        }

    private fun stopRuntime(runtime: DirectShellLaunchedServer) {
        try {
            DirectShellProbeClient.connectLoopback(runtime.launch.port, runtime.launch.secret).use { client ->
                val result = client.runAuthenticatedSequence(true)
                check(result.shutdownRequestId > 0L) { "shutdown_not_acknowledged" }
            }
            awaitServerExit(runtime.launch.port)
        } finally {
            runtime.launch.secret.fill(0)
        }
    }

    private fun exerciseInputSmoke(runtime: DirectShellLaunchedServer): String {
        val generation = InputSessionTargetProbeState.begin()
        applicationContext.startActivity(
            Intent(applicationContext, InputSessionTargetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        check(InputSessionTargetProbeState.awaitReady(generation, INPUT_TARGET_READY_TIMEOUT_MILLIS)) {
            "input_target_not_ready"
        }
        val target = requireNotNull(
            InputSessionTargetProbeState.awaitTouchTarget(generation, INPUT_TARGET_READY_TIMEOUT_MILLIS),
        ) { "input_target_bounds_unavailable" }
        DirectShellInputProbeClient.connectLoopback(runtime.launch.port, runtime.launch.secret).use { client ->
            requireInputSmokeSuccess("prepare", client.call(DirectShellProbeProtocol.Command.INPUT_PREPARE))
            requireInputSmokeSuccess(
                "key",
                client.call(DirectShellProbeProtocol.Command.INPUT_KEY_SEQUENCE, "count=1"),
            )
            requireInputSmokeSuccess(
                "touch",
                client.call(
                    DirectShellProbeProtocol.Command.INPUT_TOUCH_GESTURE,
                    DirectShellInputProbeRequest.touchGesturePayload(1, target.x, target.y),
                ),
            )
            requireInputSmokeSuccess("reset", client.call(DirectShellProbeProtocol.Command.INPUT_RESET))
        }
        val missing = InputSessionTargetProbeState.awaitEvents(
            generation,
            INPUT_SMOKE_EXPECTED_TARGET_EVENTS,
            INPUT_TARGET_EVENT_TIMEOUT_MILLIS,
        )
        check(missing.isEmpty()) { "input_target_events_missing_${missing.sorted().joinToString("_")}" }
        return "target_events=${INPUT_SMOKE_EXPECTED_TARGET_EVENTS.sorted().joinToString(",")}"
    }

    private fun requireInputSmokeSuccess(stage: String, response: DirectShellInputProbeClient.Response) {
        check(response.status == DirectShellProbeProtocol.Status.OK) {
            "input_${stage}_${response.status.name.lowercase()}"
        }
    }

    private fun awaitServerExit(port: Int) {
        val deadline = SystemClock.elapsedRealtime() + SERVER_STOP_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                DirectShellProbeClient.connectLoopback(port, ByteArray(DirectShellProbeProtocol.SECRET_BYTES)).use { }
                SystemClock.sleep(SERVER_START_POLL_MILLIS)
            } catch (_: IOException) {
                return
            }
        }
        throw IOException("directshell_server_stop_timeout")
    }

    private fun execute(command: String): String {
        val manager = connectionManager?.takeIf { it.isConnected }
            ?: throw IllegalStateException("local_adb_not_connected")
        return manager.openStream("shell:$command").use(::readBoundedOutput)
    }

    /**
     * The intentionally detached shell server can retain the launch stream after its parent has
     * printed $!. Waiting for EOF would therefore turn a healthy running server into a timeout.
     * Consume only the closed, one-line PID acknowledgement and close this control stream.
     */
    private fun executeLaunch(command: String): String {
        val manager = connectionManager?.takeIf { it.isConnected }
            ?: throw IllegalStateException("local_adb_not_connected")
        return manager.openStream("shell:$command").use(::readLaunchOutput)
    }

    private fun readLaunchOutput(stream: AdbStream): String {
        val deadline = SystemClock.elapsedRealtime() + LAUNCH_OUTPUT_TIMEOUT_MILLIS
        val buffer = ByteArray(256)
        val output = StringBuilder()
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = try {
                stream.available()
            } catch (_: IOException) {
                break
            }
            if (available == 0) {
                SystemClock.sleep(LAUNCH_OUTPUT_POLL_MILLIS)
                continue
            }
            val count = stream.read(buffer, 0, min(buffer.size, available))
            if (count < 0) break
            check(output.length + count <= MAX_SHELL_OUTPUT_BYTES) { "shell_output_exceeds_bound" }
            output.append(buffer.decodeToString(0, count))
            if (DirectShellSelfBootstrapContract.hasCompleteLaunchResponse(output.toString())) {
                return output.toString()
            }
        }
        throw IOException("directshell_launch_pid_timeout")
    }

    private fun readBoundedOutput(stream: AdbStream): String {
        val buffer = ByteArray(1_024)
        val output = StringBuilder()
        stream.openInputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.length + count <= MAX_SHELL_OUTPUT_BYTES) { "shell_output_exceeds_bound" }
                output.append(buffer.decodeToString(0, count))
            }
        }
        return output.toString()
    }

    private fun localInstalledArtifact(): DirectShellSelfBootstrapContract.InstalledArtifact {
        val sourcePath = applicationContext.applicationInfo.sourceDir
        val bytes = File(sourcePath).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            buffer.fill(0)
            digest.digest()
        }
        return try {
            DirectShellSelfBootstrapContract.installedArtifact(sourcePath, bytes.toHex())
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureManager(): DirectShellSelfAdbConnectionManager =
        connectionManager ?: DirectShellSelfAdbConnectionManager(keyStore).also { connectionManager = it }

    /** Disconnect transport between probe stages without destroying the paired host-key object. */
    private fun disconnectTransport() {
        connectionManager?.let { manager -> runCatching { manager.disconnect() } }
    }

    private fun releaseConnectionManager() {
        connectionManager?.let { manager -> runCatching { manager.close() } }
        connectionManager = null
    }

    private fun connectEndpoint(host: String, port: Int): Result {
        requireModernWirelessDebugging()
        require(host.isNotBlank()) { "invalid_adb_host" }
        disconnectTransport()
        val manager = ensureManager()
        val connected = manager.connect(host, port)
        check(connected || manager.isConnected) { "adb_connection_not_established" }
        connectionHost = host
        connectionPort = port
        return Result("CONNECT", "PASS_LOCAL_ADB_CONNECTED", "host=$host connection_port=$port")
    }

    /**
     * Wireless Debugging can briefly advertise a stale TLS-connect record after it is toggled.
     * Refresh once only when that exact endpoint refuses the TCP connection; all other failures
     * remain terminal so authorization and transport errors are never masked as discovery churn.
     */
    private fun discoverAndConnectConnectionEndpoint(
        tracePrefix: String,
    ): DirectShellSelfAdbEndpointDiscovery.Endpoint {
        val trustedServiceName = traced("${tracePrefix}_TRUSTED_IDENTITY") {
            keyStore.trustedConnectionServiceName()
                ?: throw DirectShellSelfAdbEndpointDiscovery.DiscoveryException(
                    DirectShellSelfAdbEndpointDiscovery.Failure.CONNECTION_SERVICE_IDENTITY_REQUIRED,
                )
        }
        val initial = traced("${tracePrefix}_DISCOVER_CONNECTION") {
            DirectShellSelfAdbEndpointDiscovery.discoverConnection(
                context = applicationContext,
                trustedServiceName = trustedServiceName,
            )
        }
        try {
            traced("${tracePrefix}_CONNECT") { connectEndpoint(initial.host, initial.port).requireSuccess() }
            return initial
        } catch (error: IOException) {
            if (!hasConnectionRefusedCause(error)) throw error
            Log.i(
                TAG,
                "DIRECT_SHELL_SELF_ADB_RECOVERY event=refresh_stale_connection_endpoint " +
                    "failed_port=${initial.port}",
            )
            releaseConnectionManager()
            val replacement = traced("${tracePrefix}_REDISCOVER_CONNECTION") {
                DirectShellSelfAdbEndpointDiscovery.discoverConnection(
                    context = applicationContext,
                    trustedServiceName = trustedServiceName,
                    excludedEndpoint = initial,
                )
            }
            traced("${tracePrefix}_RECONNECT") { connectEndpoint(replacement.host, replacement.port).requireSuccess() }
            return replacement
        }
    }

    private fun requireModernWirelessDebugging() {
        check(Build.VERSION.SDK_INT >= MIN_WIRELESS_DEBUGGING_API) { "wireless_debugging_requires_api_30" }
        if (wirelessDebuggingIsExplicitlyDisabled()) throw WirelessDebuggingDisabledException()
    }

    /**
     * A missing or unreadable setting is deliberately treated as unknown, not disabled. The
     * subsequent bounded DNS-SD discovery remains authoritative in that case.
     */
    private fun wirelessDebuggingIsExplicitlyDisabled(): Boolean = runCatching {
        Settings.Global.getString(applicationContext.contentResolver, ADB_WIFI_ENABLED_SETTING) == "0"
    }.getOrNull() == true

    private fun guarded(stage: String, operation: () -> Result): Result = try {
        operation()
    } catch (error: NestedStageFailure) {
        Result(
            stage,
            error.result.classification,
            "failed_stage=${error.result.stage}; ${error.result.detail}",
        )
    } catch (error: Throwable) {
        val result = Result(stage, classify(error), safeDetail(error))
        Log.w(
            TAG,
            "DIRECT_SHELL_SELF_BOOTSTRAP stage=$stage result=${result.classification} detail=${result.detail}",
            error,
        )
        result
    }

    private fun classify(error: Throwable): String = when (error) {
        is WirelessDebuggingDisabledException -> "FAIL_WIRELESS_DEBUGGING_DISABLED"
        is DirectShellSecretProvisionFailure -> "FAIL_DIRECTSHELL_SECRET_PROVISION"
        is DirectShellLaunchFailure -> "FAIL_DIRECTSHELL_APP_PROCESS_START"
        is DirectShellSelfAdbEndpointDiscovery.DiscoveryException ->
            "FAIL_WIRELESS_DEBUGGING_${error.failure.name}"
        is AdbPairingRequiredException -> "FAIL_WIRELESS_DEBUGGING_PAIRING_REQUIRED"
        is AdbAuthenticationFailedException -> "FAIL_WIRELESS_DEBUGGING_AUTHORIZATION_REJECTED"
        is ConnectException -> "FAIL_WIRELESS_DEBUGGING_DISABLED_OR_ENDPOINT_UNREACHABLE"
        is SocketTimeoutException -> "FAIL_LOCAL_ADB_TIMEOUT"
        is IllegalArgumentException -> "FAIL_INVALID_ARGUMENT"
        is IllegalStateException -> "FAIL_${error.message?.uppercaseAscii()?.take(96) ?: "BAD_STATE"}"
        is IOException -> "FAIL_LOCAL_ADB_IO_${error.javaClass.simpleName.uppercaseAscii()}"
        else -> "FAIL_${error.javaClass.simpleName.uppercaseAscii()}"
    }

    private fun safeDetail(error: Throwable): String = when (error) {
        is DirectShellSecretProvisionFailure ->
            "secret_provision_failed_${error.cause?.javaClass?.simpleName ?: "unknown"}"
        is DirectShellLaunchFailure -> compact(
            "${error.cause?.message ?: error.message}; ${error.startupDiagnostic}",
        )
        else -> compact(error.message ?: error.javaClass.simpleName)
    }

    private fun Result.requireSuccess() {
        if (!successful) throw NestedStageFailure(this)
    }

    private fun trace(stage: String) {
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=$stage result=BEGIN")
    }

    private inline fun <T> traced(stage: String, generation: Long? = null, operation: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val identity = generation?.let { " generation=$it" }.orEmpty()
        Log.i(TAG, "DIRECT_SHELL_PREPARATION stage=$stage result=BEGIN$identity")
        return try {
            operation().also {
                Log.i(
                    TAG,
                    "DIRECT_SHELL_PREPARATION stage=$stage result=PASS$identity " +
                        "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "DIRECT_SHELL_PREPARATION stage=$stage result=FAIL$identity " +
                    "error=${error.javaClass.simpleName} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}",
            )
            throw error
        }
    }

    private fun parseRuntimeInfo(payload: String): Map<String, String> = buildMap {
        payload.lineSequence().forEach { line ->
            val separator = line.indexOf('=')
            if (separator > 0) put(line.substring(0, separator), line.substring(separator + 1))
        }
    }

    private fun compact(value: String): String = value
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(MAX_DIAGNOSTIC_CHARS)

    private fun String.uppercaseAscii(): String = uppercase().replace(Regex("[^A-Z0-9_]+"), "_")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "WarpnectDirectShellBootstrap"
        const val MIN_WIRELESS_DEBUGGING_API = 30
        const val ADB_WIFI_ENABLED_SETTING = "adb_wifi_enabled"
        const val SERVER_START_TIMEOUT_MILLIS = 6_000L
        const val SERVER_STOP_TIMEOUT_MILLIS = 3_000L
        const val SERVER_START_POLL_MILLIS = 75L

        // Must outlive the bounded wrapper-start handshake in DirectShellSelfBootstrapContract.
        const val LAUNCH_OUTPUT_TIMEOUT_MILLIS = 8_000L
        const val LAUNCH_OUTPUT_POLL_MILLIS = 25L
        const val MAX_SHELL_OUTPUT_BYTES = 16 * 1_024
        const val MAX_DIAGNOSTIC_CHARS = 1_000
        const val INPUT_TARGET_READY_TIMEOUT_MILLIS = 2_000L
        const val INPUT_TARGET_EVENT_TIMEOUT_MILLIS = 2_000L
        val INPUT_SMOKE_EXPECTED_TARGET_EVENTS = setOf(
            "KEY_DOWN",
            "KEY_UP",
            "TOUCH_DOWN",
            "TOUCH_MOVE",
            "TOUCH_UP",
        )
    }
}

/** The ADB client wraps a socket refusal in an IOException on current Android releases. */
internal fun hasConnectionRefusedCause(error: Throwable): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is ConnectException) return true
        current = current.cause
    }
    return false
}
