package io.warpnect.platform.capture.privileged

import android.os.Bundle
import android.view.Surface
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest

class PrivilegedCaptureUserService : IPrivilegedCaptureService.Stub() {
    private val captureApi: PrivilegedDisplayCaptureApi = QualifiedPrivilegedDisplayCaptureApi()

    override fun queryCapabilities(): Bundle = captureApi.queryCapabilities().toBundle()

    override fun startCapture(
        sourceDisplayId: Int,
        outputWidth: Int,
        outputHeight: Int,
        followSourceRotation: Boolean,
        targetSurface: Surface?,
    ): Int {
        if (targetSurface == null || !targetSurface.isValid) {
            return CaptureError.InvalidTargetSurface.code
        }
        if (outputWidth <= 0 || outputHeight <= 0) {
            return CaptureError.InvalidCaptureDimensions.code
        }
        return captureApi.startCapture(
            request = CaptureRequest(
                sourceDisplayId = sourceDisplayId,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                followSourceRotation = followSourceRotation,
            ),
            targetSurface = targetSurface,
        ).code
    }

    override fun updateCapture(
        sourceDisplayId: Int,
        outputWidth: Int,
        outputHeight: Int,
        followSourceRotation: Boolean,
    ): Int {
        if (outputWidth <= 0 || outputHeight <= 0) {
            return CaptureError.InvalidCaptureDimensions.code
        }
        return captureApi.updateCapture(
            CaptureRequest(
                sourceDisplayId = sourceDisplayId,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                followSourceRotation = followSourceRotation,
            ),
        ).code
    }

    override fun stopCapture(): Int = captureApi.stopCapture().code

    override fun getState(): Bundle = captureApi.snapshot().toBundle()

    @Suppress("unused")
    fun destroy() {
        captureApi.stopCapture()
    }
}
