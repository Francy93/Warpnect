package io.warpnect.input.transport

enum class InputTransportState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}
