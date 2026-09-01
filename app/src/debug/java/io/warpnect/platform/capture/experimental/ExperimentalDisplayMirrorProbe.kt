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

internal enum class ExperimentalDisplayMirrorProbeKind(val code: Int) {
    Resolution(1),
    MirrorLifecycle(2),
    EncoderFrame(3),
    ;

    companion object {
        fun fromCode(code: Int): ExperimentalDisplayMirrorProbeKind =
            entries.firstOrNull { it.code == code } ?: Resolution
    }
}

/**
 * A bounded compatibility probe for the hidden static DisplayManager mirror API used as prior art
 * by current scrcpy. This creates no Warpnect Session, persists no pixels, and never sends media.
 */
/** A V2 helper prevents Shizuku from reusing the prior probe class during this isolated spike. */
internal class ExperimentalDisplayMirrorProbeV2 {
    fun run(kind: ExperimentalDisplayMirrorProbeKind): Bundle {
        val result = Bundle().apply {
            putString(KEY_PROBE, kind.name)
            putInt(KEY_UID, Process.myUid())
            putString(KEY_IDENTITY_MODE, identityMode(Process.myUid()))
            putString(KEY_SELINUX_CONTEXT, selinuxContext())
        }
        val resolved = resolve(result) ?: return result
        if (kind == ExperimentalDisplayMirrorProbeKind.Resolution) return result

        return when (kind) {
            ExperimentalDisplayMirrorProbeKind.MirrorLifecycle -> runMirrorLifecycle(resolved, result)
            ExperimentalDisplayMirrorProbeKind.EncoderFrame -> runEncoderFrame(resolved, result)
            ExperimentalDisplayMirrorProbeKind.Resolution -> result
        }
    }

    private fun resolve(result: Bundle): ResolvedDisplayManagerMirror? {
        val globalClass = resolveClass("android.hardware.display.DisplayManagerGlobal", result)
            ?: return null
        val global = resolveStaticNoArgs(globalClass, "getInstance", result)
            ?: return null
        result.putBoolean(KEY_DISPLAY_MANAGER_SERVICE_AVAILABLE, true)

        val displayInfo = runCatching {
            global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType!!).invoke(global, DISPLAY_ID)
        }.getOrElse {
            result.failure(ExperimentalDisplayMirrorFailure.DisplayUnavailable, it)
            null
        }
        result.putBoolean(KEY_DISPLAY_0_AVAILABLE, displayInfo != null)
        if (displayInfo == null) return null

