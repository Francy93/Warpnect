package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.platform.capture.DirectRootVideoCapability
import io.warpnect.platform.capture.RootVideoControl
import io.warpnect.platform.capture.RootVideoRequest
import io.warpnect.platform.capture.RootVideoRequestCodec
import io.warpnect.platform.input.injection.DirectRootInputCapability
import io.warpnect.platform.input.injection.DirectRootInputInjectionGateway
import io.warpnect.platform.input.injection.RootInputControl
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.readiness.AndroidPersistentCapabilityEvidenceKeyFactory
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRepository
import io.warpnect.platform.privileged.readiness.SharedPreferencesPersistentCapabilityEvidenceStore
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootRuntimeRegistry
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.privileged.runtime.RootControlSession
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import io.warpnect.platform.privileged.runtime.RootRuntimeBootstrap
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** DUMP-gated DEBUG controller; the external lab harness, not this app, obtains UID0. */
class DirectRootLabReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val action = intent.getStringExtra("operation").orEmpty()
        Thread({
            try {
                DirectRootLabController.execute(context.applicationContext, action)
            } catch (error: Exception) {
                Log.i(
                    "WarpnectDirectRoot",
                    "direct_root_lab_action operation=$action result=FAIL type=${error.javaClass.simpleName}",
                )
            } finally {
                pending.finish()
            }
        }, "WarpnectRootLabController").start()
    }
}

internal object DirectRootLabController {
    private var bootstrap: LaboratoryRootBootstrap? = null
    private var runtime: DirectRootRuntime? = null
    private var inputGateway: DirectRootInputInjectionGateway? = null
    private var staleInputGateway: DirectRootInputInjectionGateway? = null
    private var heldObservation: Long? = null
    private var staleSecret: ByteArray? = null
    private var monitor: java.util.concurrent.ScheduledExecutorService? = null

    /** Only this DEBUG controller's private handoff files; never reload a previous authorization. */
    fun clearAbandonedFiles(context: Context) {
        File(context.filesDir, "root-lab").listFiles()?.filter {
            it.name == "request.json" || it.name.matches(Regex("[0-9a-f]{1,16}\\.secret"))
        }?.forEach { require(it.delete()) { "root_abandoned_file_cleanup_failed" } }
    }

