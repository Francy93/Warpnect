package io.warpnect.debug.directshell

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import org.json.JSONObject

/** DEBUG/lab entry point. Typed Input and mirror control only; no encoded media or arbitrary operations. */
object WarpnectRootHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        var exit = 0
        var secret: ByteArray? = null
        try {
            require(args.size == 5) { "invalid_root_arguments" }
            val generation = args[0].toLong().also { require(it > 0) }
            val port = args[1].toInt().also { require(it in 1024..65535) }
            val hash = args[2].also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            val provenance = RootBootstrapProvenance.valueOf(args[4])
            val expires = args[3].toLong().also {
                require(it > SystemClock.elapsedRealtime() && it <= SystemClock.elapsedRealtime() + 120_000)
            }
            val path = "/data/user/0/io.warpnect/files/root-lab/${generation.toString(16)}.secret"
            if (Process.myUid() != 0 || Os.getgid() != 0) {
                diagnostic(
                    "direct_root_bootstrap_result result=FAIL_ROOT_UID_REQUIRED " +
                        "uid=${Process.myUid()} gid=${Os.getgid()}",
                )
                error("root_uid_required")
            }
            secret = DirectShellSecretFile.readRootAndDelete(path)
            val apk = System.getProperty("java.class.path").orEmpty()
            require(apk.matches(Regex("/data/app/[A-Za-z0-9_~./=\\-]+/base\\.apk"))) { "root_artifact_path_invalid" }
            require(rootArtifactHash(File(apk)) == hash) { "root_artifact_hash_invalid" }
            val identity = JSONObject().apply {
                put("pid", Process.myPid())
                put("uid", Process.myUid())
                put("gid", Os.getgid())
                put("selinux", File("/proc/self/attr/current").readText().trim().trimEnd('\u0000'))
                put("api", DirectShellRuntimeApi.sdkInt())
                put("generation", generation)
                put("version", DirectRootRuntime.PROTOCOL_VERSION)
                put("implementation", DirectRootRuntime.IMPLEMENTATION)
                put("sha256", hash)
                put("provenance", provenance.name)
                put("capabilities", "INPUT,VIDEO,SYSTEM_AUDIO")
            }.toString()
            serve(generation, port, expires, secret, identity)
        } catch (error: Exception) {
            diagnostic("direct_root_bootstrap_result result=FAIL type=${error.javaClass.simpleName}")
            exit = 2
        } finally {
            secret?.fill(0)
            diagnostic("direct_root_shutdown process_exit=$exit")
        }
        exitProcess(exit)
    }

    private fun serve(generation: Long, port: Int, expires: Long, secret: ByteArray, identity: String) {
        val inputBridge = DirectShellInputSessionBridge(
            generation,
            InputInjectionBackend.DirectRoot,
            PrivilegedUidKind.Root,
        )
        val videoBridge = RootVideoBridge(generation, secret)
        val audioBridge = RootAudioBridge(generation, secret)
        val releaseHook = Thread({
            audioBridge.close()
            videoBridge.close()
            inputBridge.shutdown()
        }, "WarpnectRootRelease")
        Runtime.getRuntime().addShutdownHook(releaseHook)
        val listener = DirectShellProbeIpv4Socket.bindListener(port)
        val running = AtomicBoolean(true)
        val owned = AtomicBoolean(false)
        val lastActivity = AtomicLong(SystemClock.elapsedRealtime())
        val slots = Semaphore(2)
        val handlers = mutableListOf<Thread>()
        diagnostic("direct_root_helper_identity $identity")
        diagnostic("direct_root_bootstrap_result result=READY generation=$generation endpoint=127.0.0.1:$port")
        try {
            while (running.get() && SystemClock.elapsedRealtime() < expires &&
                SystemClock.elapsedRealtime() - lastActivity.get() < 10_000
            ) {
                val socket = DirectShellProbeIpv4Socket.accept(listener, 200) ?: continue
                if (owned.get() || !slots.tryAcquire()) {
                    socket.close()
                    diagnostic("direct_root_authenticated result=OWNER_CONFLICT")
                    continue
                }
                val action = Runnable {
                    var owner = false
                    val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.rootHex()
                    val lease = RootControlLease(generation, expires, nonce)
                    try {
                        socket.use {
                            val input = socket.input()
                            val output = socket.output()
                            DirectShellProbeProtocol.writeResponse(
                                output,
                                1,
                                DirectShellProbeProtocol.Status.OK,
                                "$generation|$nonce".toByteArray(),
                                secret,
                            )
                            val hello = DirectShellProbeProtocol.readRequest(input, secret)
                            require(
                                hello.command == DirectShellProbeProtocol.Command.GET_RUNTIME_INFO &&
                                    lease.hello(
                                        hello.requestId,
                                        String(hello.payload, StandardCharsets.UTF_8),
                                        SystemClock.elapsedRealtime(),
                                    ),
                            ) { "root_hello_rejected" }
                            require(owned.compareAndSet(false, true)) { "root_ownership_conflict" }
                            owner = true
                            lastActivity.set(SystemClock.elapsedRealtime())
                            DirectShellProbeProtocol.writeResponse(
                                output,
                                hello.requestId,
                                DirectShellProbeProtocol.Status.OK,
                                identity.toByteArray(),
                                secret,
                            )
                            diagnostic("direct_root_authenticated result=PASS generation=$generation")
                            while (running.get() && SystemClock.elapsedRealtime() < expires) {
                                if (!socket.awaitReadable(200)) continue
                                val request = DirectShellProbeProtocol.readRequest(input, secret)
                                require(
                                    if (RootControlLease.audioCommand(request.command)) {
                                        lease.acceptAudio(
                                            request.requestId,
                                            request.payload,
                                            SystemClock.elapsedRealtime(),
                                        )
                                    } else if (RootControlLease.videoCommand(request.command)) {
                                        lease.acceptVideo(
                                            request.requestId,
                                            request.payload,
                                            SystemClock.elapsedRealtime(),
                                        )
                                    } else if (RootControlLease.inputCommand(request.command)) {
                                        lease.acceptInput(
                                            request.requestId,
                                            request.payload,
                                            SystemClock.elapsedRealtime(),
                                        )
                                    } else {
                                        lease.accept(
                                            request.requestId,
                                            String(request.payload, StandardCharsets.UTF_8),
                                            SystemClock.elapsedRealtime(),
                                        )
                                    },
                                ) {
                                    "root_generation_order_or_expiry_rejected"
                                }
                                lastActivity.set(SystemClock.elapsedRealtime())
                                val supported = RootControlLease.supported(request.command)
                                val inputReply = if (RootControlLease.inputCommand(request.command)) {
                                    inputBridge.dispatch(request.command, request.payload)
                                } else {
                                    null
                                }
                                val videoReply = if (RootControlLease.videoCommand(request.command)) {
                                    videoBridge.dispatch(request.command, request.payload)
                                } else {
                                    null
                                }
                                val audioReply = if (RootControlLease.audioCommand(request.command)) {
                                    audioBridge.dispatch(request.command, request.payload)
                                } else {
                                    null
                                }
                                val payload = when (request.command) {
                                    DirectShellProbeProtocol.Command.GET_RUNTIME_INFO -> identity
                                    DirectShellProbeProtocol.Command.ROOT_CAPABILITIES ->
                                        "video=IMPLEMENTED;system_audio=IMPLEMENTED;" +
                                            "input=IMPLEMENTED;remediation=NONE"
                                    DirectShellProbeProtocol.Command.PING -> "PONG"
                                    DirectShellProbeProtocol.Command.SHUTDOWN -> "SHUTDOWN_ACCEPTED"
                                    else -> "UNSUPPORTED"
                                }
                                DirectShellProbeProtocol.writeResponse(
                                    output,
                                    request.requestId,
                                    inputReply?.status ?: if (supported) {
                                        DirectShellProbeProtocol.Status.OK
                                    } else {
                                        DirectShellProbeProtocol.Status.UNSUPPORTED
                                    },
                                    audioReply ?: videoReply ?: inputReply?.payload ?: payload.toByteArray(),
                                    secret,
                                )
                                diagnostic(
                                    "direct_root_request id=${request.requestId} " +
                                        "command=${request.command} supported=$supported " +
                                        "result=${inputReply?.classification ?: "CONTROL"}",
                                )
                                if (request.command == DirectShellProbeProtocol.Command.SHUTDOWN) break
                            }
                        }
                    } catch (error: Exception) {
                        diagnostic(
                            "direct_root_authenticated result=REJECT_OR_LOSS " +
                                "type=${error.javaClass.simpleName} owner=$owner",
                        )
                    } finally {
                        lease.close()
                        runCatching { socket.close() }
                        slots.release()
                        if (owner) running.set(false)
                    }
                }
                val handler = Thread(action, "WarpnectRootControl")
                handlers.removeAll { !it.isAlive }
                handlers += handler
                handler.start()
            }
        } finally {
            running.set(false)
            DirectShellProbeIpv4Socket.closeListener(listener)
            handlers.forEach { it.join(4_000) }
            audioBridge.close()
            videoBridge.close()
            inputBridge.shutdown()
            Runtime.getRuntime().removeShutdownHook(releaseHook)
            diagnostic("direct_root_shutdown endpoint_released=true generation=$generation")
        }
    }

    private fun diagnostic(message: String) {
        Log.i("WarpnectDirectRoot", message)
        println(message)
    }
}

internal fun ByteArray.rootHex(): String = joinToString("") { "%02x".format(it) }

internal fun rootArtifactHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().rootHex()
}
