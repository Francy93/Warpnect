package io.warpnect.platform.capture

import android.view.Surface
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot

internal interface PrivilegedCaptureGateway {
    suspend fun queryCapabilities(): CaptureCapabilities

    suspend fun requestPermission(): CapturePermissionResult

    suspend fun startCapture(request: CaptureRequest, target: Surface): CaptureError

    suspend fun updateCapture(request: CaptureRequest): CaptureError

    suspend fun stopCapture(): CaptureError

    fun snapshot(): CaptureSessionSnapshot

    fun close()
}
