package io.warpnect.platform.capture.privileged

import android.graphics.Rect
import android.media.ImageReader
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import io.warpnect.BuildConfig
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
    private var lastResolutionFailure: CaptureBridgeResolutionFailure? = null
    private var qualification: LegacySurfaceControlQualification? = null
    private var displayToken: IBinder? = null
    private var activeRequest: CaptureRequest? = null
    private var activeDisplayInfo: CaptureDisplayInfo? = null
    private var startedAtMonotonicUs: Long? = null
    private var reconfigurationCount = 0
    private var lastError = CaptureError.None

    override fun queryCapabilities(): CaptureCapabilities {
        val qualified = qualify()
        val api = qualified.api
        val error = qualified.error
        val defaultDisplay = api?.queryDisplayInfo(DEFAULT_DISPLAY_ID)
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
        val qualified = qualify()
        val api = qualified.api ?: return remember(qualified.error)
        val secure = qualified.secure ?: return remember(qualified.error)
        val displayInfo = api.queryDisplayInfo(request.sourceDisplayId)
            ?: return remember(CaptureError.SourceDisplayNotFound)

        val token = try {
            api.createDisplay.invoke(null, DISPLAY_NAME, secure) as? IBinder
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
        val api = resolveApi() ?: return rememberHiddenApiUnavailable()
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
            remember(releaseDisplay(api, token))
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
            LegacySurfaceControlTransactionRunner.configure(
                open = { api.openTransaction.invoke(null) },
                attachSurface = targetSurface?.let { surface ->
                    { api.setDisplaySurface.invoke(null, token, surface) }
                },
                setProjection = {
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
                },
                setLayerStack = { api.setDisplayLayerStack.invoke(null, token, displayInfo.layerStack) },
                close = { api.closeTransaction.invoke(null) },
            )
            CaptureError.None
        } catch (throwable: Throwable) {
            mapInvocationFailure(throwable, CaptureError.ProjectionConfigurationFailed)
        }
    }

    private fun qualify(): LegacySurfaceControlQualification {
        qualification?.let { return it }
        val api = resolveApi()
            ?: return LegacySurfaceControlQualification(null, null, CaptureError.HiddenApiUnavailable).also {
                qualification = it
            }
        val defaultDisplay = api.queryDisplayInfo(DEFAULT_DISPLAY_ID)
        if (defaultDisplay == null) {
            return LegacySurfaceControlQualification(null, null, CaptureError.SourceDisplayNotFound).also {
                qualification = it
            }
        }

        val secure = LegacySecureModeSelector().select { candidate ->
            probeSecureMode(api, defaultDisplay, candidate)
        }
        secure.secure?.let(CaptureBridgeDebugLog::legacySecureModeQualified)
        return LegacySurfaceControlQualification(
            api = if (secure.error == CaptureError.None) api else null,
            secure = secure.secure,
            error = secure.error,
        ).also { qualification = it }
    }

    private fun probeSecureMode(
        api: ResolvedSurfaceControlApi,
        displayInfo: CaptureDisplayInfo,
        secure: Boolean,
    ): CaptureError {
        val token = try {
            api.createDisplay.invoke(null, QUALIFICATION_DISPLAY_NAME, secure) as? IBinder
                ?: return CaptureError.CaptureCreationFailed
        } catch (throwable: Throwable) {
            return mapInvocationFailure(throwable, CaptureError.CaptureCreationFailed)
        }
        var reader: ImageReader? = null
        var error = CaptureError.None
        try {
            reader = CaptureQualificationSurface.create()
            if (!reader.surface.isValid) return CaptureError.InvalidTargetSurface
            error = configureDisplay(
                api = api,
                token = token,
                displayInfo = displayInfo,
                request = CaptureRequest(
                    sourceDisplayId = DEFAULT_DISPLAY_ID,
                    outputWidth = QUALIFICATION_WIDTH,
                    outputHeight = QUALIFICATION_HEIGHT,
                ),
                targetSurface = reader.surface,
            )
        } finally {
            val released = releaseDisplay(api, token)
            runCatching { reader?.close() }
            if (error == CaptureError.None && released != CaptureError.None) {
                error = released
            }
        }
        return error
    }

    private fun releaseDisplay(api: ResolvedSurfaceControlApi, token: IBinder): CaptureError {
        var detachFailure: Throwable? = null
        try {
            api.openTransaction.invoke(null)
            try {
                api.setDisplaySurface.invoke(null, token, null)
            } finally {
                api.closeTransaction.invoke(null)
            }
        } catch (throwable: Throwable) {
            detachFailure = throwable
        }
        return try {
            api.destroyDisplay.invoke(null, token)
            detachFailure?.let { mapInvocationFailure(it, CaptureError.BackendReleased) }
                ?: CaptureError.None
        } catch (throwable: Throwable) {
            mapInvocationFailure(throwable, CaptureError.BackendReleased)
        }
    }

    private fun resolveApi(): ResolvedSurfaceControlApi? {
        resolved?.let { return it }
        CaptureBridgeDebugLog.resolutionStarted()
        lastResolutionFailure = null

        val surfaceControl = resolveClass(
            name = "android.view.SurfaceControl",
            component = CaptureBridgeComponent.SurfaceControlClass,
        ) ?: return null
        val displayManagerGlobal = resolveClass(
            name = "android.hardware.display.DisplayManagerGlobal",
            component = CaptureBridgeComponent.DisplayManagerGlobalClass,
        ) ?: return null
        val displayInfo = resolveClass(
            name = "android.view.DisplayInfo",
            component = CaptureBridgeComponent.DisplayInfoClass,
        ) ?: return null

        return ResolvedSurfaceControlApi(
            createDisplay = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.CreateDisplay,
                "createDisplay",
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
            ) ?: return null,
            destroyDisplay = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.DestroyDisplay,
                "destroyDisplay",
                IBinder::class.java,
            ) ?: return null,
            setDisplaySurface = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.SetDisplaySurface,
                "setDisplaySurface",
                IBinder::class.java,
                Surface::class.java,
            ) ?: return null,
            setDisplayProjection = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.SetDisplayProjection,
                "setDisplayProjection",
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
                Rect::class.java,
                Rect::class.java,
            ) ?: return null,
            setDisplayLayerStack = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.SetDisplayLayerStack,
                "setDisplayLayerStack",
                IBinder::class.java,
                Int::class.javaPrimitiveType!!,
            ) ?: return null,
            openTransaction = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.OpenTransaction,
                "openTransaction",
            ) ?: return null,
            closeTransaction = resolveMethod(
                surfaceControl,
                CaptureBridgeComponent.CloseTransaction,
                "closeTransaction",
            ) ?: return null,
            getDisplayManagerGlobal = resolveMethod(
                displayManagerGlobal,
                CaptureBridgeComponent.GetDisplayManagerGlobal,
                "getInstance",
            ) ?: return null,
            getDisplayInfo = resolveMethod(
                displayManagerGlobal,
                CaptureBridgeComponent.GetDisplayInfo,
                "getDisplayInfo",
                Int::class.javaPrimitiveType!!,
            ) ?: return null,
            logicalWidth = resolveField(
                displayInfo,
                CaptureBridgeComponent.DisplayLogicalWidth,
                "logicalWidth",
            ) ?: return null,
            logicalHeight = resolveField(
                displayInfo,
                CaptureBridgeComponent.DisplayLogicalHeight,
                "logicalHeight",
            ) ?: return null,
            rotation = resolveField(
                displayInfo,
                CaptureBridgeComponent.DisplayRotation,
                "rotation",
            ) ?: return null,
            refreshRate = resolveOptionalField(displayInfo, "refreshRate"),
            layerStack = resolveOptionalField(displayInfo, "layerStack"),
        ).also {
            resolved = it
            CaptureBridgeDebugLog.resolved(CaptureBridgeComponent.Complete)
        }
    }

    private fun resolveClass(name: String, component: CaptureBridgeComponent): Class<*>? =
        runCatching { Class.forName(name) }
            .onSuccess { CaptureBridgeDebugLog.resolved(component) }
            .getOrElse { resolutionFailed(component, it) }

    private fun resolveMethod(
        owner: Class<*>,
        component: CaptureBridgeComponent,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? = runCatching { owner.method(name, *parameterTypes) }
        .onSuccess { CaptureBridgeDebugLog.resolved(component) }
        .getOrElse { resolutionFailed(component, it) }

    private fun resolveField(owner: Class<*>, component: CaptureBridgeComponent, name: String): Field? =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }
            .onSuccess { CaptureBridgeDebugLog.resolved(component) }
            .getOrElse { resolutionFailed(component, it) }

    private fun resolveOptionalField(owner: Class<*>, name: String): Field? =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private fun resolutionFailed(component: CaptureBridgeComponent, throwable: Throwable): Nothing? {
        val failure = CaptureBridgeResolutionFailure.from(throwable)
        lastResolutionFailure = failure
        CaptureBridgeDebugLog.missing(component, failure)
        return null
    }

    private fun rememberHiddenApiUnavailable(): CaptureError {
        CaptureBridgeDebugLog.startFailed(
            reason = CaptureError.HiddenApiUnavailable,
            detail = lastResolutionFailure,
        )
        return remember(CaptureError.HiddenApiUnavailable)
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
        const val QUALIFICATION_DISPLAY_NAME = "WarpnectCaptureQualification"
        const val QUALIFICATION_WIDTH = 64
        const val QUALIFICATION_HEIGHT = 64
    }
}