    @Synchronized
    fun execute(context: Context, action: String) {
        when (action) {
            "prepare" -> {
                release(retainStale = true)
                // A process restart never reuses a private file as live authorization.
                val directory = File(context.filesDir, "root-lab").apply { mkdirs() }
                directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{1,16}\\.secret")) }
                    ?.forEach { require(it.delete()) }
                val prepared = LaboratoryRootBootstrap(context, directory)
                bootstrap = prepared
                runtime = DirectRootRuntime(prepared, DirectRootRuntimeRegistry::publish)
                DirectRootInputCapability.bindRuntime(runtime)
                DirectRootVideoCapability.bindRuntime(runtime)
                DirectRootRuntimeRegistry.publish(requireNotNull(runtime).snapshot)
                monitor = Executors.newSingleThreadScheduledExecutor().also { executor ->
                    executor.schedule({
                        synchronized(this) {
                            if (bootstrap === prepared) release(retainStale = false)
                        }
                    }, 90, TimeUnit.SECONDS)
                }
            }
            "acquire" -> {
                val acquired = requireNotNull(runtime).acquireAuthority()
                if (acquired.ready) {
                    inputGateway = DirectRootInputInjectionGateway(requireNotNull(runtime))
                    monitor?.scheduleWithFixedDelay({
                        val current = runtime
                        if (current != null && !current.checkLiveness()) {
                            synchronized(this) {
                                if (runtime === current) DirectRootVideoQualification.release()
                            }
                        }
                    }, 1, 1, TimeUnit.SECONDS)
                }
            }
            "negative-auth" -> requireNotNull(bootstrap).negativeAuth(staleSecret ?: ByteArray(32))
            "negative-generation" -> requireNotNull(bootstrap).negativeGeneration()
            "duplicate" -> requireNotNull(bootstrap).duplicateOwner()
            "ping" -> check(requireNotNull(runtime).checkLiveness())
            "qualify-input" -> DirectRootInputQualification.run(context, requireNotNull(runtime))
            "qualify-video" -> DirectRootVideoQualification.run(context, requireNotNull(runtime))
            "confirm-video" -> DirectRootVideoQualification.confirmContent(context, requireNotNull(runtime))
            "hold-video" -> DirectRootVideoQualification.hold(context, requireNotNull(runtime))
            "video-status" -> DirectRootVideoQualification.status(context, runtime)
            "hold-input" -> heldObservation = DirectRootInputQualification.hold(context, requireNotNull(inputGateway))
            "drop-input" -> {
                requireNotNull(runtime).inputConnectionLost()
                DirectRootInputQualification.observeDisconnectRelease(requireNotNull(heldObservation))
                heldObservation = null
            }
            "stale-input" -> {
                val result = requireNotNull(staleInputGateway).injectKey(
                    AndroidKeyInjectionEvent(0, 1000, 1, 29, source = 0x101, displayId = 0),
                )
                check(result == InputInjectionError.ServiceDied.code)
                staleInputGateway?.close()
                staleInputGateway = null
                Log.i("WarpnectDirectRoot", "direct_root_input_stale_gateway result=REJECTED code=$result")
            }
            "input-status" -> {
                runtime?.checkLiveness()
                val repository = PersistentCapabilityEvidenceRepository(
                    SharedPreferencesPersistentCapabilityEvidenceStore(
                        context,
                    ),
                    AndroidPersistentCapabilityEvidenceKeyFactory(shizukuRuntimeVersion = {
                        null
                    }),
                    recordedAtEpochMs = System::currentTimeMillis,
                )
                val records = repository.loadReusableEvidence().reusableRecords.filter {
                    it.provider == PrivilegedProvider.DIRECT_ROOT
                }
                val observed = DirectRootRuntimeRegistry.runtimeSnapshot()
                Log.i(
                    "WarpnectDirectRoot",
                    "direct_root_input_cache_state root_records=${records.size} runtime_ready=${observed.ready} " +
                        "state=${observed.state} input_snapshot=${inputGateway?.snapshot()}",
                )
            }
            "shutdown" -> release(retainStale = false)
            "invalidate" -> release(retainStale = true)
            "status" -> Unit
            else -> error("unsupported_lab_operation")
        }
        val observed = DirectRootRuntimeRegistry.runtimeSnapshot()
        val authority = DirectRootRuntimeRegistry.snapshot()
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_lab_action operation=$action " +
                "result=${if (action == "acquire" && !observed.ready) "FAIL" else "OK"} state=${observed.state} " +
                "generation=${observed.generation} provenance=${observed.provenance} " +
                "root_recovery_authority_state=${authority.available} actions=NONE " +
                "video=${DirectRootVideoCapability.qualification().state} " +
                "system_audio=NOT_IMPLEMENTED input=${DirectRootInputCapability.qualification().state}",
        )
    }

    private fun release(retainStale: Boolean) {
        DirectRootVideoQualification.release()
        DirectRootVideoCapability.bindRuntime(null)
        DirectRootInputCapability.bindRuntime(null)
        monitor?.shutdownNow()
        monitor = null
        if (retainStale && bootstrap != null) {
            staleInputGateway?.close()
            staleInputGateway = inputGateway
            staleSecret?.fill(0)
            staleSecret = bootstrap?.secret?.clone()
        } else if (!retainStale) {
            staleInputGateway?.close()
            staleInputGateway = null
            inputGateway?.close()
            staleSecret?.fill(0)
            staleSecret = null
        }
        runtime?.close()
        inputGateway = null
        runtime = null
        bootstrap = null
    }
}

private class LaboratoryRootBootstrap(context: Context, private val directory: File) : RootRuntimeBootstrap {
    override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
    private val random = SecureRandom()
    override val generation = (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
    override val expectedArtifactSha256 = rootArtifactHash(File(context.applicationInfo.sourceDir))
    val secret = ByteArray(32).also { random.nextBytes(it) }
    private val port = 36_000 + random.nextInt(10_000)
    private val expires = SystemClock.elapsedRealtime() + 90_000
    private val secretFile = File(directory, "${generation.toString(16)}.secret")
    private val requestFile = File(directory, "request.json")

    init {
        require(secretFile.createNewFile())
        secretFile.setReadable(false, false)
        secretFile.setWritable(false, false)
        require(secretFile.setReadable(true, true) && secretFile.setWritable(true, true))
        secretFile.outputStream().use { it.write(secret) }
        val metadata = JSONObject().apply {
            put("generation", generation)
            put("port", port)
            put("expires", expires)
            put("apk", context.applicationInfo.sourceDir)
            put("sha256", expectedArtifactSha256)
        }
        requestFile.writeText(metadata.toString())
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_bootstrap_start generation=$generation provenance=LABORATORY_EXTERNAL_ROOT",
        )
    }

    override fun acquire(): RootControlSession {
        val deadline = minOf(expires, SystemClock.elapsedRealtime() + 15_000)
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return RootLocalControl.connect(port, generation, secret)
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        throw IOException("root_helper_start_timeout")
    }

