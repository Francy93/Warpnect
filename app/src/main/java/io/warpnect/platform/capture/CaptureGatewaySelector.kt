package io.warpnect.platform.capture

import android.content.Context
import io.warpnect.BuildConfig

/**
 * Narrow composition seam for an explicitly armed DEBUG-only privileged display-capture probe.
 *
 * Ordinary DEBUG runs and every release build continue to create the existing Shizuku gateway.
 * The override is process-local and has no persisted capability or wire-protocol effect.
 */
internal interface DebugCaptureGatewayOverride {
    fun createGateway(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway
}

internal object DebugCaptureGatewayOverrideRegistry {
    @Volatile
    private var installed: DebugCaptureGatewayOverride? = null

    fun install(candidate: DebugCaptureGatewayOverride): Boolean {
        if (!BuildConfig.DEBUG) return false
        installed = candidate
        return true
    }

    fun clear(candidate: DebugCaptureGatewayOverride? = null) {
        if (!BuildConfig.DEBUG) return
        if (candidate == null || installed === candidate) {
            installed = null
        }
    }

    fun createGateway(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway? {
        if (!BuildConfig.DEBUG) return null
        return installed?.createGateway(context.applicationContext, onServiceDied)
    }

    fun hasOverride(): Boolean = BuildConfig.DEBUG && installed != null
}

/** Keeps Shizuku as the display-capture default until an explicit production migration is approved. */
internal object CaptureGatewaySelector {
    fun create(context: Context, onServiceDied: () -> Unit): PrivilegedCaptureGateway =
        DebugCaptureGatewayOverrideRegistry.createGateway(context, onServiceDied)
            ?: ShizukuCaptureGateway(context.applicationContext, onServiceDied)
}
