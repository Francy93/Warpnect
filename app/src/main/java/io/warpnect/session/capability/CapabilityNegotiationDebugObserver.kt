package io.warpnect.session.capability

/**
 * Optional development-only capability control milestones.
 *
 * Events carry only fixed protocol-state and error enums: never capability payloads, device
 * identities, endpoints, or protected control data.
 */
fun interface CapabilityNegotiationDebugObserver {
    fun onEvent(event: CapabilityNegotiationDebugEvent)

    companion object {
        val None = CapabilityNegotiationDebugObserver {}
    }
}

data class CapabilityNegotiationDebugEvent(
    val kind: CapabilityNegotiationDebugEventKind,
    val error: CapabilityNegotiationError? = null,
)

enum class CapabilityNegotiationDebugEventKind {
    ClientOfferSent,
    ClientOfferReceived,
    HostSelectionSent,
    HostSelectionReceived,
    ClientConfirmSent,
    ClientConfirmReceived,
    HostCompleteSent,
    HostCompleteReceived,
    Completed,
    Failed,
}