        val managerClass = resolveClass("android.hardware.display.DisplayManager", result)
            ?: return null
        val method = runCatching {
            managerClass.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Surface::class.java,
            )
        }.getOrElse {
            result.failure(ExperimentalDisplayMirrorFailure.MethodNotFound, it)
            null
        }
        result.putBoolean(KEY_MIRROR_METHOD_AVAILABLE, method != null)
        if (method == null) return null
        val expected = Modifier.isStatic(method.modifiers) &&
            method.returnType.name == "android.hardware.display.VirtualDisplay"
        result.putBoolean(KEY_EXPECTED_SIGNATURE_AVAILABLE, expected)
        if (!expected) {
            result.failure(ExperimentalDisplayMirrorFailure.SignatureMismatch, null)
            return null
        }
        result.putString(KEY_FAILURE, ExperimentalDisplayMirrorFailure.None.name)
        return ResolvedDisplayManagerMirror(method)
    }

    private fun runMirrorLifecycle(resolved: ResolvedDisplayManagerMirror, result: Bundle): Bundle {
        var reader: ImageReader? = null
        var virtualDisplay: Any? = null
        var released = false
        try {
            reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, IMAGE_READER_BUFFERS)
            result.putBoolean(KEY_SURFACE_ATTACHED, reader.surface.isValid)
            virtualDisplay = resolved.createVirtualDisplay(reader.surface, result)
            result.putBoolean(KEY_CREATE_MIRROR_DISPLAY, virtualDisplay != null)
            if (virtualDisplay == null) {
                result.failure(ExperimentalDisplayMirrorFailure.VirtualDisplayCreationFailed, null)
                return result
            }
        } catch (throwable: Throwable) {
            result.failure(ExperimentalDisplayMirrorFailure.from(throwable), throwable)
            return result
        } finally {
            released = releaseVirtualDisplay(virtualDisplay) && closeImageReader(reader)
            result.putBoolean(KEY_RELEASE_SUCCEEDED, released)
            if (!released && result.failure() == ExperimentalDisplayMirrorFailure.None) {
                result.failure(ExperimentalDisplayMirrorFailure.ReleaseFailed, null)
            }
        }
        if (result.failure() == ExperimentalDisplayMirrorFailure.None) {
            result.putBoolean(KEY_MIRROR_LIFECYCLE_SUCCEEDED, true)
        }
        return result
    }

    private fun runEncoderFrame(resolved: ResolvedDisplayManagerMirror, result: Bundle): Bundle {
        val request = VideoEncoderRequest(
            width = WIDTH,
            height = HEIGHT,
            frameRate = FRAME_RATE,
            bitrateBps = BITRATE_BPS,
            iFrameIntervalSeconds = I_FRAME_INTERVAL_SECONDS,
        )
        val capabilities = AndroidVideoEncoderDiscovery().query(request)
        val codecName = capabilities.selectedCodec?.codecName
        if (!capabilities.isSupported || codecName == null) {
            result.failure(ExperimentalDisplayMirrorFailure.EncoderUnavailable, null)
            return result
        }

        var codec: MediaCodec? = null
        var inputSurface: Surface? = null
        var virtualDisplay: Any? = null
        var codecStarted = false
        var released = false
        try {
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(
                AndroidVideoEncoderFormatFactory.create(request),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            inputSurface = codec.createInputSurface()
            codec.start()
            codecStarted = true
            result.putBoolean(KEY_ENCODER_STARTED, true)

            virtualDisplay = resolved.createVirtualDisplay(inputSurface, result)
            result.putBoolean(KEY_CAPTURE_STARTED, virtualDisplay != null)
            if (virtualDisplay == null) {
                result.failure(ExperimentalDisplayMirrorFailure.VirtualDisplayCreationFailed, null)
                return result
            }

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
                        break
                    }
                }
            }
            if (!result.getBoolean(KEY_FIRST_REAL_FRAME_ENCODED, false)) {
                result.failure(ExperimentalDisplayMirrorFailure.EncoderOutputTimeout, null)
            }
        } catch (throwable: Throwable) {
            result.failure(ExperimentalDisplayMirrorFailure.from(throwable), throwable)
        } finally {
            released = releaseVirtualDisplay(virtualDisplay)
            if (codecStarted) released = runCatching { codec?.stop() }.isSuccess && released
            released = runCatching { inputSurface?.release() }.isSuccess && released
            released = runCatching { codec?.release() }.isSuccess && released
            result.putBoolean(KEY_RELEASE_SUCCEEDED, released)
            if (!released && result.failure() == ExperimentalDisplayMirrorFailure.None) {
                result.failure(ExperimentalDisplayMirrorFailure.ReleaseFailed, null)
            }
        }
        return result
    }

    private fun resolveClass(name: String, result: Bundle): Class<*>? = runCatching { Class.forName(name) }
        .getOrElse {
            result.failure(ExperimentalDisplayMirrorFailure.ClassNotFound, it)
            null
        }

    private fun resolveStaticNoArgs(owner: Class<*>, name: String, result: Bundle): Any? = runCatching {
        owner.getDeclaredMethod(name).invoke(null)
    }.getOrElse {
        result.failure(ExperimentalDisplayMirrorFailure.from(it), it)
        null
    }

    private fun ResolvedDisplayManagerMirror.createVirtualDisplay(surface: Surface, result: Bundle): Any? {
        val arguments: Array<Any> = arrayOf(DISPLAY_NAME, WIDTH, HEIGHT, DISPLAY_ID, surface)
        result.putString(KEY_REFLECTION_STAGE, "arguments_created")
        val expectedParameterTypes = arrayOf(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Surface::class.java,
        )
        result.putString(KEY_REFLECTION_STAGE, "arguments_checked")
        result.putInt(KEY_METHOD_PARAMETER_COUNT, expectedParameterTypes.size)
        result.putInt(KEY_MIRROR_ARGUMENT_COUNT, arguments.size)
        result.putBoolean(KEY_MIRROR_ARGUMENT_COUNT_MATCH, expectedParameterTypes.size == arguments.size)
        val argumentsAssignable = expectedParameterTypes.zip(arguments).mapIndexed { index, (expected, value) ->
            expected.accepts(value).also { assignable ->
                result.putBoolean("$KEY_ARGUMENT_ASSIGNABLE_PREFIX$index$KEY_ARGUMENT_ASSIGNABLE_SUFFIX", assignable)
            }
        }
        result.putBoolean(KEY_MIRROR_ARGUMENT_TYPES_MATCH, argumentsAssignable.all { it })
        result.putString(KEY_REFLECTION_STAGE, "invoke_started")
        return try {
            createVirtualDisplay.invoke(null, DISPLAY_NAME, WIDTH, HEIGHT, DISPLAY_ID, surface).also {
                result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            }
        } catch (exception: InvocationTargetException) {
            // The target method was entered; its wrapped failure is a service/platform result.
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, true)
            throw exception
        } catch (exception: IllegalArgumentException) {
            result.putBoolean(KEY_REFLECTION_INVOCATION_ACCEPTED, false)
            throw exception
        }
    }

    private fun Class<*>.accepts(value: Any): Boolean = when {
        !isPrimitive -> isInstance(value)
        this == Int::class.javaPrimitiveType -> value is Int
        this == Boolean::class.javaPrimitiveType -> value is Boolean
        this == Long::class.javaPrimitiveType -> value is Long
        this == Float::class.javaPrimitiveType -> value is Float
        this == Double::class.javaPrimitiveType -> value is Double
        else -> false
    }

    private fun releaseVirtualDisplay(virtualDisplay: Any?): Boolean {
        if (virtualDisplay == null) return true
        return runCatching { virtualDisplay.javaClass.getMethod("release").invoke(virtualDisplay) }.isSuccess
    }

    private fun closeImageReader(reader: ImageReader?): Boolean = runCatching { reader?.close() }.isSuccess

    private fun identityMode(uid: Int): String = when (uid) {
        Process.ROOT_UID -> "root"
        Process.SHELL_UID -> "shell"
        else -> "other"
    }

    private fun selinuxContext(): String = runCatching {
        Class.forName("android.os.SELinux").getMethod("getContext").invoke(null) as? String
    }.getOrNull() ?: "unavailable"

    private fun Bundle.failure(): ExperimentalDisplayMirrorFailure = runCatching {
        enumValueOf<ExperimentalDisplayMirrorFailure>(
            getString(KEY_FAILURE) ?: ExperimentalDisplayMirrorFailure.None.name,
        )
    }.getOrDefault(ExperimentalDisplayMirrorFailure.Unexpected)

    private fun Bundle.failure(failure: ExperimentalDisplayMirrorFailure, throwable: Throwable?) {
        putString(KEY_FAILURE, failure.name)
        putString(
            KEY_FAILURE_STAGE,
            throwable?.let { ExperimentalDisplayMirrorFailure.from(it).name } ?: failure.name,
        )
        throwable?.let { putString(KEY_FAILURE_ORIGIN, ExperimentalDisplayMirrorFailure.origin(it)) }
    }

    private data class ResolvedDisplayManagerMirror(val createVirtualDisplay: Method)

    private companion object {
        const val DISPLAY_ID = 0
        const val DISPLAY_NAME = "WarpnectCaptureExperiment"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val BITRATE_BPS = 8_000_000
        const val I_FRAME_INTERVAL_SECONDS = 1f
        const val IMAGE_READER_BUFFERS = 2
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
        const val KEY_METHOD_PARAMETER_COUNT = "method_parameter_count"
        const val KEY_MIRROR_ARGUMENT_COUNT = "mirror_argument_count"
        const val KEY_MIRROR_ARGUMENT_COUNT_MATCH = "mirror_argument_count_match"
        const val KEY_MIRROR_ARGUMENT_TYPES_MATCH = "mirror_argument_types_match"
        const val KEY_ARGUMENT_ASSIGNABLE_PREFIX = "arg_"
        const val KEY_ARGUMENT_ASSIGNABLE_SUFFIX = "_assignable"
        const val KEY_REFLECTION_STAGE = "reflection_stage"
        const val KEY_REFLECTION_INVOCATION_ACCEPTED = "reflection_invocation_accepted"
        const val KEY_CREATE_MIRROR_DISPLAY = "create_mirror_display"
        const val KEY_SURFACE_ATTACHED = "surface_attached"
        const val KEY_RELEASE_SUCCEEDED = "release_succeeded"
        const val KEY_MIRROR_LIFECYCLE_SUCCEEDED = "mirror_lifecycle_succeeded"
        const val KEY_CAPTURE_STARTED = "capture_started"
        const val KEY_ENCODER_STARTED = "encoder_started"
        const val KEY_FIRST_REAL_FRAME_ENCODED = "first_real_frame_encoded"
        const val KEY_FAILURE = "failure"
        const val KEY_FAILURE_STAGE = "failure_stage"
        const val KEY_FAILURE_ORIGIN = "failure_origin"
    }
}

