package io.warpnect.platform.capture.privileged

import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Surface
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureGeometry
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState
import io.warpnect.platform.capture.CaptureClock
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class SurfaceControlDisplayCaptureApi : PrivilegedDisplayCaptureApi {
    private var resolved: ResolvedSurfaceControlApi? = null
    private var displayToken: IBinder? = null
    private var activeRequest: CaptureRequest? = null
    private var activeDisplayInfo: CaptureDisplayInfo? = null
    private var startedAtMonotonicUs: Long? = null
    private var reconfigurationCount = 0
    private var lastError = CaptureError.None

    override fun queryCapabilities(): CaptureCapabilities {
        val api = resolveApi()
        val defaultDisplay = api?.queryDisplayInfo(DEFAULT_DISPLAY_ID)
        val error = when {
            api == null -> CaptureError.HiddenApiUnavailable
            defaultDisplay == null -> CaptureError.SourceDisplayNotFound
            else -> CaptureError.None
        }
        return CaptureCapabilities(
            privilegeState = if (error == CaptureError.None) {
                CapturePrivilegeState.Ready
            } else {
                CapturePrivilegeState.BackendUnavailable
            },
            backend = if (api != null) CaptureBackend.SurfaceControlDisplay else CaptureBackend.None,
            backendAvailable = error == CaptureError.None,
            supportedSourceDisplays = listOfNotNull(defaultDisplay),
            supportsDynamicProjection = api != null,
            platformApiLevel = Build.VERSION.SDK_INT,
            lastError = error,
        )
    }

    override fun startCapture(request: CaptureRequest, targetSurface: Surface): CaptureError {
        if (displayToken != null) {
            return remember(CaptureError.AlreadyRunning)
        }
        val api = resolveApi() ?: return remember(CaptureError.HiddenApiUnavailable)
        val displayInfo = api.queryDisplayInfo(request.sourceDisplayId)
            ?: return remember(CaptureError.SourceDisplayNotFound)

        val token = try {
            api.createDisplay.invoke(null, DISPLAY_NAME, true) as? IBinder
                ?: return remember(CaptureError.CaptureCreationFailed)
        } catch (throwable: Throwable) {
            return remember(mapInvocationFailure(throwable, CaptureError.CaptureCreationFailed))
        }

        displayToken = token
        activeRequest = request
        activeDisplayInfo = displayInfo
        startedAtMonotonicUs = CaptureClock.monotonicUs()
        reconfigurationCount = 0

        val configured = configureDisplay(api, token, displayInfo, request, targetSurface)
        if (configured != CaptureError.None) {
            stopCapture()
            return remember(configured)
        }
        return remember(CaptureError.None)
    }

    override fun updateCapture(request: CaptureRequest): CaptureError {
        val token = displayToken ?: return remember(CaptureError.NotRunning)
        val api = resolveApi() ?: return remember(CaptureError.HiddenApiUnavailable)
        val displayInfo = api.queryDisplayInfo(request.sourceDisplayId)
            ?: return remember(CaptureError.DisplayRemoved)
        val currentTarget = activeRequest ?: return remember(CaptureError.NotRunning)
        val updatedRequest = request.copy(
            outputWidth = currentTarget.outputWidth,
            outputHeight = currentTarget.outputHeight,
        )
        val configured = configureDisplay(
            api = api,
            token = token,
            displayInfo = displayInfo,
            request = updatedRequest,
            targetSurface = null,
        )
        if (configured == CaptureError.None) {
            activeRequest = updatedRequest
            activeDisplayInfo = displayInfo
            reconfigurationCount += 1
        }
        return remember(configured)
    }

    override fun stopCapture(): CaptureError {
        val token = displayToken ?: return CaptureError.None
        val api = resolved
        displayToken = null
        activeRequest = null
        activeDisplayInfo = null
        startedAtMonotonicUs = null
        return if (api == null) {
            remember(CaptureError.None)
        } else {
            try {
                runCatching { api.setDisplaySurface.invoke(null, token, null) }
                api.destroyDisplay.invoke(null, token)
                remember(CaptureError.None)
            } catch (throwable: Throwable) {
                remember(mapInvocationFailure(throwable, CaptureError.BackendReleased))
            }
        }
    }

    override fun snapshot(): CaptureSessionSnapshot {
        val request = activeRequest
        val displayInfo = activeDisplayInfo
        return CaptureSessionSnapshot(
            state = when {
                displayToken != null && lastError == CaptureError.None -> CaptureState.Running
                displayToken != null -> CaptureState.Error
                else -> CaptureState.Stopped
            },
            backend = if (resolved != null) CaptureBackend.SurfaceControlDisplay else CaptureBackend.None,
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

    private fun configureDisplay(
        api: ResolvedSurfaceControlApi,
        token: IBinder,
        displayInfo: CaptureDisplayInfo,
        request: CaptureRequest,
        targetSurface: Surface?,
    ): CaptureError {
        val projection = try {
            CaptureGeometry.computeProjection(
                sourceWidth = displayInfo.logicalWidth,
                sourceHeight = displayInfo.logicalHeight,
                sourceRotation = if (request.followSourceRotation) displayInfo.rotation else 0,
                targetWidth = request.outputWidth,
                targetHeight = request.outputHeight,
            )
        } catch (_: IllegalArgumentException) {
            return CaptureError.ProjectionConfigurationFailed
        }

        return try {
            if (targetSurface != null) {
                api.setDisplaySurface.invoke(null, token, targetSurface)
            }
            api.setDisplayLayerStack.invoke(null, token, displayInfo.layerStack)
            api.setDisplayProjection.invoke(
                null,
                token,
                projection.orientation,
                Rect(
                    projection.sourceCrop.left,
                    projection.sourceCrop.top,
                    projection.sourceCrop.right,
                    projection.sourceCrop.bottom,
                ),
                Rect(
                    projection.targetRect.left,
                    projection.targetRect.top,
                    projection.targetRect.right,
                    projection.targetRect.bottom,
                ),
            )
            CaptureError.None
        } catch (throwable: Throwable) {
            mapInvocationFailure(throwable, CaptureError.ProjectionConfigurationFailed)
        }
    }

    private fun resolveApi(): ResolvedSurfaceControlApi? {
        resolved?.let { return it }
        return runCatching {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            val displayManagerGlobal = Class.forName("android.hardware.display.DisplayManagerGlobal")
            ResolvedSurfaceControlApi(
                createDisplay = surfaceControl.method(
                    "createDisplay",
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ),
                destroyDisplay = surfaceControl.method("destroyDisplay", IBinder::class.java),
                setDisplaySurface = surfaceControl.method(
                    "setDisplaySurface",
                    IBinder::class.java,
                    Surface::class.java,
                ),
                setDisplayProjection = surfaceControl.method(
                    "setDisplayProjection",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!,
                    Rect::class.java,
                    Rect::class.java,
                ),
                setDisplayLayerStack = surfaceControl.method(
                    "setDisplayLayerStack",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
                getDisplayManagerGlobal = displayManagerGlobal.method("getInstance"),
                getDisplayInfo = displayManagerGlobal.method(
                    "getDisplayInfo",
                    Int::class.javaPrimitiveType!!,
                ),
            )
        }.getOrNull()?.also { resolved = it }
    }

    private fun remember(error: CaptureError): CaptureError {
        lastError = error
        return error
    }

    private fun mapInvocationFailure(throwable: Throwable, fallback: CaptureError): CaptureError {
        val cause = if (throwable is InvocationTargetException) throwable.targetException else throwable
        return when (cause) {
            is SecurityException -> CaptureError.CapturePermissionDenied
            is NoSuchMethodException,
            is NoSuchFieldException,
            is ClassNotFoundException,
            -> CaptureError.HiddenApiUnavailable
            else -> fallback
        }
    }

    private companion object {
        const val DEFAULT_DISPLAY_ID = 0
        const val DISPLAY_NAME = "WarpnectCapture"
    }
}

private data class ResolvedSurfaceControlApi(
    val createDisplay: Method,
    val destroyDisplay: Method,
    val setDisplaySurface: Method,
    val setDisplayProjection: Method,
    val setDisplayLayerStack: Method,
    val getDisplayManagerGlobal: Method,
    val getDisplayInfo: Method,
) {
    private val logicalWidth = displayInfoField("logicalWidth")
    private val logicalHeight = displayInfoField("logicalHeight")
    private val rotation = displayInfoField("rotation")
    private val refreshRate = displayInfoFieldOrNull("refreshRate")
    private val layerStack = displayInfoFieldOrNull("layerStack")

    fun queryDisplayInfo(displayId: Int): CaptureDisplayInfo? {
        val manager = getDisplayManagerGlobal.invoke(null) ?: return null
        val info = getDisplayInfo.invoke(manager, displayId) ?: return null
        return CaptureDisplayInfo(
            displayId = displayId,
            logicalWidth = logicalWidth.getInt(info),
            logicalHeight = logicalHeight.getInt(info),
            rotation = rotation.getInt(info),
            refreshRate = refreshRate?.getFloat(info),
            layerStack = layerStack?.getInt(info) ?: displayId,
        )
    }

    private fun displayInfoField(name: String): Field =
        displayInfoClass().getDeclaredField(name).apply { isAccessible = true }

    private fun displayInfoFieldOrNull(name: String): Field? = runCatching { displayInfoField(name) }.getOrNull()

    private fun displayInfoClass(): Class<*> = Class.forName("android.view.DisplayInfo")
}

private fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method =
    getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