internal class LegacySecureModeSelector {
    fun select(probe: (Boolean) -> CaptureError): LegacySecureModeQualification {
        val secureResult = probe(true)
        if (secureResult == CaptureError.None) {
            return LegacySecureModeQualification(secure = true, error = CaptureError.None)
        }
        val insecureResult = probe(false)
        return if (insecureResult == CaptureError.None) {
            LegacySecureModeQualification(secure = false, error = CaptureError.None)
        } else {
            LegacySecureModeQualification(secure = null, error = insecureResult)
        }
    }
}

internal data class LegacySecureModeQualification(
    val secure: Boolean?,
    val error: CaptureError,
)

private data class LegacySurfaceControlQualification(
    val api: ResolvedSurfaceControlApi?,
    val secure: Boolean?,
    val error: CaptureError,
)

private data class ResolvedSurfaceControlApi(
    val createDisplay: Method,
    val destroyDisplay: Method,
    val setDisplaySurface: Method,
    val setDisplayProjection: Method,
    val setDisplayLayerStack: Method,
    val openTransaction: Method,
    val closeTransaction: Method,
    val getDisplayManagerGlobal: Method,
    val getDisplayInfo: Method,
    private val logicalWidth: Field,
    private val logicalHeight: Field,
    private val rotation: Field,
    private val refreshRate: Field?,
    private val layerStack: Field?,
) {
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
}