private enum class ExperimentalDisplayMirrorFailure {
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
    EncoderInputSurfaceFailed,
    EncoderStartFailed,
    EncoderOutputTimeout,
    ReleaseFailed,
    Unexpected,
    ;

    companion object {
        fun from(throwable: Throwable): ExperimentalDisplayMirrorFailure {
            val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
            return when (cause) {
                is ClassNotFoundException -> ClassNotFound
                is NoSuchMethodException -> MethodNotFound
                is SecurityException -> PermissionDenied
                is IllegalAccessException -> PermissionDenied
                is IllegalArgumentException -> {
                    if (throwable is InvocationTargetException) {
                        DisplayConfigurationRejected
                    } else {
                        ReflectionArgumentMismatch
                    }
                }
                is android.os.RemoteException -> BinderCallRejected
                is android.media.MediaCodec.CodecException -> EncoderStartFailed
                else -> PlatformInvocationFailed
            }
        }

        fun origin(throwable: Throwable): String = when (throwable) {
            is InvocationTargetException -> when (throwable.targetException) {
                is SecurityException -> "TargetSecurity"
                is IllegalArgumentException -> "TargetIllegalArgument"
                else -> "TargetFailure"
            }
            is IllegalArgumentException -> "ReflectionArgument"
            is IllegalAccessException -> "ReflectionAccess"
            else -> "ResolutionOrInvocation"
        }
    }
}
