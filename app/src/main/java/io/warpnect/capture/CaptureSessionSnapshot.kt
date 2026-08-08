package io.warpnect.capture

data class CaptureSessionSnapshot(
    val state: CaptureState = CaptureState.Stopped,
    val backend: CaptureBackend = CaptureBackend.None,
    val sourceDisplayId: Int? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val sourceRotation: Int? = null,
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val startedAtMonotonicUs: Long? = null,
    val reconfigurationCount: Int = 0,
    val lastError: CaptureError = CaptureError.None,
)
