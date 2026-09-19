package io.warpnect.platform.audio.capture

import android.content.Context
import io.warpnect.BuildConfig
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.telemetry.AudioSenderTelemetry

/**
 * Narrow composition seam for a DEBUG-only SystemAudio source experiment.
 *
 * Release builds and ordinary DEBUG runs always construct the established Shizuku controller.
 * A source must be explicitly armed by DEBUG-only code before the registry can substitute one.
 */
internal interface DebugSystemAudioCaptureOverride {
    fun createController(context: Context, telemetry: AudioSenderTelemetry?): AudioCaptureController
}

/**
 * Process-local DEBUG experiment registration. It deliberately has no persisted state, no wire
 * contract, and no production fallback behavior.
 */
internal object DebugSystemAudioCaptureOverrideRegistry {
    @Volatile
    private var installed: DebugSystemAudioCaptureOverride? = null

    fun install(candidate: DebugSystemAudioCaptureOverride): Boolean {
        if (!BuildConfig.DEBUG) return false
        installed = candidate
        return true
    }

    fun clear(candidate: DebugSystemAudioCaptureOverride? = null) {
        if (!BuildConfig.DEBUG) return
        if (candidate == null || installed === candidate) {
            installed = null
        }
    }

    fun createController(context: Context, telemetry: AudioSenderTelemetry?): AudioCaptureController? {
        if (!BuildConfig.DEBUG) return null
        return installed?.createController(context.applicationContext, telemetry)
    }

    fun hasOverride(): Boolean = BuildConfig.DEBUG && installed != null
}

/** Keeps the established SystemAudio controller as the default for every production path. */
internal object SystemAudioCaptureControllerSelector {
    fun create(context: Context, telemetry: AudioSenderTelemetry? = null): AudioCaptureController =
        DebugSystemAudioCaptureOverrideRegistry.createController(context, telemetry)
            ?: AndroidSystemAudioCaptureController(context, telemetry = telemetry)

    fun queryCapabilities(context: Context, request: AudioCaptureRequest): AudioCaptureCapabilities =
        queryCapabilitiesAndClose(create(context), request)
}