    fun negativeAuth(wrongSecret: ByteArray) {
        DirectShellProbeIpv4Socket.connect(port).use { socket ->
            DirectShellProbeProtocol.readResponse(socket.input(), secret)
            DirectShellProbeProtocol.writeRequest(
                socket.output(),
                1,
                DirectShellProbeProtocol.Command.INPUT_SESSION_KEY,
                DirectShellInputSessionCodec.generationRequest(generation),
                wrongSecret,
            )
            try {
                DirectShellProbeProtocol.readResponse(socket.input(), secret)
                error("negative_auth_was_accepted")
            } catch (_: IOException) {
                Log.i(
                    "WarpnectDirectRoot",
                    "direct_root_negative_auth result=REJECTED generation=$generation typed_input=true",
                )
            }
        }
    }

    fun duplicateOwner() {
        DirectShellProbeIpv4Socket.connect(port).use { socket ->
            try {
                DirectShellProbeProtocol.readResponse(socket.input(), secret)
                error("duplicate_owner_was_accepted")
            } catch (_: IOException) {
                Log.i("WarpnectDirectRoot", "direct_root_duplicate_owner result=REJECTED")
            }
        }
    }

    fun negativeGeneration() {
        DirectShellProbeIpv4Socket.connect(port).use { socket ->
            val challenge = DirectShellProbeProtocol.readResponse(socket.input(), secret)
            val nonce = String(challenge.payload, Charsets.UTF_8).substringAfter('|')
            DirectShellProbeProtocol.writeRequest(
                socket.output(),
                1,
                DirectShellProbeProtocol.Command.GET_RUNTIME_INFO,
                "${generation - 1}|$nonce".toByteArray(),
                secret,
            )
            try {
                DirectShellProbeProtocol.readResponse(socket.input(), secret)
                error("wrong_generation_was_accepted")
            } catch (_: IOException) {
                Log.i("WarpnectDirectRoot", "direct_root_negative_generation result=REJECTED")
            }
        }
    }

    override fun cancel() {
        secretFile.delete()
        requestFile.delete()
        secret.fill(0)
    }
}