private fun Class<*>.method(name: String, vararg parameterTypes: Class<*>): Method =
    getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }

private enum class CaptureBridgeComponent {
    SurfaceControlClass,
    DisplayManagerGlobalClass,
    DisplayInfoClass,
    CreateDisplay,
    DestroyDisplay,
    SetDisplaySurface,
    SetDisplayProjection,
    SetDisplayLayerStack,
    OpenTransaction,
    CloseTransaction,
    GetDisplayManagerGlobal,
    GetDisplayInfo,
    DisplayLogicalWidth,
    DisplayLogicalHeight,
    DisplayRotation,
    Complete,
}

private enum class CaptureBridgeResolutionFailure {
    ClassNotFound,
    MethodNotFound,
    FieldNotFound,
    AccessDenied,
    Unexpected,
    ;

    companion object {
        fun from(throwable: Throwable): CaptureBridgeResolutionFailure = when (throwable) {
            is ClassNotFoundException,
            is NoClassDefFoundError,
            -> ClassNotFound
            is NoSuchMethodException -> MethodNotFound
            is NoSuchFieldException -> FieldNotFound
            is IllegalAccessException,
            is SecurityException,
            -> AccessDenied
            else -> Unexpected
        }
    }
}

private object CaptureBridgeDebugLog {
    private const val TAG = "WarpnectDiscovery"

    fun resolutionStarted() = log("event=capture_bridge_resolution_started")

    fun resolved(component: CaptureBridgeComponent) = log("event=capture_hidden_api_resolved component=$component")

    fun missing(component: CaptureBridgeComponent, reason: CaptureBridgeResolutionFailure) =
        log("event=capture_hidden_api_missing component=$component reason=$reason")

    fun legacySecureModeQualified(secure: Boolean) = log("event=capture_legacy_secure_mode_qualified secure=$secure")

    fun startFailed(reason: CaptureError, detail: CaptureBridgeResolutionFailure?) {
        val message = buildString {
            append("event=capture_gateway_start_failed reason=")
            append(reason.name)
            detail?.let {
                append(" detail=")
                append(it.name)
            }
        }
        log(message)
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
