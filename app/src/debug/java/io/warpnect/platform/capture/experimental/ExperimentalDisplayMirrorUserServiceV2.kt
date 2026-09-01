package io.warpnect.platform.capture.experimental

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import io.warpnect.platform.video.encoder.AndroidVideoEncoderDiscovery
import io.warpnect.platform.video.encoder.AndroidVideoEncoderFormatFactory
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
        val result = when (kind) {
            ExperimentalDisplayMirrorProbeKind.MirrorLifecycle -> runMirrorLifecycle()
            ExperimentalDisplayMirrorProbeKind.EncoderFrame -> runEncoderFrame()
            ExperimentalDisplayMirrorProbeKind.Resolution -> probe.run(kind)
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
        val request = VideoEncoderRequest(
            width = WIDTH,
            height = HEIGHT,
            frameRate = FRAME_RATE,
            bitrateBps = BITRATE_BPS,
            iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
        )
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
        const val PROBE_REVISION = "v2-direct-encoder-1"
        const val KEY_PROBE_REVISION = "probe_revision"
        const val DISPLAY_MANAGER_GLOBAL = "android.hardware.display.DisplayManagerGlobal"
        const val DISPLAY_MANAGER = "android.hardware.display.DisplayManager"
        const val VIRTUAL_DISPLAY = "android.hardware.display.VirtualDisplay"
        const val GET_INSTANCE = "getInstance"
        const val GET_DISPLAY_INFO = "getDisplayInfo"
        const val CREATE_VIRTUAL_DISPLAY = "createVirtualDisplay"
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
