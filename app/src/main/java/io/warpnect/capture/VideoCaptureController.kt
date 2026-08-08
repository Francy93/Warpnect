package io.warpnect.capture

import android.view.Surface

interface VideoCaptureController {
    suspend fun queryCapabilities(): CaptureCapabilities

    suspend fun requestPermission(): CapturePermissionResult

    suspend fun start(request: CaptureRequest, target: Surface): CaptureStartResult

    suspend fun stop(): CaptureStopResult

    fun snapshot(): CaptureSessionSnapshot

    fun close()
}
