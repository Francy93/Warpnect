package io.warpnect.platform.audio.capture

import io.warpnect.audio.capture.AudioCaptureError

/** Keeps finite helper failure boundaries meaningful to the existing capture controller. */
internal object RootAudioStatusError {
    fun classify(boundary: String, expected: String): AudioCaptureError {
        if (boundary.length > 120) return AudioCaptureError.Unknown
        if (boundary == expected && expected in setOf("PREPARED", "RECORDING", "STOPPED")) {
            return AudioCaptureError.None
        }
        return when (boundary) {
            "AUDIO_API_UNAVAILABLE", "REMOTE_SUBMIX_UNAVAILABLE" -> AudioCaptureError.UnsupportedPlatform
            "AUDIO_FORMAT_MISMATCH" -> AudioCaptureError.UnsupportedFormat
            "AUDIORECORD_CONSTRUCTION_FAILED", "AUDIO_OP_PACKAGE_UNAVAILABLE",
            "AUDIO_FRAMEWORK_APPLICATION_UNAVAILABLE",
            -> AudioCaptureError.AudioRecordCreationFailed
            "AUDIORECORD_UNINITIALIZED" -> AudioCaptureError.AudioRecordUninitialized
            "START_RECORDING_FAILED" -> AudioCaptureError.AudioRecordStartFailed
            "NOT_PREPARED", "AUDIO_NOT_PREPARED", "STOPPED" -> AudioCaptureError.NotPrepared
            "AUDIO_ALREADY_PREPARED" -> AudioCaptureError.AlreadyPrepared
            "FD_HANDOFF_FAILED", "NOTIFY_NOT_PIPE", "ACK_NOT_PIPE" -> AudioCaptureError.NotificationChannelFailed
            "RING_SETUP_FAILED", "FD_SET_INVALID" -> AudioCaptureError.SharedRingCorrupt
            "PRODUCER_STOP_TIMEOUT" -> AudioCaptureError.ThreadStopFailed
            else -> when {
                boundary.startsWith("AUDIO_FORMAT_UNAVAILABLE_") -> AudioCaptureError.UnsupportedFormat
                boundary.startsWith("READ_FAILED_") || boundary.startsWith("pcm_read_invalid_") ->
                    AudioCaptureError.AudioRecordReadFailed
                else -> AudioCaptureError.Unknown
            }
        }
    }
}
