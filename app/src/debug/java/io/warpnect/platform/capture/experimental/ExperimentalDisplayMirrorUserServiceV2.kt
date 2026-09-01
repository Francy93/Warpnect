package io.warpnect.platform.capture.experimental

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.platform.capture.privileged.SurfaceControlDisplayCaptureApi
import io.warpnect.platform.input.injection.ReflectivePrivilegedInputManagerApi
import io.warpnect.platform.video.encoder.AndroidExactVideoEncoderCapabilityProbe
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.AndroidVideoEncoderFormatFactory
import io.warpnect.platform.video.encoder.ExactVideoEncoderCapabilityKey
import io.warpnect.video.encoder.VideoEncoderRequest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** A new debug-only UserService class forces Shizuku to load the current experimental bytecode. */
class ExperimentalDisplayMirrorUserServiceV2 : IExperimentalDisplayMirrorService.Stub() {
    private val lock = Any()
    private val probe = ExperimentalDisplayMirrorProbeV2()

    override fun runProbe(probeKind: Int): Bundle = synchronized(lock) {
        val kind = ExperimentalDisplayMirrorProbeKind.fromCode(probeKind)
        val result = try {
            when (kind) {
                ExperimentalDisplayMirrorProbeKind.MirrorLifecycle -> runMirrorLifecycle()
                ExperimentalDisplayMirrorProbeKind.EncoderFrame -> runEncoderFrame()
                ExperimentalDisplayMirrorProbeKind.VideoCapability -> runVideoCapability()
                ExperimentalDisplayMirrorProbeKind.InputCapability -> runInputCapability()
                ExperimentalDisplayMirrorProbeKind.LegacyLifecycle -> runLegacyLifecycle()
                ExperimentalDisplayMirrorProbeKind.LegacyEncoderFrame -> runLegacyEncoderFrame()
                ExperimentalDisplayMirrorProbeKind.VideoMetadata -> runVideoMetadata()
                ExperimentalDisplayMirrorProbeKind.Resolution -> probe.run(kind)
            }
        } catch (throwable: Throwable) {
            initialResult(kind).failure(Failure.from(throwable))
        }
        result.apply {
            putString(KEY_PROBE_REVISION, PROBE_REVISION)
        }
    }

