package io.warpnect.platform.capture.privileged

import android.media.ImageReader
import android.os.Build
import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState
import io.warpnect.platform.capture.CaptureClock
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Privileged modern Android mirror adapter. It mirrors directly into the app-owned encoder Surface;
 * MediaCodec ownership never enters this Shizuku UserService.
 */
internal class DisplayManagerMirrorCaptureApi : PrivilegedDisplayCaptureApi {
    private var resolved: ResolvedDisplayManagerMirrorApi? = null
    private var qualification: DisplayManagerMirrorQualification? = null
    private var virtualDisplay: Any? = null
    private var activeRequest: CaptureRequest? = null
    private var activeDisplayInfo: CaptureDisplayInfo? = null
    private var startedAtMonotonicUs: Long? = null
    private var reconfigurationCount = 0
    private var lastError = CaptureError.None

    override fun queryCapabilities(): CaptureCapabilities {
        val qualified = qualify()
        val api = qualified.api
        val defaultDisplay = qualified.defaultDisplay
        val error = qualified.error
        return CaptureCapabilities(
            privilegeState = if (error == CaptureError.None) {
                CapturePrivilegeState.Ready
            } else {
                CapturePrivilegeState.BackendUnavailable
            },
            backend = if (api != null) CaptureBackend.DisplayManagerMirror else CaptureBackend.None,
            backendAvailable = error == CaptureError.None,
            supportedSourceDisplays = listOfNotNull(defaultDisplay),
            supportsDynamicProjection = api != null,
            platformApiLevel = Build.VERSION.SDK_INT,
            lastError = error,
        )
    }

    override fun startCapture(request: CaptureRequest, targetSurface: Surface): CaptureError {
        if (virtualDisplay != null) return remember(CaptureError.AlreadyRunning)
        val qualified = qualify()
        val api = qualified.api ?: return remember(qualified.error)
        val displayInfo = api.queryDisplayInfo(request.sourceDisplayId)
            ?: return remember(CaptureError.SourceDisplayNotFound)
        val mirror = try {
            api.createMirror.invoke(
                null,
                DISPLAY_NAME,
                request.outputWidth,
                request.outputHeight,
                request.sourceDisplayId,
                targetSurface,
            )
        } catch (throwable: Throwable) {
            return remember(mapInvocationFailure(throwable, CaptureError.CaptureCreationFailed))
        } ?: return remember(CaptureError.CaptureCreationFailed)

        virtualDisplay = mirror
        activeRequest = request
        activeDisplayInfo = displayInfo
        startedAtMonotonicUs = CaptureClock.monotonicUs()
        reconfigurationCount = 0
        return remember(CaptureError.None)
    }

    override fun updateCapture(request: CaptureRequest): CaptureError {
        if (virtualDisplay == null) return remember(CaptureError.NotRunning)
        val api = resolveApi() ?: return remember(CaptureError.HiddenApiUnavailable)
        val displayInfo = api.queryDisplayInfo(request.sourceDisplayId)
            ?: return remember(CaptureError.DisplayRemoved)
        val current = activeRequest ?: return remember(CaptureError.NotRunning)
        activeRequest = request.copy(outputWidth = current.outputWidth, outputHeight = current.outputHeight)
        activeDisplayInfo = displayInfo
        reconfigurationCount += 1
        return remember(CaptureError.None)
    }

    override fun stopCapture(): CaptureError {
        val display = virtualDisplay ?: return CaptureError.None
        virtualDisplay = null
        activeRequest = null
        activeDisplayInfo = null
        startedAtMonotonicUs = null
        val api = resolved ?: return remember(CaptureError.None)
        return try {
            api.release.invoke(display)
            remember(CaptureError.None)
        } catch (throwable: Throwable) {
            remember(mapInvocationFailure(throwable, CaptureError.BackendReleased))
        }
    }

    override fun snapshot(): CaptureSessionSnapshot {
        val request = activeRequest
        val displayInfo = activeDisplayInfo
        return CaptureSessionSnapshot(
            state = when {
                virtualDisplay != null && lastError == CaptureError.None -> CaptureState.Running
                virtualDisplay != null -> CaptureState.Error
                else -> CaptureState.Stopped
            },
            backend = if (resolved != null) CaptureBackend.DisplayManagerMirror else CaptureBackend.None,
            sourceDisplayId = request?.sourceDisplayId,
            sourceWidth = displayInfo?.logicalWidth,
            sourceHeight = displayInfo?.logicalHeight,
            sourceRotation = displayInfo?.rotation,
            targetWidth = request?.outputWidth,
            targetHeight = request?.outputHeight,
            startedAtMonotonicUs = startedAtMonotonicUs,
            reconfigurationCount = reconfigurationCount,
            lastError = lastError,
        )
    }

    private fun resolveApi(): ResolvedDisplayManagerMirrorApi? {
        resolved?.let { return it }
        return runCatching {
            val displayManager = Class.forName(DISPLAY_MANAGER_CLASS)
            val virtualDisplay = Class.forName(VIRTUAL_DISPLAY_CLASS)
            val displayManagerGlobal = Class.forName(DISPLAY_MANAGER_GLOBAL_CLASS)
            val displayInfo = Class.forName(DISPLAY_INFO_CLASS)
            val createMirror = displayManager.publicOrDeclaredMethod(
                CREATE_VIRTUAL_DISPLAY,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Surface::class.java,
            )
            check(Modifier.isStatic(createMirror.modifiers))
            check(virtualDisplay.isAssignableFrom(createMirror.returnType))
            ResolvedDisplayManagerMirrorApi(
                createMirror = createMirror,
                release = virtualDisplay.publicOrDeclaredMethod(RELEASE),
                getDisplayManagerGlobal = displayManagerGlobal.publicOrDeclaredMethod(GET_INSTANCE),
                getDisplayInfo = displayManagerGlobal.publicOrDeclaredMethod(
                    GET_DISPLAY_INFO,
                    Int::class.javaPrimitiveType!!,
                ),
                logicalWidth = displayInfo.getDeclaredField("logicalWidth").apply { isAccessible = true },
                logicalHeight = displayInfo.getDeclaredField("logicalHeight").apply { isAccessible = true },
                rotation = displayInfo.getDeclaredField("rotation").apply { isAccessible = true },
                refreshRate = displayInfo.optionalField("refreshRate"),
                layerStack = displayInfo.optionalField("layerStack"),
            )
        }.getOrNull()?.also { resolved = it }
    }

