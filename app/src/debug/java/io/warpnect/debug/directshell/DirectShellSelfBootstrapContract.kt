package io.warpnect.debug.directshell

import java.security.SecureRandom

/**
 * Closed command and artifact contract for the DEBUG-only self-ADB bootstrap experiment.
 *
 * The normal app never exposes a generic shell or file API. Every command originates from this
 * object, references the installed Warpnect APK, and launches only the existing probe entry point.
 */
internal object DirectShellSelfBootstrapContract {
    const val LOOPBACK_HOST = "127.0.0.1"
    const val PACKAGE_NAME = "io.warpnect"
    const val SERVER_MAIN_CLASS = "io.warpnect.debug.directshell.WarpnectPrivilegedServer"
    const val INPUT_SMOKE_SERVER_MAIN_CLASS =
        "io.warpnect.debug.directshell.WarpnectPrivilegedInputProbeServer"
    const val PCM_SHARED_RING_SERVER_MAIN_CLASS =
        "io.warpnect.debug.directshell.WarpnectPrivilegedPcmSharedRingProbeServer"
    const val VIDEO_CAPTURE_SERVER_MAIN_CLASS =
        "io.warpnect.debug.directshell.WarpnectPrivilegedVideoCaptureProbeServer"

    private const val MIN_PORT = 1_024
    private const val MAX_PORT = 65_535
    private const val SERVER_IDLE_TIMEOUT_MILLIS = 60_000L
    const val HOST_RUNTIME_IDLE_TIMEOUT_MILLIS = 900_000L
    private const val STARTUP_LOG_PREFIX = "/data/local/tmp/warpnect-directshell-bootstrap-"
    private const val STARTUP_LOG_SUFFIX = ".log"
    private const val SECRET_FILE_PREFIX = "/data/local/tmp/warpnect-directshell-secret-"
    private const val SECRET_FILE_SUFFIX = ".bin"
    private const val MAX_STARTUP_LOG_BYTES = 4 * 1_024

    // Android's shell launcher can be queued behind an earlier app_process launch. Keep the
    // readiness check bounded, but allow the observed shell-side startup variance before
    // declaring the generation unavailable.
    private const val WRAPPER_START_ATTEMPTS = 80
    private const val WRAPPER_START_POLL_SECONDS = "0.05"
    private val random = SecureRandom()
    private val packageApkPath = Regex("/data/app/[A-Za-z0-9_~./=\\-]+/base\\.apk")
    private val checksum = Regex("^([0-9A-Fa-f]{64})(?:\\s+.*)?$")
    private val shellProcessId = Regex("^pid=([1-9][0-9]{0,8})$")

    data class InstalledArtifact(
        val sourcePath: String,
        val sha256: String,
    )

    /** The app may launch only these fixed DEBUG probe entry points. */
    enum class ServerRole(
        val mainClass: String,
    ) {
        Control(SERVER_MAIN_CLASS),
        InputSmoke(INPUT_SMOKE_SERVER_MAIN_CLASS),
        InputSession(INPUT_SMOKE_SERVER_MAIN_CLASS),
        PcmSharedRing(PCM_SHARED_RING_SERVER_MAIN_CLASS),
        VideoCapture(VIDEO_CAPTURE_SERVER_MAIN_CLASS),
    }

    data class Launch(
        val serverRole: ServerRole,
        val generation: Long,
        val port: Int,
        val secret: ByteArray,
        val secretFilePath: String,
        val startupLogPath: String,
        val command: String,
    )

    fun pairingPort(value: String): Int = parsePort(value, "pairing_port")

    fun connectionPort(value: String): Int = parsePort(value, "connection_port")

    fun pairingCode(value: String): String {
        val normalized = value.trim()
        require(normalized.matches(Regex("[0-9]{6}"))) { "invalid_pairing_code" }
        return normalized
    }

    fun installedArtifact(sourcePath: String, sha256: String): InstalledArtifact {
        require(packageApkPath.matches(sourcePath)) { "invalid_installed_artifact_path" }
        val normalizedHash = sha256.lowercase()
        require(normalizedHash.matches(Regex("[0-9a-f]{64}"))) { "invalid_artifact_sha256" }
        return InstalledArtifact(sourcePath, normalizedHash)
    }

    fun parseChecksumOutput(output: String): String {
        val firstLine = output.lineSequence().firstOrNull()?.trim().orEmpty()
        return checksum.matchEntire(firstLine)?.groupValues?.get(1)?.lowercase()
            ?: throw IllegalArgumentException("invalid_remote_sha256")
    }

    fun checksumCommand(artifact: InstalledArtifact): String = "sha256sum -- ${shellQuote(artifact.sourcePath)}"

