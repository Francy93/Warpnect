package io.warpnect.platform.audio.capture

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSnapshot
import io.warpnect.platform.audio.capture.privileged.PrivilegedSystemAudioSetup

internal interface PrivilegedAudioCaptureGateway : AutoCloseable {
    fun supportsSharedPcm(api: Int): Boolean = api >= 27

    suspend fun querySystemAudioCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities

    suspend fun prepareSystemAudioCapture(request: AudioCaptureRequest): PrivilegedSystemAudioSetup

    suspend fun startSystemAudioCapture(): AudioCaptureError

    suspend fun stopSystemAudioCapture(): AudioCaptureError

    fun snapshot(): AudioCaptureSnapshot

    override fun close()
}
