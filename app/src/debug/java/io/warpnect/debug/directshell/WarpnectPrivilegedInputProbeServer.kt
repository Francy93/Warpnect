package io.warpnect.debug.directshell

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * DEBUG-only app_process feasibility server for direct shell InputManager injection.
 *
 * It deliberately reuses Warpnect's production InputManager resolver, Android event factory, and
 * input state tracker. It has no Shizuku, Session, SCL, or Input Payload V1 dependency.
 */
object WarpnectPrivilegedInputProbeServer {
    private const val TAG = "WarpnectDirectShellInput"

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = try {
            val arguments = ServerArguments.parse(args)
            ProbeServer(arguments).run()
            0
        } catch (error: IllegalArgumentException) {
            log("START_FAILURE result=INVALID_ARGUMENT message=${safe(error.message)}")
            2
        } catch (error: Throwable) {
            log(
                "START_FAILURE result=UNEXPECTED type=${error.javaClass.simpleName} " +
                    "message=${safe(error.message)}",
            )
            3
        }
        log("PROCESS_EXIT code=$exitCode")
        exitProcess(exitCode)
    }

    private class ProbeServer(
        private val arguments: ServerArguments,
    ) {
        private val running = AtomicBoolean(true)
        private val endpointReleased = AtomicBoolean(false)
        private val lastAuthenticatedActivityMillis = AtomicLong(SystemClock.elapsedRealtime())
        private val ownerLease = DirectShellOwnerLease()
        private val engine = DirectShellInputProbeEngine(arguments.displayId)
        private val sessionBridge = arguments.generationId.takeIf { it > 0L }?.let(::DirectShellInputSessionBridge)
        private val shellContext = DirectShellShellAudioContext.create()
        private var listener: FileDescriptor? = null
        private var idleWatchdog: Thread? = null

        fun run() {
            check(Process.myUid() == Process.SHELL_UID) { "IDENTITY_UNAVAILABLE_NOT_SHELL_UID" }
            listener = DirectShellProbeIpv4Socket.bindListener(arguments.port)
            log(
                "START result=READY pid=${Process.myPid()} uid=${Process.myUid()} gid=${Os.getgid()} " +
                    "uid_kind=SHELL api=${DirectShellRuntimeApi.sdkInt()} " +
                    "api_source=${DirectShellRuntimeApi.source()} selinux_context=${selinuxContext()} " +
                    "endpoint=${endpoint()} transport=LOOPBACK_TCP context=${shellContext.classification} " +
                    "generation=${arguments.generationId}",
            )
            log("RUNTIME_IDENTITY ${compact(shellContext.toPayload())}")
            if (arguments.startupLocalKey) {
                val prepared = engine.prepare()
                log("LOCAL_PROBE command=INPUT_PREPARE result=${prepared.classification} ${prepared.payload}")
                val injected = if (prepared.success) engine.injectKeySequence(1) else prepared
                log("LOCAL_PROBE command=INPUT_KEY_SEQUENCE result=${injected.classification} ${injected.payload}")
            }
            startIdleWatchdog()
            try {
                while (running.get()) {
                    try {
                        val client = DirectShellProbeIpv4Socket.accept(listener, CONTROL_ACCEPT_TIMEOUT_MILLIS)
                            ?: continue
                        handleControl(client)
                    } catch (error: IOException) {
                        if (running.get()) {
                            log("CONTROL_ACCEPT_FAILURE type=${error.javaClass.simpleName}")
                        }
                    }
                }
            } finally {
                shutdownEngines()
                ownerLease.close()
                releaseEndpoint()
                joinWatchdog()
            }
        }

        private fun handleControl(client: DirectShellProbeIpv4Socket) {
            val order = DirectShellProbeProtocol.RequestOrder()
            var ownerTransferred = false
            try {
                val input = client.input()
                val output = client.output()
                while (running.get()) {
                    // A session transport is intentionally quiet between input events. Poll before
                    // parsing so the socket's bounded per-frame deadline never turns quiet time
                    // into a terminal ServiceDied result in the normal process.
                    if (!client.awaitReadable(CONTROL_IDLE_POLL_TIMEOUT_MILLIS)) continue
                    val request = try {
                        DirectShellProbeProtocol.readRequest(input, arguments.secret)
                    } catch (error: DirectShellProbeProtocol.ProtocolException) {
                        log("AUTH result=${error.kind.name} endpoint=${endpoint()}")
                        return
                    } catch (error: EOFException) {
                        return
                    }
                    if (!order.accept(request.requestId)) {
                        writeResponse(
                            output,
                            request.requestId,
                            DirectShellProbeProtocol.Status.OUT_OF_ORDER,
                            "request_id_not_strictly_increasing".encodeToByteArray(),
                        )
                        log("REQUEST id=${request.requestId} command=${request.command.name} result=OUT_OF_ORDER")
                        continue
                    }
                    lastAuthenticatedActivityMillis.set(SystemClock.elapsedRealtime())
                    log("AUTH result=SUCCESS request_id=${request.requestId}")
                    if (request.command == DirectShellProbeProtocol.Command.HOST_OWNER_ATTACH) {
                        ownerTransferred = ownerLease.attach(
                            client,
                            request,
                            output,
                            arguments.secret,
                            arguments.generationId,
                        ) { requestShutdown("owner_connection_lost") }
                        log(
                            "HOST_OWNER result=${if (ownerTransferred) "ATTACHED" else "REJECTED"} " +
                                "generation=${arguments.generationId}",
                        )
                        return
                    }
                    if (dispatch(request, output)) {
                        requestShutdown("authenticated_shutdown")
                        return
                    }
                }
            } finally {
                if (!ownerTransferred) runCatching { client.close() }
            }
        }

        private fun dispatch(request: DirectShellProbeProtocol.Request, output: DataOutputStream): Boolean {
            val response = if (request.command.isSessionCommand()) {
                sessionBridge?.dispatch(request.command, request.payload)
                    ?: DirectShellInputSessionBridge.Reply.badState(
                        "INPUT_SESSION_UNAVAILABLE",
                        "generation_not_configured".encodeToByteArray(),
                    )
            } else {
                legacyReply(request)
            }
            return try {
                writeResponse(
                    output,
                    request.requestId,
                    response.status,
                    response.payload,
                )
                log(
                    "REQUEST id=${request.requestId} command=${request.command.name} " +
                        "result=${response.classification} status=${response.status.name} " +
                        "payload_bytes=${response.payload.size}",
                )
                request.command == DirectShellProbeProtocol.Command.SHUTDOWN
            } catch (error: IOException) {
                log("RESPONSE_FAILURE command=${request.command.name} type=${error.javaClass.simpleName}")
                false
            }
        }

        private fun legacyReply(request: DirectShellProbeProtocol.Request): DirectShellInputSessionBridge.Reply {
            val response = when (request.command) {
                DirectShellProbeProtocol.Command.PING -> DirectShellInputProbeEngine.Result.success("PONG", "PONG")
                DirectShellProbeProtocol.Command.GET_RUNTIME_INFO -> DirectShellInputProbeEngine.Result.success(
                    "RUNTIME_INFO",
                    runtimePayload(),
                )
                DirectShellProbeProtocol.Command.INPUT_PREPARE -> engine.prepare()
                DirectShellProbeProtocol.Command.INPUT_KEY_SEQUENCE -> withCount(
                    request.payload,
                    engine::injectKeySequence,
                )
                DirectShellProbeProtocol.Command.INPUT_TOUCH_GESTURE -> withTouchGesture(request.payload)
                DirectShellProbeProtocol.Command.INPUT_POINTER -> engine.injectPointer()
                DirectShellProbeProtocol.Command.INPUT_JOYSTICK -> engine.injectJoystick()
                DirectShellProbeProtocol.Command.INPUT_RESET -> engine.resetAfterPressedKey()
                DirectShellProbeProtocol.Command.GET_INPUT_DIAGNOSTICS -> engine.diagnostics()
                DirectShellProbeProtocol.Command.SHUTDOWN -> {
                    shutdownEngines()
                    DirectShellInputProbeEngine.Result.success("INPUT_STOPPED", "INPUT_STOPPED")
                }
                else -> DirectShellInputProbeEngine.Result.failure(
                    "UNSUPPORTED_COMMAND",
                    "command=${request.command.name}",
                )
            }
            return if (response.success) {
                DirectShellInputSessionBridge.Reply.ok(
                    response.classification,
                    "result=${response.classification} ${response.payload}".toByteArray(StandardCharsets.UTF_8),
                )
            } else {
                DirectShellInputSessionBridge.Reply.badState(
                    response.classification,
                    "result=${response.classification} ${response.payload}".toByteArray(StandardCharsets.UTF_8),
                )
            }
        }

        private fun withCount(
            payload: ByteArray,
            operation: (Int) -> DirectShellInputProbeEngine.Result,
        ): DirectShellInputProbeEngine.Result {
            val count = DirectShellInputProbeRequest.parseCount(payload)
                ?: return DirectShellInputProbeEngine.Result.failure("INVALID_COUNT", "count_payload_rejected")
            return operation(count)
        }

        private fun withTouchGesture(payload: ByteArray): DirectShellInputProbeEngine.Result {
            val gesture = DirectShellInputProbeRequest.parseTouchGesture(payload)
                ?: return DirectShellInputProbeEngine.Result.failure(
                    "INVALID_TOUCH_GESTURE",
                    "touch_gesture_payload_rejected",
                )
            return engine.injectTouchGesture(gesture)
        }

        private fun writeResponse(
            output: DataOutputStream,
            requestId: Long,
            status: DirectShellProbeProtocol.Status,
            payload: ByteArray,
        ) {
            DirectShellProbeProtocol.writeResponse(
                output,
                requestId,
                status,
                payload,
                arguments.secret,
            )
        }

        private fun DirectShellProbeProtocol.Command.isSessionCommand(): Boolean = when (this) {
            DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES,
            DirectShellProbeProtocol.Command.INPUT_SESSION_PREPARE,
            DirectShellProbeProtocol.Command.INPUT_SESSION_START,
            DirectShellProbeProtocol.Command.INPUT_SESSION_KEY,
            DirectShellProbeProtocol.Command.INPUT_SESSION_TOUCH,
            DirectShellProbeProtocol.Command.INPUT_SESSION_POINTER,
            DirectShellProbeProtocol.Command.INPUT_SESSION_JOYSTICK,
            DirectShellProbeProtocol.Command.INPUT_SESSION_RESET,
            DirectShellProbeProtocol.Command.INPUT_SESSION_STOP,
            DirectShellProbeProtocol.Command.INPUT_SESSION_SNAPSHOT,
            -> true
            else -> false
        }

        private fun runtimePayload(): String = listOf(
            "pid=${Process.myPid()}",
            "uid=${Process.myUid()}",
            "gid=${Os.getgid()}",
            "uid_kind=${if (Process.myUid() == Process.SHELL_UID) "SHELL" else "OTHER"}",
            "api=${DirectShellRuntimeApi.sdkInt()}",
            "api_source=${DirectShellRuntimeApi.source()}",
            "selinux_context=${selinuxContext()}",
            "java_class_path=${safe(System.getProperty("java.class.path"))}",
            "class_loader=${javaClass.classLoader?.javaClass?.name ?: "bootstrap"}",
            shellContext.toPayload(),
            engine.diagnostics().payload,
        ).joinToString("\n")

        private fun startIdleWatchdog() {
            idleWatchdog = Thread(
                {
                    while (running.get()) {
                        try {
                            Thread.sleep(250L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                        if (DirectShellOwnerLease.bootstrapExpired(
                                SystemClock.elapsedRealtime(),
                                lastAuthenticatedActivityMillis.get(),
                                arguments.idleTimeoutMillis,
                                ownerLease.attached(),
                            )
                        ) {
                            requestShutdown("bootstrap_owner_timeout")
                            return@Thread
                        }
                    }
                },
                "WarpnectDirectShellInputIdleWatchdog",
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun requestShutdown(reason: String) {
            if (!running.compareAndSet(true, false)) return
            shutdownEngines()
            ownerLease.close()
            log("SHUTDOWN result=REQUESTED reason=$reason")
            releaseEndpoint()
        }

        private fun shutdownEngines() {
            engine.shutdown()
            sessionBridge?.shutdown()
        }

        private fun releaseEndpoint() {
            if (!endpointReleased.compareAndSet(false, true)) return
            val activeListener = listener
            listener = null
            if (activeListener != null) {
                runCatching { DirectShellProbeIpv4Socket.closeListener(activeListener) }
                    .onFailure { log("ENDPOINT_RELEASE_FAILED type=${it.javaClass.simpleName}") }
            }
            log("ENDPOINT_RELEASED transport=LOOPBACK_TCP endpoint=${endpoint()}")
        }

        private fun joinWatchdog() {
            val watchdog = idleWatchdog ?: return
            if (watchdog === Thread.currentThread()) return
            watchdog.interrupt()
            runCatching { watchdog.join(500L) }
        }

        private fun endpoint() = "127.0.0.1:${arguments.port}"
    }

    private data class ServerArguments(
        val port: Int,
        val secret: ByteArray,
        val idleTimeoutMillis: Long,
        val displayId: Int,
        val generationId: Long,
        val startupLocalKey: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): ServerArguments {
                var port: Int? = null
                var encodedSecret: String? = null
                var secretFile: String? = null
                var idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS
                var displayId = 0
                var generationId = 0L
                var startupLocalKey = false
                var index = 0
                while (index < args.size) {
                    when (val argument = args[index++]) {
                        "--port" -> port = args.valueAt(index++, argument).toIntOrNull()
                        "--secret-base64" -> encodedSecret = args.valueAt(index++, argument)
                        "--secret-file" -> secretFile = args.valueAt(index++, argument)
                        "--idle-timeout-ms" -> idleTimeoutMillis = args.valueAt(index++, argument).toLongOrNull()
                            ?: throw IllegalArgumentException("invalid_idle_timeout")
                        "--display-id" -> displayId = args.valueAt(index++, argument).toIntOrNull()
                            ?: throw IllegalArgumentException("invalid_display_id")
                        "--generation" -> generationId = args.valueAt(index++, argument).toLongOrNull()
                            ?: throw IllegalArgumentException("invalid_generation")
                        "--startup-local-key" -> startupLocalKey = true
                        else -> throw IllegalArgumentException("unknown_argument_$argument")
                    }
                }
                require(port != null && port in 1024..65535) { "invalid_port" }
                require(
                    idleTimeoutMillis in MIN_IDLE_TIMEOUT_MILLIS..MAX_IDLE_TIMEOUT_MILLIS,
                ) { "invalid_idle_timeout" }
                require(displayId in 0..64) { "invalid_display_id" }
                require(generationId >= 0L) { "invalid_generation" }
                require((encodedSecret == null) != (secretFile == null)) { "invalid_secret_source" }
                val rawSecret = if (encodedSecret != null) {
                    decodeSecret(encodedSecret)
                } else {
                    DirectShellSecretFile.readAndDelete(requireNotNull(secretFile))
                }
                return ServerArguments(
                    port = requireNotNull(port),
                    secret = try {
                        DirectShellProbeProtocol.requireSecret(rawSecret)
                    } finally {
                        rawSecret.fill(0)
                    },
                    idleTimeoutMillis = idleTimeoutMillis,
                    displayId = displayId,
                    generationId = generationId,
                    startupLocalKey = startupLocalKey,
                )
            }

            private fun Array<String>.valueAt(index: Int, name: String): String =
                getOrNull(index) ?: throw IllegalArgumentException("missing_value_$name")

            private fun decodeSecret(value: String): ByteArray = try {
                Base64.getUrlDecoder().decode(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("invalid_secret")
            }
        }
    }

    private fun selinuxContext(): String = runCatching {
        File("/proc/self/attr/current").readText().trim().ifEmpty { "unavailable" }
    }.getOrElse { "unavailable" }

    private fun compact(value: String): String = value.replace('\n', ';').replace('\r', '_').take(3_000)

    private fun safe(value: String?): String = value
        ?.replace('\n', '_')
        ?.replace('\r', '_')
        ?.replace('\u0000', '_')
        ?.take(160)
        ?: "none"

    private fun log(message: String) {
        Log.i(TAG, message)
        println("WARPNECT_DIRECTSHELL_INPUT $message")
        System.out.flush()
    }

    private const val CONTROL_ACCEPT_TIMEOUT_MILLIS = 250
    private const val CONTROL_IDLE_POLL_TIMEOUT_MILLIS = 250
    private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L
    private const val MIN_IDLE_TIMEOUT_MILLIS = 3_000L
    private const val MAX_IDLE_TIMEOUT_MILLIS = 900_000L
}