    fun newLaunch(
        artifact: InstalledArtifact,
        serverRole: ServerRole = ServerRole.Control,
        idleTimeoutMillis: Long = SERVER_IDLE_TIMEOUT_MILLIS,
    ): Launch {
        require(idleTimeoutMillis in 3_000L..HOST_RUNTIME_IDLE_TIMEOUT_MILLIS) {
            "invalid_idle_timeout"
        }
        val secret = ByteArray(DirectShellProbeProtocol.SECRET_BYTES)
        random.nextBytes(secret)
        val generation = positiveRandomLong()
        val port = 36_000 + random.nextInt(10_000)
        val processName = "warpnect-${serverRole.name.lowercase()}-${generation.toString(16)}"
        val startupLogPath = "$STARTUP_LOG_PREFIX${generation.toString(16)}$STARTUP_LOG_SUFFIX"
        val secretFilePath = "$SECRET_FILE_PREFIX${generation.toString(16)}$SECRET_FILE_SUFFIX"
        val serverArguments = buildList {
            add("--port")
            add(port.toString())
            add("--secret-file")
            add(secretFilePath)
            when (serverRole) {
                ServerRole.Control -> {
                    add("--declared-package")
                    add(PACKAGE_NAME)
                }
                ServerRole.InputSmoke,
                ServerRole.InputSession,
                -> {
                    add("--generation")
                    add(generation.toString())
                    add("--display-id")
                    add("0")
                }
                ServerRole.PcmSharedRing,
                ServerRole.VideoCapture,
                -> {
                    add("--generation")
                    add(generation.toString())
                }
            }
            add("--idle-timeout-ms")
            add(idleTimeoutMillis.toString())
        }
        val serverCommand = listOf(
            "app_process",
            "/system/bin",
            "--nice-name=$processName",
            serverRole.mainClass,
        ).plus(serverArguments).joinToString(" ")
        val wrapperCommand = "echo WRAPPER_START pid=\$\$; $serverCommand; status=\$?; " +
            "echo WRAPPER_EXIT_status=\$status; exit \$status"
        val startupLog = shellQuote(startupLogPath)
        val launchHandshake = listOf(
            "pid=\$!; attempt=0;",
            "while [ \$attempt -lt $WRAPPER_START_ATTEMPTS ]; do",
            "if grep -q '^WRAPPER_START ' $startupLog; then printf 'pid=%s\\n' \"\$pid\"; exit 0; fi;",
            "if ! kill -0 \"\$pid\" 2>/dev/null; then printf 'launch_failure=wrapper_exited\\n'; exit 1; fi;",
            "sleep $WRAPPER_START_POLL_SECONDS; attempt=\$((attempt + 1));",
            "done; printf 'launch_failure=wrapper_start_timeout\\n'; exit 1",
        ).joinToString(" ")
        val command = listOf(
            "rm -f -- $startupLog;",
            "CLASSPATH=${shellQuote(artifact.sourcePath)}",
            "/system/bin/nohup",
            "sh", "-c", shellQuote(wrapperCommand),
            "</dev/null", ">$startupLog", "2>&1", "&",
            launchHandshake,
        ).joinToString(" ")
        // Keep the secret in process memory only. Passing it as a shell argument causes adbd to
        // mirror it into logcat on this device, so it must travel as encrypted ADB stream data.
        return Launch(serverRole, generation, port, secret, secretFilePath, startupLogPath, command)
    }

    /**
     * The ADB service request contains only this closed command; its stdin carries the secret
     * bytes over the authenticated TLS transport. head exits after exactly one secret-sized read,
     * allowing the caller to observe a bounded completion response without exposing the bytes.
     */
    fun secretProvisionCommand(launch: Launch): String {
        val path = shellQuote(launch.secretFilePath)
        val size = DirectShellProbeProtocol.SECRET_BYTES
        return "umask 077; head -c $size > $path; " +
            "if [ \"\$(wc -c < $path)\" -eq $size ]; then chmod 600 $path; " +
            "printf 'secret_file=READY\\n'; else rm -f -- $path; " +
            "printf 'secret_file=INVALID\\n'; fi"
    }

    fun parseLaunchProcessId(output: String): Int {
        val failure = completeLaunchLines(output)
            .firstOrNull { it.startsWith("launch_failure=") }
        require(failure == null) { failure ?: "launch_failure" }
        val match = completeLaunchLines(output)
            .mapNotNull { shellProcessId.matchEntire(it) }
            .lastOrNull()
            ?: throw IllegalArgumentException("invalid_launch_process_id")
        return match.groupValues[1].toInt()
    }

    fun hasCompleteLaunchResponse(output: String): Boolean = completeLaunchLines(output)
        .any { it.startsWith("pid=") || it.startsWith("launch_failure=") }

    private fun completeLaunchLines(output: String): Sequence<String> {
        val end = output.lastIndexOf('\n')
        return if (end < 0) emptySequence() else output.substring(0, end + 1).lineSequence().map(String::trim)
    }

    /**
     * Reads only a small, generation-scoped startup log through the already authenticated ADB
     * connection. The app never exposes an arbitrary file-reading facility.
     */
    fun startupDiagnosticCommand(launch: Launch, processId: Int?): String {
        val processState = processId?.let { processId ->
            "if [ -d /proc/$processId ]; then printf 'process=RUNNING\\n'; else printf 'process=EXITED\\n'; fi;"
        }.orEmpty()
        val logPath = shellQuote(launch.startupLogPath)
        return "$processState if [ -f $logPath ]; then " +
            "printf 'startup_log_bytes='; wc -c < $logPath; " +
            "printf 'startup_log_mode='; ls -ld -- $logPath; " +
            "tail -c $MAX_STARTUP_LOG_BYTES -- $logPath; " +
            "else printf 'startup_log=MISSING\\n'; fi"
    }

    fun cleanupStartupLogCommand(launch: Launch): String = "rm -f -- ${shellQuote(launch.startupLogPath)}"

    fun cleanupSecretFileCommand(launch: Launch): String = "rm -f -- ${shellQuote(launch.secretFilePath)}"

    fun shellIdentityCommand(): String = "id"

    private fun parsePort(value: String, name: String): Int = value.toIntOrNull()
        ?.takeIf { it in MIN_PORT..MAX_PORT }
        ?: throw IllegalArgumentException("invalid_$name")

    private fun positiveRandomLong(): Long {
        var candidate = 0L
        while (candidate <= 0L) candidate = random.nextLong() and Long.MAX_VALUE
        return candidate
    }

    private fun shellQuote(value: String): String {
        require(value.isNotEmpty() && value.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "unsafe_shell_value"
        }
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
