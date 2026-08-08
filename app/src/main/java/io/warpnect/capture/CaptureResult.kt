package io.warpnect.capture

data class CaptureStartResult(
    val error: CaptureError,
    val snapshot: CaptureSessionSnapshot,
) {
    val isSuccess: Boolean
        get() = error == CaptureError.None
}

data class CaptureStopResult(
    val error: CaptureError,
    val snapshot: CaptureSessionSnapshot,
) {
    val isSuccess: Boolean
        get() = error == CaptureError.None
}

data class CapturePermissionResult(
    val error: CaptureError,
    val requestIssued: Boolean = false,
) {
    val isGranted: Boolean
        get() = error == CaptureError.None
}
