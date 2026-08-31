package io.warpnect.session.lifecycle

/**
 * Development-only lifecycle-start milestones for bounded physical-device investigation.
 * Events contain only fixed state and error enums; they intentionally expose no Session data.
 */
fun interface SessionLifecycleDebugObserver {
    fun onEvent(event: SessionLifecycleDebugEvent)

    companion object {
        val None = SessionLifecycleDebugObserver {}
    }
}

data class SessionLifecycleDebugEvent(
    val kind: SessionLifecycleDebugEventKind,
    val error: SessionLifecycleError? = null,
)

enum class SessionLifecycleDebugEventKind {
    StartRequested,
    StartRejectedClosed,
    StartRejectedMissingCapacity,
    StartRejectedBootstrapTransfer,
    StartRejectedCapacityPromotion,
    StartSucceeded,
}
