package io.warpnect.session.integration

/**
 * Optional development-only milestones for the bounded Session-to-media startup transaction.
 * Events contain only fixed state and error enums; they intentionally expose no peer or media data.
 */
fun interface SessionStartupDebugObserver {
    fun onEvent(event: SessionStartupDebugEvent)

    companion object {
        val None = SessionStartupDebugObserver {}
    }
}

data class SessionStartupDebugEvent(
    val kind: SessionStartupDebugEventKind,
    val error: SecureSessionIntegrationError? = null,
)

enum class SessionStartupDebugEventKind {
    Authenticated,
    SecureControlReady,
    CapabilityNegotiationStarted,
    CapabilityNegotiated,
    SessionSetupStarted,
    SessionPrepared,
    VideoChannelReady,
    MediaStartRequested,
    MediaStartAccepted,
    RuntimeRunning,
    Failed,
}
