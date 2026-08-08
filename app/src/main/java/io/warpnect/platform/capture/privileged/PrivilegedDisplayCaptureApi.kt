package io.warpnect.platform.capture.privileged

import android.view.Surface
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot

internal interface PrivilegedDisplayCaptureApi {
    fun queryCapabilities(): CaptureCapabilities

    fun startCapture(request: CaptureRequest, targetSurface: Surface): CaptureError

    fun updateCapture(request: CaptureRequest): CaptureError

    fun stopCapture(): CaptureError

    fun snapshot(): CaptureSessionSnapshot
}
