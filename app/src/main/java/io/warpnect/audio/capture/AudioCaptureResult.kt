package io.warpnect.audio.capture

data class AudioCaptureResult(
    val error: AudioCaptureError,
    val snapshot: AudioCaptureSnapshot,
) {
    val isSuccess: Boolean
        get() = error == AudioCaptureError.None
}
