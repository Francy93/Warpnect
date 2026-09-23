package io.warpnect.platform.input.injection

import android.content.Context
import io.warpnect.BuildConfig
import io.warpnect.input.injection.InputInjectionController

/**
 * Narrow composition seam for an explicitly armed DEBUG-only privileged Input experiment.
 *
 * Release builds and ordinary DEBUG runs keep constructing the established Shizuku controller.
 * The registry is process-local by design: it has no persisted state, wire contract, or fallback.
 */
internal interface DebugInputInjectionControllerOverride {
    fun createController(context: Context): InputInjectionController
}

internal object DebugInputInjectionControllerOverrideRegistry {
    @Volatile
    private var installed: DebugInputInjectionControllerOverride? = null

    fun install(candidate: DebugInputInjectionControllerOverride): Boolean {
        if (!BuildConfig.DEBUG) return false
        installed = candidate
        return true
    }

    fun clear(candidate: DebugInputInjectionControllerOverride? = null) {
        if (!BuildConfig.DEBUG) return
        if (candidate == null || installed === candidate) {
            installed = null
        }
    }

    fun createController(context: Context): InputInjectionController? {
        if (!BuildConfig.DEBUG) return null
        return installed?.createController(context.applicationContext)
    }

    fun hasOverride(): Boolean = BuildConfig.DEBUG && installed != null
}

/** Keeps Shizuku as the default Input backend until an explicit production migration is approved. */
internal object InputInjectionControllerSelector {
    fun create(context: Context): InputInjectionController =
        DebugInputInjectionControllerOverrideRegistry.createController(context)
            ?: AndroidInputInjectionController(context)
}
