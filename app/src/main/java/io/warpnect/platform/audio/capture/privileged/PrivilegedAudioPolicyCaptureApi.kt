package io.warpnect.platform.audio.capture.privileged

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureFormat
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioTimestampAnchor
import java.nio.ByteBuffer

internal data class PrivilegedSystemAudioPrepareRequest(
    val request: AudioCaptureRequest,
    val format: AudioCaptureFormat,
    val audioRecordBufferSizeBytes: Int,
)

internal data class PrivilegedAudioPolicyPrepareResult(
    val error: AudioCaptureError,
    val actualBufferSizeFrames: Int = 0,
)

/** Cold capability result for the current AudioPolicy-based system-audio implementation. */
internal data class AudioPolicyCapabilityQualification(
    val contextAvailable: Boolean,
    val hiddenApiAvailable: Boolean,
    val routingPermissionGranted: Boolean,
) {
    val error: AudioCaptureError = when {
        !contextAvailable -> AudioCaptureError.PrivilegedServiceUnavailable
        !hiddenApiAvailable -> AudioCaptureError.AudioPolicyUnavailable
        !routingPermissionGranted -> AudioCaptureError.PermissionDenied
        else -> AudioCaptureError.None
    }

    val isAvailable: Boolean get() = error == AudioCaptureError.None
}

internal interface PrivilegedAudioPolicyCaptureApi {
    fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities

    fun prepareSystemAudioCapture(
        prepareRequest: PrivilegedSystemAudioPrepareRequest,
    ): PrivilegedAudioPolicyPrepareResult

    fun startRecording(): AudioCaptureError

    fun read(target: ByteBuffer, sizeBytes: Int): Int

    fun latestTimestampAnchor(): AudioTimestampAnchor?

    fun stopSystemAudioCapture(): AudioCaptureError

    fun close()
}
