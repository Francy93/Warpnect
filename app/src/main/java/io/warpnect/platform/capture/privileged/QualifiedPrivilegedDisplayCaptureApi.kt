package io.warpnect.platform.capture.privileged

import android.os.Build
import android.util.Log
import android.view.Surface
import io.warpnect.BuildConfig
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot

/**
 * Selects one privileged display-mirror strategy before capture begins. The selection is retained
 * for this UserService lifetime so an active or retried capture cannot silently move between
 * Android backends.
 */
internal class QualifiedPrivilegedDisplayCaptureApi(
    private val strategies: List<PrivilegedDisplayCaptureApi> = listOf(
        DisplayManagerMirrorCaptureApi(),
        SurfaceControlDisplayCaptureApi(),
    ),
) : PrivilegedDisplayCaptureApi {
    private var selection: StrategySelection? = null
    private var activeStrategy: PrivilegedDisplayCaptureApi? = null
    private var lastError = CaptureError.None

    override fun queryCapabilities(): CaptureCapabilities = selectStrategy().capabilities

    override fun startCapture(request: CaptureRequest, targetSurface: Surface): CaptureError {
        if (activeStrategy != null) {
            return remember(CaptureError.AlreadyRunning)
        }
        val selected = selectStrategy()
        val strategy = selected.strategy ?: return remember(CaptureError.CaptureBackendUnavailable)
        CaptureStrategyDebugLog.startRequested(selected.capabilities.backend)
        val error = strategy.startCapture(request, targetSurface)
        return if (error == CaptureError.None) {
            activeStrategy = strategy
            CaptureStrategyDebugLog.started(selected.capabilities.backend)
            remember(CaptureError.None)
        } else {
            CaptureStrategyDebugLog.startFailed(selected.capabilities.backend, error)
            remember(error)
        }
    }

    override fun updateCapture(request: CaptureRequest): CaptureError {
        val strategy = activeStrategy ?: return remember(CaptureError.NotRunning)
        return remember(strategy.updateCapture(request))
    }

    override fun stopCapture(): CaptureError {
        val strategy = activeStrategy ?: return CaptureError.None
        activeStrategy = null
        val error = strategy.stopCapture()
        CaptureStrategyDebugLog.stopped(strategy.snapshot().backend, error)
        return remember(error)
    }

    override fun snapshot(): CaptureSessionSnapshot = activeStrategy?.snapshot()
        ?: CaptureSessionSnapshot(
            backend = selection?.capabilities?.backend ?: CaptureBackend.None,
            lastError = lastError,
        )

    private fun selectStrategy(): StrategySelection {
        selection?.let { return it }

        val qualifications = strategies.map { strategy -> strategy to strategy.queryCapabilities() }
        qualifications.forEach { (_, capabilities) ->
            CaptureStrategyDebugLog.qualified(
                capabilities.backend,
                available = capabilities.backendAvailable,
                capabilities.lastError,
            )
        }
        val qualified = qualifications.firstOrNull { (_, capabilities) -> capabilities.backendAvailable }
        val selected = if (qualified != null) {
            val (strategy, capabilities) = qualified
            StrategySelection(strategy, capabilities)
        } else {
            StrategySelection(
                strategy = null,
                capabilities = CaptureCapabilities(
                    privilegeState = CapturePrivilegeState.BackendUnavailable,
                    backend = CaptureBackend.None,
                    backendAvailable = false,
                    supportedSourceDisplays = emptyList(),
                    supportsDynamicProjection = false,
                    platformApiLevel = Build.VERSION.SDK_INT,
                    lastError = CaptureError.CaptureBackendUnavailable,
                ),
            )
        }
        selection = selected
        if (selected.strategy != null) {
            CaptureStrategyDebugLog.selected(selected.capabilities.backend)
        }
        return selected
    }

    private fun remember(error: CaptureError): CaptureError {
        lastError = error
        return error
    }

    private data class StrategySelection(
        val strategy: PrivilegedDisplayCaptureApi?,
        val capabilities: CaptureCapabilities,
    )
}

private object CaptureStrategyDebugLog {
    private const val TAG = "WarpnectCapture"

    fun qualified(backend: CaptureBackend, available: Boolean, reason: CaptureError) {
        log("event=capture_strategy_qualified strategy=$backend available=$available reason=${reason.name}")
    }

    fun selected(backend: CaptureBackend) = log("event=capture_strategy_selected strategy=$backend")

    fun startRequested(backend: CaptureBackend) = log("event=capture_mirror_start_requested strategy=$backend")

    fun started(backend: CaptureBackend) = log("event=capture_mirror_started strategy=$backend")

    fun startFailed(backend: CaptureBackend, reason: CaptureError) =
        log("event=capture_mirror_failed strategy=$backend reason=${reason.name}")

    fun stopped(backend: CaptureBackend, reason: CaptureError) =
        log("event=capture_mirror_stopped strategy=$backend reason=${reason.name}")

    private fun log(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(TAG, message) }
    }
}