private class RootLocalControl(
    private val socket: DirectShellProbeIpv4Socket,
    private val secret: ByteArray,
    override val identity: RootHelperIdentity,
) : RootControlSession, RootInputControl, RootVideoControl {
    private var nextId = 2L
    private var surfaceGeneration = 1L
    private var videoRequest = CaptureRequest(0, 640, 360)
    private var videoLatest = CaptureSessionSnapshot()

    private fun video(command: DirectShellProbeProtocol.Command): JSONObject {
        val bytes = RootVideoRequestCodec.encode(RootVideoRequest(identity.generation, surfaceGeneration, videoRequest))
        return RootVideoReplies.decode(request(command, bytes), identity.generation).also {
            videoLatest = RootVideoReplies.snapshot(it)
        }
    }

    override fun videoCapabilities(): CaptureCapabilities = RootVideoReplies.capabilities(
        video(DirectShellProbeProtocol.Command.VIDEO_QUALIFY),
    )

    override fun startVideo(request: CaptureRequest, target: Surface): CaptureError {
        videoRequest = request
        surfaceGeneration = DirectRootSurfaceHandoff.arm(identity, secret, request, target)
        try {
            val reply = video(DirectShellProbeProtocol.Command.VIDEO_START)
            Log.i("WarpnectDirectRoot", "direct_root_video_start_reply $reply")
            return CaptureError.fromCode(reply.getInt("error")).also {
                if (it != CaptureError.None) DirectRootSurfaceHandoff.release()
            }
        } catch (error: Exception) {
            DirectRootSurfaceHandoff.release()
            throw error
        }
    }

    override fun updateVideo(request: CaptureRequest): CaptureError {
        videoRequest = request
        return CaptureError.fromCode(video(DirectShellProbeProtocol.Command.VIDEO_UPDATE).getInt("error"))
    }

    override fun stopVideo(): CaptureError = try {
        CaptureError.fromCode(video(DirectShellProbeProtocol.Command.VIDEO_STOP).getInt("error"))
    } finally {
        DirectRootSurfaceHandoff.release()
    }

    override fun videoSnapshot(): CaptureSessionSnapshot = videoLatest

    @Synchronized
    private fun request(command: DirectShellProbeProtocol.Command, payload: ByteArray): ByteArray {
        val id = nextId++
        DirectShellProbeProtocol.writeRequest(
            socket.output(),
            id,
            command,
            payload,
            secret,
        )
        val response = DirectShellProbeProtocol.readResponse(socket.input(), secret)
        check(
            response.requestId == id && response.status == DirectShellProbeProtocol.Status.OK,
        ) { "root_response_invalid" }
        return response.payload
    }

    private fun call(command: DirectShellProbeProtocol.Command): String = String(
        request(command, identity.generation.toString().toByteArray()),
        Charsets.UTF_8,
    )

    private fun input(command: DirectShellProbeProtocol.Command, payload: ByteArray): Int =
        DirectShellInputSessionCodec.decodeServiceResult(request(command, payload), identity.generation).value
            ?: error("root_input_result_invalid")

    override fun capabilities(): InputInjectionCapabilities = (
        DirectShellInputSessionCodec.decodeCapabilities(
            request(
                DirectShellProbeProtocol.Command.INPUT_SESSION_CAPABILITIES,
                DirectShellInputSessionCodec.generationRequest(identity.generation),
            ),
            identity.generation,
        ).value ?: error("root_input_capabilities_invalid")
        ).copy(
        backend = InputInjectionBackend.DirectRoot,
        privilegedUidKind = PrivilegedUidKind.Root,
    )

    override fun prepareInput(config: InputInjectionConfig): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_PREPARE,
        DirectShellInputSessionCodec.encodePrepare(identity.generation, config),
    )
    override fun startInput(): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_START,
        DirectShellInputSessionCodec.generationRequest(identity.generation),
    )
    override fun key(event: AndroidKeyInjectionEvent): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_KEY,
        DirectShellInputSessionCodec.encodeKey(identity.generation, event),
    )
    override fun touch(event: AndroidTouchInjectionEvent): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_TOUCH,
        DirectShellInputSessionCodec.encodeTouch(identity.generation, event),
    )
    override fun pointer(event: AndroidPointerInjectionEvent): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_POINTER,
        DirectShellInputSessionCodec.encodePointer(identity.generation, event),
    )
    override fun joystick(event: AndroidJoystickInjectionEvent): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_JOYSTICK,
        DirectShellInputSessionCodec.encodeJoystick(identity.generation, event),
    )
    override fun resetInput(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_RESET,
        DirectShellInputSessionCodec.encodeReset(identity.generation, scope, stateSlot, reason),
    )
    override fun stopInput(): Int = input(
        DirectShellProbeProtocol.Command.INPUT_SESSION_STOP,
        DirectShellInputSessionCodec.encodeStop(identity.generation, true),
    )
    override fun inputSnapshot(): InputInjectionSnapshot = (
        DirectShellInputSessionCodec.decodeSnapshot(
            request(
                DirectShellProbeProtocol.Command.INPUT_SESSION_SNAPSHOT,
                DirectShellInputSessionCodec.generationRequest(identity.generation),
            ),
            identity.generation,
        ).value
            ?: error("root_input_snapshot_invalid")
        ).copy(
        backend = InputInjectionBackend.DirectRoot,
        privilegedUidKind = PrivilegedUidKind.Root,
    )

    override fun ping() {
        check(call(DirectShellProbeProtocol.Command.PING) == "PONG")
    }

    override fun shutdown() {
        check(call(DirectShellProbeProtocol.Command.SHUTDOWN) == "SHUTDOWN_ACCEPTED")
    }

    override fun close() {
        try {
            DirectRootSurfaceHandoff.release()
            socket.close()
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        fun connect(port: Int, generation: Long, secret: ByteArray): RootLocalControl {
            val socket = DirectShellProbeIpv4Socket.connect(port)
            try {
                val challenge = DirectShellProbeProtocol.readResponse(socket.input(), secret)
                val payload = String(challenge.payload, Charsets.UTF_8)
                require(challenge.requestId == 1L && challenge.status == DirectShellProbeProtocol.Status.OK)
                require(payload.matches(Regex("$generation\\|[0-9a-f]{64}"))) { "root_challenge_invalid" }
                DirectShellProbeProtocol.writeRequest(
                    socket.output(),
                    1,
                    DirectShellProbeProtocol.Command.GET_RUNTIME_INFO,
                    challenge.payload,
                    secret,
                )
                val response = DirectShellProbeProtocol.readResponse(socket.input(), secret)
                require(response.requestId == 1L && response.status == DirectShellProbeProtocol.Status.OK)
                val info = JSONObject(String(response.payload, Charsets.UTF_8))
                require(info.getString("provenance") == "LABORATORY_EXTERNAL_ROOT")
                val identity = RootHelperIdentity(
                    pid = info.getInt("pid"), uid = info.getInt("uid"), gid = info.getInt("gid"),
                    selinuxContext = info.getString("selinux"), api = info.getInt("api"),
                    generation = info.getLong("generation"), protocolVersion = info.getInt("version"),
                    implementation = info.getString("implementation"), artifactSha256 = info.getString("sha256"),
                )
                val client = RootLocalControl(socket, secret.clone(), identity)
                check(
                    client.call(DirectShellProbeProtocol.Command.ROOT_CAPABILITIES) ==
                        "video=IMPLEMENTED;system_audio=NOT_IMPLEMENTED;input=IMPLEMENTED;remediation=NONE",
                )
                Log.i("WarpnectDirectRoot", "direct_root_app_identity $info")
                return client
            } catch (error: Exception) {
                socket.close()
                throw error
            }
        }
    }
}
