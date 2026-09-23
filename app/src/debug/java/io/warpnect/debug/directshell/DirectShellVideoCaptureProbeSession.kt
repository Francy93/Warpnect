package io.warpnect.debug.directshell

import android.os.Process
import android.util.Log
import android.view.Surface
import io.warpnect.platform.video.encoder.AndroidExactVideoEncoderCapabilityProbe
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.CbrCapabilityFallback
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.encoder.VideoEncoderRequest
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Arrays
import kotlinx.coroutines.runBlocking

/**
 * DEBUG-only normal-process owner for one app-owned production encoder Surface.
 *
 * The shell process never creates an encoder. It may receive exactly one parcelled copy of this
 * Surface after proving possession of the active generation secret through the shell-gated
 * ContentProvider. The encoder and all AVC output remain in the normal Warpnect process.
 */
internal object DirectShellVideoCaptureProbeSession {
    private const val TAG = "WarpnectDirectShellVideo"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FRAME_RATE = 60
    private const val BITRATE_BPS = 8_000_000
    private const val I_FRAME_INTERVAL_SECONDS = 1f
    private const val MAX_HASH_BYTES_PER_ACCESS_UNIT = 4_096

    private var active: ActiveGeneration? = null

    @JvmStatic
    @Synchronized
    fun arm(generationId: Long, secret: ByteArray): Result {
        if (generationId <= 0L) return Result.failure("INVALID_GENERATION")
        val safeSecret = try {
            DirectShellProbeProtocol.requireSecret(secret)
        } catch (_: IllegalArgumentException) {
            return Result.failure("INVALID_SECRET")
        }
        releaseLocked("replaced")

        val metrics = EncoderMetrics()
        val encoder = AndroidMediaCodecVideoEncoder(
            discovery = AndroidVideoEncoderDiscovery(
                CbrCapabilityFallback(AndroidExactVideoEncoderCapabilityProbe()),
            ),
        )
        val prepared = runCatching {
            runBlocking {
                encoder.prepare(
                    VideoEncoderRequest(
                        width = WIDTH,
                        height = HEIGHT,
                        frameRate = FRAME_RATE,
                        bitrateBps = BITRATE_BPS,
                        iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
                    ),
                    metrics,
                )
            }
        }.getOrElse {
            Arrays.fill(safeSecret, 0.toByte())
            encoder.close()
            return Result.failure("ENCODER_PREPARE_THROW_${it.javaClass.simpleName}")
        }
        val surface = prepared.inputSurface
        if (!prepared.isSuccess || surface == null || !surface.isValid) {
            Arrays.fill(safeSecret, 0.toByte())
            encoder.close()
            return Result.failure("ENCODER_PREPARE_${prepared.error.name}")
        }
        val started = runCatching { runBlocking { encoder.start() } }.getOrElse {
            Arrays.fill(safeSecret, 0.toByte())
            encoder.close()
            return Result.failure("ENCODER_START_THROW_${it.javaClass.simpleName}")
        }
        if (!started.isSuccess) {
            Arrays.fill(safeSecret, 0.toByte())
            runCatching { runBlocking { encoder.stop() } }
            encoder.close()
            return Result.failure("ENCODER_START_${started.error.name}")
        }
        active = ActiveGeneration(
            generationId = generationId,
            secret = safeSecret,
            encoder = encoder,
            surface = surface,
            metrics = metrics,
        )
        log("APP_VIDEO_ENCODER_ARM generation=$generationId ${payloadLocked("ARMED")}")
        return Result.success(payloadLocked("ARMED"))
    }

    /**
     * Registers a Session-owned production encoder Surface without taking ownership of that codec
     * or Surface. It is the narrow normal-process setup seam used by the DEBUG Session integration.
     */
    @JvmStatic
    @Synchronized
    fun armExternalSurface(generationId: Long, secret: ByteArray, surface: Surface): Result {
        if (generationId <= 0L || !surface.isValid) return Result.failure("INVALID_EXTERNAL_SURFACE")
        val safeSecret = try {
            DirectShellProbeProtocol.requireSecret(secret)
        } catch (_: IllegalArgumentException) {
            return Result.failure("INVALID_SECRET")
        }
        releaseLocked("replaced")
        active = ActiveGeneration(
            generationId = generationId,
            secret = safeSecret,
            encoder = null,
            surface = surface,
            metrics = EncoderMetrics(),
        )
        log("APP_VIDEO_EXTERNAL_SURFACE_ARM generation=$generationId ${payloadLocked("EXTERNAL_SURFACE_ARMED")}")
        return Result.success(payloadLocked("EXTERNAL_SURFACE_ARMED"))
    }

