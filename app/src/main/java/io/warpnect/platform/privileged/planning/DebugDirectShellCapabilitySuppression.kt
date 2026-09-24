package io.warpnect.platform.privileged.planning

import io.warpnect.BuildConfig

/**
 * One-shot DEBUG-only eligibility suppression for controlled composite-plan validation. It does
 * not fabricate another provider's success and cannot survive process recreation or one Host
 * admission attempt.
 */
internal object DebugDirectShellCapabilitySuppression {
    @Volatile
    private var pending: PrivilegedCapability? = null

    fun arm(capability: PrivilegedCapability): Boolean {
        if (!BuildConfig.DEBUG) return false
        pending = capability
        return true
    }

    fun clear() {
        pending = null
    }

    /**
     * Lets the DEBUG runtime avoid starting a deliberately suppressed feature before planning.
     * The value remains pending until admission consumes it, so matrix construction and runtime
     * provisioning describe the same one-shot Host attempt.
     */
    fun peek(): PrivilegedCapability? = if (BuildConfig.DEBUG) pending else null

    fun consume(): PrivilegedCapability? {
        if (!BuildConfig.DEBUG) return null
        return pending.also { pending = null }
    }
}
