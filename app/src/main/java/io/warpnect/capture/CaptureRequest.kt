package io.warpnect.capture

data class CaptureRequest(
    val sourceDisplayId: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val followSourceRotation: Boolean = true,
)
