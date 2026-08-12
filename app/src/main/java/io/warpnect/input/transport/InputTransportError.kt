package io.warpnect.input.transport

enum class InputTransportError {
    None,
    InvalidConfiguration,
    InvalidEndpoint,
    InvalidDatagramBudget,
    UnsupportedInputMessage,
    InvalidInputEvent,
    PacketEncodeFailed,
    UdpOpenFailed,
    UdpBindFailed,
    UdpSendFailed,
    WouldBlock,
    PartialDatagramSend,
    UnsupportedProtocolVersion,
    UnexpectedPayloadType,
    FragmentedInputUnsupported,
    MalformedInputPayload,
    Closed,
    InvalidHandle,
    NotPrepared,
    NotRunning,
    AlreadyRunning,
    ;

    val isImmediateDrop: Boolean
        get() = this == WouldBlock || this == UdpSendFailed || this == PartialDatagramSend

    companion object {
        fun fromNativeCode(code: Int): InputTransportError = entries.getOrElse(code) { InvalidHandle }
    }
}
