package io.warpnect.capture

enum class CaptureState {
    Stopped,
    Starting,
    Running,
    Reconfiguring,
    Stopping,
    Error,
}
