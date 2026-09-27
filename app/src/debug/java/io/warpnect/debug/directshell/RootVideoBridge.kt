package io.warpnect.debug.directshell

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState
import io.warpnect.platform.capture.RootVideoRequestCodec
import io.warpnect.platform.capture.privileged.QualifiedPrivilegedDisplayCaptureApi
import org.json.JSONObject

/** Provider adapter only: mirror selection, projection, reconfiguration and release are shared. */
internal class RootVideoBridge(private val generation: Long, private val secret: ByteArray) : AutoCloseable {
    private val capture = QualifiedPrivilegedDisplayCaptureApi()
    private var provider: RootVideoProviderClient? = null
    private var surface: Surface? = null
    private var surfaceGeneration: Long? = null

    @Synchronized fun dispatch(command: DirectShellProbeProtocol.Command, bytes: ByteArray): ByteArray {
        val request = RootVideoRequestCodec.decode(bytes)
        require(request.generation == generation)
        var error = CaptureError.None
        var detail = "OK"
        try {
            when (command) {
                DirectShellProbeProtocol.Command.VIDEO_QUALIFY -> error = capture.queryCapabilities().lastError
                DirectShellProbeProtocol.Command.VIDEO_START -> {
                    check(surface == null) { "root_video_already_active" }
                    val capabilities = capture.queryCapabilities()
                    error = capabilities.lastError
                    if (capabilities.backendAvailable && error == CaptureError.None) {
                        provider = RootVideoProviderClient.open()
                        provider!!.verifyStaleRejection(request, secret)
                        surface = provider!!.surface(request, secret)
                        provider!!.verifyConsumedRejection(request, secret)
                        surfaceGeneration = request.surfaceGeneration
                        error = capture.startCapture(request.capture, surface!!)
                        if (error != CaptureError.None) close()
                    }
                }
                DirectShellProbeProtocol.Command.VIDEO_UPDATE -> {
                    require(surfaceGeneration == request.surfaceGeneration) { "root_video_surface_stale" }
                    error = capture.updateCapture(request.capture)
                }
                DirectShellProbeProtocol.Command.VIDEO_STOP -> {
                    require(surfaceGeneration == null || surfaceGeneration == request.surfaceGeneration)
                    error = capture.stopCapture()
                    close()
                }
                DirectShellProbeProtocol.Command.GET_VIDEO_DIAGNOSTICS -> Unit
                else -> error("unsupported_root_video_operation")
            }
        } catch (failure: Exception) {
            detail = "${failure.javaClass.simpleName}:${failure.cause?.javaClass?.simpleName ?: failure.message}"
            error = CaptureError.BackendInvocationFailed
            close()
        }
        val caps = capture.queryCapabilities()
        val snapshot = capture.snapshot()
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_video_operation command=$command generation=$generation " +
                "surface=${request.surfaceGeneration} " +
                "backend=${snapshot.backend} state=${snapshot.state} error=$error detail=$detail " +
                "source=${snapshot.sourceWidth}x${snapshot.sourceHeight} " +
                "target=${snapshot.targetWidth}x${snapshot.targetHeight}",
        )
        return RootVideoReplies.encode(generation, error, detail, caps, snapshot)
    }

    @Synchronized override fun close() {
        capture.stopCapture()
        surface?.release()
        surface = null
        surfaceGeneration = null
        runCatching { provider?.close() }
        provider = null
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_video_cleanup generation=$generation mirror=false surface=false provider=false",
        )
    }
}

internal object RootVideoReplies {
    fun encode(
        generation: Long,
        error: CaptureError,
        detail: String,
        caps: CaptureCapabilities,
        snapshot: CaptureSessionSnapshot,
    ): ByteArray = JSONObject().apply {
        put("generation", generation)
        put("error", error.code)
        put("detail", detail.take(180))
        put("backend", snapshot.backend.name)
        put("available", caps.backendAvailable)
        put("api", caps.platformApiLevel)
        put("state", snapshot.state.name)
        put("source_width", snapshot.sourceWidth ?: caps.supportedSourceDisplays.firstOrNull()?.logicalWidth ?: 0)
        put("source_height", snapshot.sourceHeight ?: caps.supportedSourceDisplays.firstOrNull()?.logicalHeight ?: 0)
        put("rotation", snapshot.sourceRotation ?: caps.supportedSourceDisplays.firstOrNull()?.rotation ?: 0)
        put("width", snapshot.targetWidth ?: 0)
        put("height", snapshot.targetHeight ?: 0)
        put("elapsed_ms", SystemClock.elapsedRealtime())
    }.toString().toByteArray()

    fun decode(bytes: ByteArray, generation: Long): JSONObject = JSONObject(String(bytes, Charsets.UTF_8)).also {
        require(bytes.size <= 2048 && it.getLong("generation") == generation)
    }

    fun capabilities(reply: JSONObject) = CaptureCapabilities(
        if (reply.getBoolean("available")) CapturePrivilegeState.Ready else CapturePrivilegeState.BackendUnavailable,
        CaptureBackend.valueOf(reply.getString("backend")),
        reply.getBoolean("available"),
        listOf(
            CaptureDisplayInfo(
                0,
                reply.getInt("source_width"),
                reply.getInt("source_height"),
                reply.getInt("rotation"),
            ),
        ),
        true,
        reply.getInt("api"),
        CaptureError.fromCode(reply.getInt("error")),
    )

    fun snapshot(reply: JSONObject) = CaptureSessionSnapshot(
        state = CaptureState.valueOf(reply.getString("state")),
        backend = CaptureBackend.valueOf(reply.getString("backend")), sourceDisplayId = 0,
        sourceWidth = reply.getInt("source_width"), sourceHeight = reply.getInt("source_height"),
        sourceRotation = reply.getInt(
            "rotation",
        ),
        targetWidth = reply.getInt("width"), targetHeight = reply.getInt("height"),
        lastError = CaptureError.fromCode(reply.getInt("error")),
    )
}