    private fun qualify(): DisplayManagerMirrorQualification {
        qualification?.let { return it }
        val api = resolveApi()
            ?: return DisplayManagerMirrorQualification(null, null, CaptureError.HiddenApiUnavailable).also {
                qualification = it
            }
        val defaultDisplay = api.queryDisplayInfo(DEFAULT_DISPLAY_ID)
            ?: return DisplayManagerMirrorQualification(null, null, CaptureError.SourceDisplayNotFound).also {
                qualification = it
            }
        val error = probeMirrorLifecycle(api)
        return DisplayManagerMirrorQualification(
            api = if (error == CaptureError.None) api else null,
            defaultDisplay = if (error == CaptureError.None) defaultDisplay else null,
            error = error,
        ).also { qualification = it }
    }

    private fun probeMirrorLifecycle(api: ResolvedDisplayManagerMirrorApi): CaptureError {
        var reader: ImageReader? = null
        var mirror: Any? = null
        var error = CaptureError.None
        try {
            reader = CaptureQualificationSurface.create()
            if (!reader.surface.isValid) return CaptureError.InvalidTargetSurface
            mirror = api.createMirror.invoke(
                null,
                QUALIFICATION_DISPLAY_NAME,
                QUALIFICATION_WIDTH,
                QUALIFICATION_HEIGHT,
                DEFAULT_DISPLAY_ID,
                reader.surface,
            ) ?: return CaptureError.CaptureCreationFailed
        } catch (throwable: Throwable) {
            error = mapInvocationFailure(throwable, CaptureError.CaptureCreationFailed)
        } finally {
            val released = mirror == null || runCatching { api.release.invoke(mirror) }.isSuccess
            runCatching { reader?.close() }
            if (error == CaptureError.None && !released) {
                error = CaptureError.BackendReleased
            }
        }
        return error
    }

    private fun mapInvocationFailure(throwable: Throwable, fallback: CaptureError): CaptureError {
        val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
        return when (cause) {
            is SecurityException,
            is IllegalAccessException,
            -> CaptureError.CapturePermissionDenied
            is NoSuchMethodException,
            is NoSuchFieldException,
            is ClassNotFoundException,
            -> CaptureError.HiddenApiUnavailable
            else -> fallback
        }
    }

    private fun remember(error: CaptureError): CaptureError {
        lastError = error
        return error
    }

    private companion object {
        const val DEFAULT_DISPLAY_ID = 0
        const val DISPLAY_NAME = "WarpnectCapture"
        const val QUALIFICATION_DISPLAY_NAME = "WarpnectCaptureQualification"
        const val QUALIFICATION_WIDTH = 64
        const val QUALIFICATION_HEIGHT = 64
        const val DISPLAY_MANAGER_CLASS = "android.hardware.display.DisplayManager"
        const val VIRTUAL_DISPLAY_CLASS = "android.hardware.display.VirtualDisplay"
        const val DISPLAY_MANAGER_GLOBAL_CLASS = "android.hardware.display.DisplayManagerGlobal"
        const val DISPLAY_INFO_CLASS = "android.view.DisplayInfo"
        const val CREATE_VIRTUAL_DISPLAY = "createVirtualDisplay"
        const val RELEASE = "release"
        const val GET_INSTANCE = "getInstance"
        const val GET_DISPLAY_INFO = "getDisplayInfo"
    }
}

private data class DisplayManagerMirrorQualification(
    val api: ResolvedDisplayManagerMirrorApi?,
    val defaultDisplay: CaptureDisplayInfo?,
    val error: CaptureError,
)

private data class ResolvedDisplayManagerMirrorApi(
    val createMirror: Method,
    val release: Method,
    val getDisplayManagerGlobal: Method,
    val getDisplayInfo: Method,
    private val logicalWidth: Field,
    private val logicalHeight: Field,
    private val rotation: Field,
    private val refreshRate: Field?,
    private val layerStack: Field?,
) {
    fun queryDisplayInfo(displayId: Int): CaptureDisplayInfo? = runCatching {
        val manager = getDisplayManagerGlobal.invoke(null) ?: return null
        val info = getDisplayInfo.invoke(manager, displayId) ?: return null
        CaptureDisplayInfo(
            displayId = displayId,
            logicalWidth = logicalWidth.getInt(info),
            logicalHeight = logicalHeight.getInt(info),
            rotation = rotation.getInt(info),
            refreshRate = refreshRate?.getFloat(info),
            layerStack = layerStack?.getInt(info) ?: displayId,
        )
    }.getOrNull()
}

private fun Class<*>.publicOrDeclaredMethod(name: String, vararg parameterTypes: Class<*>): Method =
    runCatching { getMethod(name, *parameterTypes) }
        .recoverCatching { getDeclaredMethod(name, *parameterTypes) }
        .getOrThrow()
        .apply { isAccessible = true }

private fun Class<*>.optionalField(name: String): Field? =
    runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
