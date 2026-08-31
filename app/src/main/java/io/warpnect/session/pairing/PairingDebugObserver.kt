package io.warpnect.session.pairing

/**
 * Optional development-only control-plane breadcrumbs. Implementations receive only static event,
 * message-type, and normalized-error enums; they never receive pairing material or peer identity.
 */
fun interface PairingDebugObserver {
    fun onEvent(event: PairingDebugEvent)
}

data class PairingDebugEvent(
    val kind: PairingDebugEventKind,
    val messageType: PairingMessageType? = null,
    val error: PairingError? = null,
)

enum class PairingDebugEventKind {
    AttemptStarted,
    SasReady,
    LocalConfirm,
    RemoteConfirmReceived,
    LocalReject,
    RemoteRejectReceived,
    ActionSent,
    ActionReceived,
    Succeeded,
    Failed,
    ResetStarted,
    ResetComplete,
}