    @JvmStatic
    @Synchronized
    fun issueSurface(
        callerUid: Int,
        callerPid: Int,
        magic: Int,
        version: Int,
        generationId: Long,
        width: Int,
        height: Int,
        proof: ByteArray?,
    ): SurfaceReply {
        val current = active
        val result = when {
            callerUid != Process.SHELL_UID -> DirectShellVideoCaptureProbeBinderProtocol.Result.CALLER_UID_REJECTED
            current == null -> DirectShellVideoCaptureProbeBinderProtocol.Result.NOT_ARMED
            else -> DirectShellVideoCaptureProbeBinderProtocol.validate(
                current.generationId,
                current.surfaceIssued,
                WIDTH,
                HEIGHT,
                magic,
                version,
                generationId,
                width,
                height,
                proof,
                current.secret,
            )
        }
        if (result != DirectShellVideoCaptureProbeBinderProtocol.Result.OK || current == null) {
            log(
                "APP_VIDEO_SURFACE_REJECT result=${result.name} caller_uid=$callerUid " +
                    "caller_pid=$callerPid generation=$generationId",
            )
            return SurfaceReply.failure(result, "encoder_surface_rejected")
        }
        if (!current.surface.isValid) {
            return SurfaceReply.failure(
                DirectShellVideoCaptureProbeBinderProtocol.Result.INTERNAL_ERROR,
                "encoder_surface_invalid",
            )
        }
        current.surfaceIssued = true
        log(
            "APP_VIDEO_SURFACE_ISSUED generation=${current.generationId} caller_uid=$callerUid " +
                "caller_pid=$callerPid ${payloadLocked("SURFACE_ISSUED")}",
        )
        return SurfaceReply.success(current.surface, "encoder_surface_issued")
    }

    @JvmStatic
    @Synchronized
    fun metrics(generationId: Long): Result {
        if (currentGeneration(generationId) == null) return Result.failure("GENERATION_NOT_ACTIVE")
        return Result.success(payloadLocked("METRICS"))
    }

    @JvmStatic
    @Synchronized
    fun finish(generationId: Long): Result {
        if (currentGeneration(generationId) == null) return Result.failure("GENERATION_NOT_ACTIVE")
        releaseLocked("finished")
        log("APP_VIDEO_ENCODER_FINISH generation=$generationId")
        return Result.success("generation=$generationId encoder_released=true")
    }

    @Synchronized
    fun closeForDebug() {
        releaseLocked("debug_close")
    }

    private fun currentGeneration(generationId: Long): ActiveGeneration? = active?.takeIf {
        it.generationId == generationId
    }

    private fun payloadLocked(event: String): String {
        val current = active ?: return "event=$event active=false"
        return current.metrics.payload(
            event = event,
            generationId = current.generationId,
            surfaceValid = current.surface.isValid,
            surfaceIssued = current.surfaceIssued,
        )
    }

    private fun releaseLocked(reason: String) {
        val current = active ?: return
        active = null
        current.encoder?.let { encoder ->
            runCatching { runBlocking { encoder.stop() } }
            encoder.close()
        }
        Arrays.fill(current.secret, 0.toByte())
        log("APP_VIDEO_ENCODER_RELEASE generation=${current.generationId} reason=$reason")
    }

    private fun log(message: String) = Log.i(TAG, message)

    internal data class SurfaceReply(
        val result: DirectShellVideoCaptureProbeBinderProtocol.Result,
        val surface: Surface?,
        val detail: String,
    ) {
        companion object {
            fun success(surface: Surface, detail: String): SurfaceReply = SurfaceReply(
                DirectShellVideoCaptureProbeBinderProtocol.Result.OK,
                surface,
                detail,
            )

            fun failure(result: DirectShellVideoCaptureProbeBinderProtocol.Result, detail: String): SurfaceReply =
                SurfaceReply(result, null, detail)
        }
    }

    internal data class Result(val success: Boolean, val payload: String) {
        companion object {
            fun success(payload: String) = Result(true, payload)
            fun failure(payload: String) = Result(false, payload)
        }
    }

    private data class ActiveGeneration(
        val generationId: Long,
        val secret: ByteArray,
        val encoder: AndroidMediaCodecVideoEncoder?,
        val surface: Surface,
        val metrics: EncoderMetrics,
        var surfaceIssued: Boolean = false,
    )

    private class EncoderMetrics : EncodedVideoSink {
        private val lock = Any()
        private val digest = MessageDigest.getInstance("SHA-256")
        private var outputFormats = 0L
        private var accessUnits = 0L
        private var encodedBytes = 0L
        private var keyframes = 0L
        private var encoderErrors = 0L
        private var lastError = VideoEncoderError.None

        override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
            synchronized(lock) {
                outputFormats += 1L
            }
        }

        override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            synchronized(lock) {
                accessUnits += 1L
                encodedBytes += size.coerceAtLeast(0).toLong()
                if ((flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keyframes += 1L
                val sampleBytes = size.coerceIn(0, MAX_HASH_BYTES_PER_ACCESS_UNIT)
                if (sampleBytes > 0 && offset >= 0 && offset + sampleBytes <= buffer.capacity()) {
                    val copy = buffer.duplicate()
                    copy.position(offset)
                    copy.limit(offset + sampleBytes)
                    val bytes = ByteArray(sampleBytes)
                    copy.get(bytes)
                    digest.update(bytes)
                    Arrays.fill(bytes, 0.toByte())
                }
            }
        }

        override fun onEncoderError(error: VideoEncoderError) {
            synchronized(lock) {
                encoderErrors += 1L
                lastError = error
            }
        }

        fun payload(event: String, generationId: Long, surfaceValid: Boolean, surfaceIssued: Boolean): String {
            return synchronized(lock) {
                val hash = digest.clone() as MessageDigest
                "event=$event generation=$generationId surface_valid=$surfaceValid surface_issued=$surfaceIssued " +
                    "output_formats=$outputFormats access_units=$accessUnits encoded_bytes=$encodedBytes " +
                    "keyframes=$keyframes encoder_errors=$encoderErrors last_error=${lastError.name} " +
                    "sample_hash=${hash.digest().joinToString("") { "%02x".format(it) }}"
            }
        }
    }
}
