package io.warpnect.session.handshake

/**
 * Optional development-only bootstrap breadcrumbs. They expose only fixed WNSH message metadata
 * and normalized errors, never packet bytes, endpoints, attempt IDs, or identity material.
 */
fun interface SessionHandshakeDebugObserver {
    fun onEvent(event: SessionHandshakeDebugEvent)
}

data class SessionHandshakeDebugEvent(
    val kind: SessionHandshakeDebugEventKind,
    val messageType: SessionHandshakeMessageType? = null,
    val messageSequence: Int? = null,
    val error: SessionHandshakeError? = null,
)

enum class SessionHandshakeDebugEventKind {
    Started,
    ActionSent,
    ActionReceived,
    PairingTrustBoundaryReset,
    Authenticated,
    Failed,
}