    private fun runMirrorLifecycle(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.MirrorLifecycle)
        var reader: ImageReader? = null
        var virtualDisplay: Any? = null
        try {
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            val surface = reader.surface
            result.putBoolean(KEY_SURFACE_ATTACHED, surface.isValid)
            val mirror = resolveMirror(result) ?: return result
            virtualDisplay = invokeMirror(mirror, surface, result)
            result.putBoolean(KEY_CREATE_MIRROR_DISPLAY, virtualDisplay != null)
            if (virtualDisplay == null) return result.failure(Failure.VirtualDisplayCreationFailed)
            result.putBoolean(KEY_MIRROR_LIFECYCLE_SUCCEEDED, true)
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            val virtualDisplayReleased = releaseVirtualDisplay(virtualDisplay)
            val readerReleased = runCatching { reader?.close() }.isSuccess
            result.putBoolean(KEY_RELEASE_SUCCEEDED, virtualDisplayReleased && readerReleased)
            if (!result.getBoolean(KEY_RELEASE_SUCCEEDED) && !result.containsKey(KEY_FAILURE)) {
                result.failure(Failure.ReleaseFailed)
            }
        }
        return result
    }

    private fun runEncoderFrame(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.EncoderFrame)
        val request = videoRequest()
        val capabilities = AndroidVideoEncoderDiscovery().query(request)
        if (!capabilities.isSupported) return result.failure(Failure.EncoderUnavailable)
        val codecName = capabilities.selectedCodec?.codecName ?: return result.failure(Failure.EncoderUnavailable)
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var virtualDisplay: Any? = null
        var codecStarted = false
        try {
            codec = MediaCodec.createByCodecName(codecName)
            result.putString(KEY_ENCODER_STAGE, "configure_started")
            codec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_ENCODER_CONFIGURED, true)
            result.putString(KEY_ENCODER_STAGE, "input_surface_started")
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            val mirror = resolveMirror(result) ?: return result
            val captureStartMs = SystemClock.elapsedRealtime()
            virtualDisplay = invokeMirror(mirror, checkNotNull(inputSurface), result)
            result.putBoolean(KEY_CREATE_MIRROR_DISPLAY, virtualDisplay != null)
            result.putBoolean(KEY_MIRROR_CREATED, virtualDisplay != null)
            result.putBoolean(KEY_CAPTURE_STARTED, virtualDisplay != null)
            if (virtualDisplay == null) return result.failure(Failure.VirtualDisplayCreationFailed)

            val bufferInfo = MediaCodec.BufferInfo()
            val deadlineMs = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(KEY_FIRST_FRAME_ELAPSED_MS, SystemClock.elapsedRealtime() - captureStartMs)
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(Failure.EncoderOutputTimeout)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            var released = releaseVirtualDisplay(virtualDisplay)
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_RELEASE_SUCCEEDED, released)
            if (!released && !result.containsKey(KEY_FAILURE)) result.failure(Failure.ReleaseFailed)
        }
        return result
    }

    /**
     * Inventory only. The exact production discovery query is retained as the authoritative
     * availability result; the raw rows explain vendor metadata without admitting a new profile.
     */
    private fun runVideoCapability(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.VideoCapability)
        try {
            val request = videoRequest()
            val rows = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }
                .map { info -> rawAvcRow(info, request, allowActiveProbe = true) }
                .toList()
            val capabilities = AndroidVideoEncoderDiscovery().query(request)
            result.putInt(KEY_AVC_ENCODER_COUNT, rows.size)
            result.putString(KEY_AVC_ENCODER_INVENTORY, rows.joinToString("|"))
            result.putBoolean(KEY_VIDEO_ENCODER_AVAILABLE, capabilities.isSupported)
            result.putString(KEY_VIDEO_ENCODER_REASON, capabilities.error.name)
            capabilities.selectedCodec?.let { result.putString(KEY_VIDEO_SELECTED_CODEC, it.codecName) }
        } catch (throwable: Throwable) {
            result.putString(KEY_VIDEO_PROBE_FAILURE, Failure.from(throwable).name)
            result.failure(Failure.from(throwable))
        }
        return result
    }

    /** Reads raw Android metadata only and never instantiates a temporary codec. */
    private fun runVideoMetadata(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.VideoMetadata)
        val request = videoRequest()
        val rows = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(AVC_MIME, ignoreCase = true) } }
            .map { info -> rawAvcRow(info, request, allowActiveProbe = false) }
            .toList()
        result.putInt(KEY_AVC_ENCODER_COUNT, rows.size)
        result.putString(KEY_AVC_ENCODER_INVENTORY, rows.joinToString("|"))
        result.putBoolean(KEY_VIDEO_METADATA_ONLY, true)
        return result
    }

    /** Resolves the existing privileged InputManager bridge without injecting an input event. */
    private fun runInputCapability(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.InputCapability)
        val capabilities = ReflectivePrivilegedInputManagerApi().resolve()
        val available = capabilities.apiResolved &&
            capabilities.asyncInjectionSupported &&
            capabilities.displayTargetingSupported
        result.putBoolean(KEY_INPUT_API_RESOLVED, capabilities.apiResolved)
        result.putBoolean(KEY_INPUT_ASYNC_SUPPORTED, capabilities.asyncInjectionSupported)
        result.putBoolean(KEY_INPUT_DISPLAY_SUPPORTED, capabilities.displayTargetingSupported)
        result.putBoolean(KEY_INPUT_AVAILABLE, available)
        result.putString(
            KEY_INPUT_REASON,
            when {
                available -> "None"
                !capabilities.apiResolved -> capabilities.lastError.name
                !capabilities.asyncInjectionSupported -> "InputApiUnavailable"
                else -> "DisplayTargetingUnsupported"
            },
        )
        return result
    }

    /** Runs the unchanged production legacy SurfaceControl bridge against a temporary Surface. */
    private fun runLegacyLifecycle(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyLifecycle)
        var reader: ImageReader? = null
        val captureApi = SurfaceControlDisplayCaptureApi()
        try {
            resolveLegacyStructure(result)
            val capabilities = captureApi.queryCapabilities()
            result.putBoolean(KEY_LEGACY_SURFACECONTROL_AVAILABLE, capabilities.backendAvailable)
            result.putString(KEY_LEGACY_RESOLUTION_ERROR, capabilities.lastError.name)
            if (!capabilities.backendAvailable) return result

            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            val start = captureApi.startCapture(CaptureRequest(DISPLAY_ID, WIDTH, HEIGHT), reader.surface)
            result.putString(KEY_LEGACY_START_ERROR, start.name)
            result.putBoolean(KEY_LEGACY_DISPLAY_CREATED, start == CaptureError.None)
            result.putBoolean(KEY_LEGACY_SURFACE_ATTACHED, start == CaptureError.None && reader.surface.isValid)
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            val stopped = runCatching { captureApi.stopCapture() }.getOrNull() == CaptureError.None
            val readerReleased = runCatching { reader?.close() }.isSuccess
            result.putBoolean(KEY_LEGACY_RELEASE_SUCCEEDED, stopped && readerReleased)
        }
        return result
    }

    /**
     * Uses the exact existing encoder surface and unchanged legacy capture bridge. No Session,
     * network transport, or encoded output persistence is involved.
     */
    private fun runLegacyEncoderFrame(): Bundle {
        val result = initialResult(ExperimentalDisplayMirrorProbeKind.LegacyEncoderFrame)
        val request = videoRequest()
        val capabilities = AndroidVideoEncoderDiscovery().query(request)
        if (!capabilities.isSupported) return result.failure(Failure.EncoderUnavailable)
        val codecName = capabilities.selectedCodec?.codecName ?: return result.failure(Failure.EncoderUnavailable)
        val captureApi = SurfaceControlDisplayCaptureApi()
        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var codecStarted = false
        try {
            resolveLegacyStructure(result)
            val legacyCapabilities = captureApi.queryCapabilities()
            result.putBoolean(KEY_LEGACY_SURFACECONTROL_AVAILABLE, legacyCapabilities.backendAvailable)
            result.putString(KEY_LEGACY_RESOLUTION_ERROR, legacyCapabilities.lastError.name)
            if (!legacyCapabilities.backendAvailable) return result

            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            result.putBoolean(KEY_ENCODER_CONFIGURED, true)
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            val captureStartMs = SystemClock.elapsedRealtime()
            val start = captureApi.startCapture(
                CaptureRequest(DISPLAY_ID, WIDTH, HEIGHT),
                checkNotNull(inputSurface),
            )
            result.putString(KEY_LEGACY_START_ERROR, start.name)
            result.putBoolean(KEY_LEGACY_DISPLAY_CREATED, start == CaptureError.None)
            result.putBoolean(KEY_LEGACY_SURFACE_ATTACHED, start == CaptureError.None)
            if (start != CaptureError.None) return result

            val bufferInfo = MediaCodec.BufferInfo()
            val deadlineMs = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                val index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val hasFrame = bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    codec.releaseOutputBuffer(index, false)
                    if (hasFrame) {
                        result.putBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, true)
                        result.putLong(
                            KEY_LEGACY_FIRST_FRAME_ELAPSED_MS,
                            SystemClock.elapsedRealtime() - captureStartMs,
                        )
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_LEGACY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(Failure.EncoderOutputTimeout)
            }
        } catch (throwable: Throwable) {
            result.failure(Failure.from(throwable))
        } finally {
            var released = runCatching { captureApi.stopCapture() }.getOrNull() == CaptureError.None
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_LEGACY_RELEASE_SUCCEEDED, released)
        }
        return result
    }

    private fun rawAvcRow(info: MediaCodecInfo, request: VideoEncoderRequest, allowActiveProbe: Boolean): String {
        val capabilities = runCatching { info.getCapabilitiesForType(AVC_MIME) }.getOrNull()
            ?: return "${info.name},capabilities=false"
        val video = capabilities.videoCapabilities
        val encoder = capabilities.encoderCapabilities
        val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isHardwareAccelerated
        val software = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly
        val vendor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isVendor
        val alias = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias
        val surface = capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        val width = video.supportedWidths.contains(request.width)
        val height = video.supportedHeights.contains(request.height)
        val size = runCatching { video.isSizeSupported(request.width, request.height) }.getOrDefault(false)
        val frameRate = runCatching {
            video.areSizeAndRateSupported(request.width, request.height, request.frameRate.toDouble())
        }.getOrDefault(false)
        val bitrate = video.bitrateRange.contains(request.bitrateBps)
        val cbr = encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cbrFd = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
        val format = runCatching { capabilities.isFormatSupported(AndroidVideoEncoderFormatFactory.create(request)) }
            .getOrDefault(false)
        val eligible = hardware && !software && surface && width && height && size && frameRate && bitrate
        val activeProbe = if (!allowActiveProbe) {
            "NotRun"
        } else if (!cbr && eligible) {
            AndroidExactVideoEncoderCapabilityProbe()
                .probe(ExactVideoEncoderCapabilityKey.from(info.name, request))
                .probeResult
                ?.name ?: "None"
        } else {
            "NotNeeded"
        }
        return listOf(
            info.name,
            "hw=$hardware",
            "sw=$software",
            "vendor=$vendor",
            "alias=$alias",
            "surface=$surface",
            "width=$width",
            "height=$height",
            "size=$size",
            "fps60=$frameRate",
            "bitrate8m=$bitrate",
            "cbr=$cbr",
            "cbrfd=$cbrFd",
            "format=$format",
            "probe=$activeProbe",
            "profiles=${capabilities.profileLevels.size}",
        ).joinToString(",")
    }

    private fun resolveLegacyStructure(result: Bundle) {
        val surfaceControl = runCatching { Class.forName(LEGACY_SURFACE_CONTROL) }.getOrNull()
        result.putBoolean(KEY_LEGACY_SURFACECONTROL_CLASS_AVAILABLE, surfaceControl != null)
        val createDisplay = surfaceControl?.let { owner ->
            runCatching {
                owner.getDeclaredMethod(
                    LEGACY_CREATE_DISPLAY,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ).apply { isAccessible = true }
            }.getOrNull()
        }
        result.putBoolean(KEY_LEGACY_CREATE_DISPLAY_AVAILABLE, createDisplay != null)
    }

    private fun videoRequest() = VideoEncoderRequest(
        width = WIDTH,
        height = HEIGHT,
        frameRate = FRAME_RATE,
        bitrateBps = BITRATE_BPS,
        iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
    )

    private fun initialResult(kind: ExperimentalDisplayMirrorProbeKind): Bundle = Bundle().apply {
        putString(KEY_PROBE, kind.name)
        putInt(KEY_UID, Process.myUid())
        putString(KEY_IDENTITY_MODE, if (Process.myUid() == Process.SHELL_UID) "shell" else "other")
        putString(KEY_SELINUX_CONTEXT, selinuxContext())
    }

    private fun resolveMirror(result: Bundle): Method? {
        val displayManagerGlobal = Class.forName(DISPLAY_MANAGER_GLOBAL)
        val global = displayManagerGlobal.getDeclaredMethod(GET_INSTANCE).invoke(null)
        result.putBoolean(KEY_DISPLAY_MANAGER_SERVICE_AVAILABLE, global != null)
        val displayInfo = global.javaClass
            .getMethod(GET_DISPLAY_INFO, Int::class.javaPrimitiveType!!)
            .invoke(global, DISPLAY_ID)
        result.putBoolean(KEY_DISPLAY_0_AVAILABLE, displayInfo != null)
        if (displayInfo == null) {
            result.failure(Failure.DisplayUnavailable)
            return null
        }
        val mirror = Class.forName(DISPLAY_MANAGER).getMethod(
            CREATE_VIRTUAL_DISPLAY,
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Surface::class.java,
        )
        result.putBoolean(KEY_MIRROR_METHOD_AVAILABLE, true)
        val expected = Modifier.isStatic(mirror.modifiers) && mirror.returnType.name == VIRTUAL_DISPLAY
        result.putBoolean(KEY_EXPECTED_SIGNATURE_AVAILABLE, expected)
        if (!expected) {
            result.failure(Failure.SignatureMismatch)
            return null
        }
        return mirror
    }

    private fun invokeMirror(mirror: Method, surface: Surface, result: Bundle): Any? {
        result.putString(KEY_REFLECTION_STAGE, "arguments_checked")
        result.putInt(KEY_METHOD_PARAMETER_COUNT, MIRROR_ARGUMENT_COUNT)
        result.putInt(KEY_MIRROR_ARGUMENT_COUNT, MIRROR_ARGUMENT_COUNT)
        result.putBoolean(KEY_MIRROR_ARGUMENT_COUNT_MATCH, true)
        repeat(MIRROR_ARGUMENT_COUNT) { index ->
            result.putBoolean("$KEY_ARGUMENT_ASSIGNABLE_PREFIX$index$KEY_ARGUMENT_ASSIGNABLE_SUFFIX", true)
        }
        result.putBoolean(KEY_MIRROR_ARGUMENT_TYPES_MATCH, true)
        result.putString(KEY_REFLECTION_STAGE, "invoke_started")
        return try {
            mirror.invoke(null, DISPLAY_NAME, WIDTH, HEIGHT, DISPLAY_ID, surface).also {
                result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            }
        } catch (exception: InvocationTargetException) {
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            throw exception
        } catch (exception: IllegalArgumentException) {
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, false)
            throw exception
        }
    }

    private fun releaseVirtualDisplay(virtualDisplay: Any?): Boolean {
        return if (virtualDisplay == null) {
            true
        } else {
            runCatching { virtualDisplay.javaClass.getMethod(RELEASE).invoke(virtualDisplay) }.isSuccess
        }
    }

    private fun selinuxContext(): String = runCatching {
        Class.forName("android.os.SELinux").getMethod("getContext").invoke(null) as? String
    }.getOrNull() ?: "unavailable"

    private fun Bundle.failure(failure: Failure): Bundle = apply {
        putString(KEY_FAILURE, failure.name)
        putString(KEY_FAILURE_STAGE, failure.name)
    }

    @Suppress("unused")
    fun destroy() = Unit

    private companion object {
        const val PROBE_REVISION = "v2-a41-compatibility-2"
        const val KEY_PROBE_REVISION = "probe_revision"
        const val DISPLAY_MANAGER_GLOBAL = "android.hardware.display.DisplayManagerGlobal"
        const val DISPLAY_MANAGER = "android.hardware.display.DisplayManager"
        const val LEGACY_SURFACE_CONTROL = "android.view.SurfaceControl"
        const val VIRTUAL_DISPLAY = "android.hardware.display.VirtualDisplay"
        const val GET_INSTANCE = "getInstance"
        const val GET_DISPLAY_INFO = "getDisplayInfo"
        const val CREATE_VIRTUAL_DISPLAY = "createVirtualDisplay"
        const val LEGACY_CREATE_DISPLAY = "createDisplay"
        const val RELEASE = "release"
        const val DISPLAY_NAME = "WarpnectCaptureExperiment"
        const val DISPLAY_ID = 0
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1f
        const val IMAGE_READER_BUFFERS = 2
        const val MIRROR_ARGUMENT_COUNT = 5
        const val ENCODE_TIMEOUT_MS = 3_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L

        const val KEY_PROBE = "probe"
        const val KEY_UID = "uid"
        const val KEY_IDENTITY_MODE = "identity_mode"
        const val KEY_SELINUX_CONTEXT = "selinux_context"
        const val KEY_DISPLAY_MANAGER_SERVICE_AVAILABLE = "display_manager_service_available"
        const val KEY_DISPLAY_0_AVAILABLE = "display_0_available"
        const val KEY_MIRROR_METHOD_AVAILABLE = "mirror_method_available"
        const val KEY_EXPECTED_SIGNATURE_AVAILABLE = "expected_signature_available"
        const val KEY_SURFACE_ATTACHED = "surface_attached"
        const val KEY_METHOD_PARAMETER_COUNT = "method_parameter_count"
        const val KEY_MIRROR_ARGUMENT_COUNT = "mirror_argument_count"
        const val KEY_MIRROR_ARGUMENT_COUNT_MATCH = "mirror_argument_count_match"
        const val KEY_ARGUMENT_ASSIGNABLE_PREFIX = "arg_"
        const val KEY_ARGUMENT_ASSIGNABLE_SUFFIX = "_assignable"
        const val KEY_MIRROR_ARGUMENT_TYPES_MATCH = "mirror_argument_types_match"
        const val KEY_REFLECTION_STAGE = "reflection_stage"
        const val KEY_REFLECTION_INVOCATION_ACCEPTED = "reflection_invocation_accepted"
        const val KEY_CREATE_MIRROR_DISPLAY = "create_mirror_display"
        const val KEY_MIRROR_LIFECYCLE_SUCCEEDED = "mirror_lifecycle_succeeded"
        const val KEY_MIRROR_CREATED = "mirror_created"
        const val KEY_CAPTURE_STARTED = "capture_started"
        const val KEY_ENCODER_STAGE = "encoder_stage"
        const val KEY_ENCODER_CONFIGURED = "encoder_configured"
        const val KEY_ENCODER_STARTED = "encoder_started"
        const val KEY_FIRST_REAL_FRAME_ENCODED = "first_real_frame_encoded"
        const val KEY_FIRST_FRAME_ELAPSED_MS = "first_frame_elapsed_ms"
        const val KEY_RELEASE_SUCCEEDED = "release_succeeded"
        const val KEY_FAILURE = "failure"
        const val KEY_FAILURE_STAGE = "failure_stage"
        const val KEY_AVC_ENCODER_COUNT = "avc_encoder_count"
        const val KEY_AVC_ENCODER_INVENTORY = "avc_encoder_inventory"
        const val KEY_VIDEO_ENCODER_AVAILABLE = "video_encoder_available"
        const val KEY_VIDEO_ENCODER_REASON = "video_encoder_reason"
        const val KEY_VIDEO_SELECTED_CODEC = "video_selected_codec"
        const val KEY_VIDEO_PROBE_FAILURE = "video_probe_failure"
        const val KEY_VIDEO_METADATA_ONLY = "video_metadata_only"
        const val KEY_INPUT_API_RESOLVED = "input_api_resolved"
        const val KEY_INPUT_ASYNC_SUPPORTED = "input_async_supported"
        const val KEY_INPUT_DISPLAY_SUPPORTED = "input_display_supported"
        const val KEY_INPUT_AVAILABLE = "input_available"
        const val KEY_INPUT_REASON = "input_reason"
        const val KEY_LEGACY_SURFACECONTROL_CLASS_AVAILABLE = "legacy_surfacecontrol_class_available"
        const val KEY_LEGACY_CREATE_DISPLAY_AVAILABLE = "legacy_create_display_available"
        const val KEY_LEGACY_SURFACECONTROL_AVAILABLE = "legacy_surfacecontrol_available"
        const val KEY_LEGACY_RESOLUTION_ERROR = "legacy_resolution_error"
        const val KEY_LEGACY_START_ERROR = "legacy_start_error"
        const val KEY_LEGACY_DISPLAY_CREATED = "legacy_display_created"
        const val KEY_LEGACY_SURFACE_ATTACHED = "legacy_surface_attached"
        const val KEY_LEGACY_RELEASE_SUCCEEDED = "legacy_release_succeeded"
        const val KEY_LEGACY_FIRST_REAL_FRAME_ENCODED = "legacy_first_real_frame_encoded"
        const val KEY_LEGACY_FIRST_FRAME_ELAPSED_MS = "legacy_first_frame_elapsed_ms"
        const val AVC_MIME = "video/avc"
    }
}

private enum class Failure {
    None,
    ClassNotFound,
    MethodNotFound,
    SignatureMismatch,
    DisplayUnavailable,
    PermissionDenied,
    ReflectionArgumentMismatch,
    DisplayConfigurationRejected,
    BinderCallRejected,
    PlatformInvocationFailed,
    VirtualDisplayCreationFailed,
    EncoderUnavailable,
    EncoderConfigurationFailed,
    EncoderStartFailed,
    EncoderOutputTimeout,
    ReleaseFailed,
    ;

    companion object {
        fun from(throwable: Throwable): Failure {
            val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
            return when (cause) {
                is ClassNotFoundException -> ClassNotFound
                is NoSuchMethodException -> MethodNotFound
                is SecurityException,
                is IllegalAccessException,
                -> PermissionDenied
                is IllegalArgumentException -> {
                    if (throwable is InvocationTargetException) {
                        DisplayConfigurationRejected
                    } else {
                        ReflectionArgumentMismatch
                    }
                }
                is android.os.RemoteException -> BinderCallRejected
                is MediaCodec.CodecException -> EncoderStartFailed
                else -> PlatformInvocationFailed
            }
        }
    }
}
