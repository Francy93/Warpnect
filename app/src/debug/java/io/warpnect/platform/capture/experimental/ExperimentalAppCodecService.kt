package io.warpnect.platform.capture.experimental

import android.app.Application
import android.app.Service
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.view.Surface

/**
 * Debug-only, normal-UID codec owner. It passes only the encoder input Surface to the Shizuku
 * UserService and never creates a Session, transports media, or persists encoded output.
 */
class ExperimentalAppCodecService : Service() {
    private val lock = Any()

    private val binder = object : IExperimentalAppCodecService.Stub() {
        override fun runSplitProcessLegacyFrame(mirrorService: IBinder?, secure: Boolean): Bundle {
            return synchronized(lock) {
                this@ExperimentalAppCodecService.runSplitProcessLegacyFrame(mirrorService, secure)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun runSplitProcessLegacyFrame(mirrorService: IBinder?, secure: Boolean): Bundle {
        val result = Bundle().apply {
            putString(KEY_CODEC_OWNER_PROCESS, codecOwnerProcessName())
            putInt(KEY_CODEC_OWNER_UID, Process.myUid())
            putBoolean(KEY_CAPTURE_BACKEND_FRAME_PROOF_ONLY, true)
        }
        val remoteMirror = IExperimentalDisplayMirrorService.Stub.asInterface(mirrorService)
            ?: return result.failure(FAILURE_MIRROR_SERVICE_UNAVAILABLE)
        val codecInfo = findAdvertisedVbrAvcEncoder() ?: return result.failure(FAILURE_ENCODER_UNAVAILABLE)

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var codecStarted = false
        var mirrorStarted = false
        try {
            codec = MediaCodec.createByCodecName(codecInfo.name)
            codec.configure(
                createAdvertisedVbrFormat(),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_APP_CODEC_CONFIGURED, true)
            inputSurface = codec.createInputSurface()
            result.putBoolean(KEY_APP_CODEC_INPUT_SURFACE_CREATED, inputSurface.isValid)
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_APP_CODEC_STARTED, true)

            val mirrorResult = remoteMirror.startLegacyMirror(checkNotNull(inputSurface), secure)
            copyMirrorResult(mirrorResult, result)
            mirrorStarted = mirrorResult.getBoolean(KEY_MIRROR_CREATED, false)
            if (!mirrorStarted) return result.failure(FAILURE_LEGACY_MIRROR_REJECTED)

            val startedAt = SystemClock.elapsedRealtime()
            val bufferInfo = MediaCodec.BufferInfo()
            val deadline = startedAt + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(KEY_FIRST_FRAME_ELAPSED_MS, SystemClock.elapsedRealtime() - startedAt)
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(FAILURE_ENCODER_OUTPUT_TIMEOUT)
            }
        } catch (exception: Throwable) {
            result.failure(failureReason(exception))
        } finally {
            var cleanup = true
            if (mirrorStarted) {
                val mirrorStop = runCatching { remoteMirror.stopLegacyMirror() }.getOrNull()
                cleanup = mirrorStop?.getBoolean(KEY_CLEANUP_SUCCEEDED, false) == true
            }
            if (codecStarted) cleanup = runCatching { codec?.stop() }.isSuccess && cleanup
            cleanup = runCatching { inputSurface?.release() }.isSuccess && cleanup
            cleanup = runCatching { codec?.release() }.isSuccess && cleanup
            result.putBoolean(KEY_CLEANUP_SUCCEEDED, cleanup)
        }
        return result
    }

    private fun copyMirrorResult(source: Bundle, target: Bundle) {
        target.putString(KEY_MIRROR_OWNER_PROCESS, source.getString(KEY_MIRROR_OWNER_PROCESS))
        target.putInt(KEY_MIRROR_OWNER_UID, source.getInt(KEY_MIRROR_OWNER_UID))
        target.putBoolean(
            KEY_SURFACE_TRANSFER_ACCEPTED,
            source.getBoolean(KEY_SURFACE_TRANSFER_ACCEPTED, false),
        )
        target.putBoolean(KEY_MIRROR_CREATED, source.getBoolean(KEY_MIRROR_CREATED, false))
        source.getString(KEY_LEGACY_MIRROR_OUTCOME)?.let {
            target.putString(KEY_LEGACY_MIRROR_OUTCOME, it)
        }
    }

    private fun findAdvertisedVbrAvcEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            if (!info.isEncoder || !info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) }) {
                return@firstOrNull false
            }
            val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull()
                ?: return@firstOrNull false
            val video = capabilities.videoCapabilities
            val encoder = capabilities.encoderCapabilities
            val surface = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
            val software = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly
            hardware &&
                !software &&
                surface &&
                video.isSizeSupported(WIDTH, HEIGHT) &&
                video.areSizeAndRateSupported(WIDTH, HEIGHT, FRAME_RATE.toDouble()) &&
                video.bitrateRange.contains(BITRATE_BPS) &&
                encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) &&
                capabilities.isFormatSupported(createAdvertisedVbrFormat())
        }

    private fun codecOwnerProcessName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            "app_debug_secondary"
        }
    }

    private fun createAdvertisedVbrFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(AVC_MIME, WIDTH, HEIGHT).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE_BPS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
    }

    private fun Bundle.failure(reason: String): Bundle = apply { putString(KEY_FAILURE, reason) }

    private fun failureReason(throwable: Throwable): String = when (throwable) {
        is MediaCodec.CodecException -> FAILURE_CODEC_EXCEPTION
        is SecurityException -> FAILURE_SECURITY_EXCEPTION
        is IllegalArgumentException -> FAILURE_ILLEGAL_ARGUMENT
        is IllegalStateException -> FAILURE_ILLEGAL_STATE
        else -> FAILURE_CODEC_OR_MIRROR_EXCEPTION
    }

    private companion object {
        const val AVC_MIME = "video/avc"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1f
        const val ENCODE_TIMEOUT_MS = 3_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L

        const val KEY_CODEC_OWNER_PROCESS = "codec_owner_process"
        const val KEY_CODEC_OWNER_UID = "codec_owner_uid"
        const val KEY_MIRROR_OWNER_PROCESS = "mirror_owner_process"
        const val KEY_MIRROR_OWNER_UID = "mirror_owner_uid"
        const val KEY_APP_CODEC_CONFIGURED = "app_codec_configured"
        const val KEY_APP_CODEC_INPUT_SURFACE_CREATED = "app_codec_input_surface_created"
        const val KEY_APP_CODEC_STARTED = "app_codec_started"
        const val KEY_SURFACE_TRANSFER_ACCEPTED = "surface_transfer_accepted"
        const val KEY_MIRROR_CREATED = "mirror_created"
        const val KEY_LEGACY_MIRROR_OUTCOME = "legacy_mirror_outcome"
        const val KEY_FIRST_REAL_FRAME_ENCODED = "first_real_frame_encoded"
        const val KEY_FIRST_FRAME_ELAPSED_MS = "first_frame_elapsed_ms"
        const val KEY_CLEANUP_SUCCEEDED = "cleanup_succeeded"
        const val KEY_CAPTURE_BACKEND_FRAME_PROOF_ONLY = "capture_backend_frame_proof_only"
        const val KEY_FAILURE = "failure"

        const val FAILURE_MIRROR_SERVICE_UNAVAILABLE = "MirrorServiceUnavailable"
        const val FAILURE_ENCODER_UNAVAILABLE = "AdvertisedVbrEncoderUnavailable"
        const val FAILURE_LEGACY_MIRROR_REJECTED = "LegacyMirrorRejected"
        const val FAILURE_ENCODER_OUTPUT_TIMEOUT = "EncoderOutputTimeout"
        const val FAILURE_CODEC_EXCEPTION = "CodecException"
        const val FAILURE_SECURITY_EXCEPTION = "SecurityException"
        const val FAILURE_ILLEGAL_ARGUMENT = "IllegalArgumentException"
        const val FAILURE_ILLEGAL_STATE = "IllegalStateException"
        const val FAILURE_CODEC_OR_MIRROR_EXCEPTION = "CodecOrMirrorException"
    }
}
