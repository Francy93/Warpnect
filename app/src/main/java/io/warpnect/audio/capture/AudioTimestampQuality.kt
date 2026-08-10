package io.warpnect.audio.capture

enum class AudioTimestampQuality {
    AudioRecordTimestamp,
    EstimatedFromReadCompletion,
    Unavailable,
}
