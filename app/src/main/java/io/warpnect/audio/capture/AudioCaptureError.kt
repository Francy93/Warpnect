package io.warpnect.audio.capture

enum class AudioCaptureError(
    val code: Int,
) {
    None(0),
    InvalidRequest(1),
    UnsupportedFormat(2),
    InvalidChunkDuration(3),
    PermissionDenied(4),
    ShizukuUnavailable(5),
    ShizukuPermissionDenied(6),
    ShizukuPermissionRequired(7),
    ShizukuBinderUnavailable(8),
    PrivilegedServiceUnavailable(9),
    PrivilegedServiceDied(10),
    AudioPolicyUnavailable(11),
    AudioPolicyRegistrationFailed(12),
    AudioMixCreationFailed(13),
    AudioRecordCreationFailed(14),
    AudioRecordUninitialized(15),
    AudioRecordStartFailed(16),
    AudioRecordReadFailed(17),
    AudioRecordDead(18),
    SharedMemoryCreationFailed(19),
    SharedMemoryMappingFailed(20),
    NotificationChannelFailed(21),
    SharedRingCorrupt(22),
    SharedRingOverrun(23),
    TimestampUnavailable(24),
    TimestampInvalid(25),
    SinkFailure(26),
    AlreadyPrepared(27),
    AlreadyRunning(28),
    NotPrepared(29),
    NotRunning(30),
    Closed(31),
    UnsupportedPlatform(32),
    ThreadStartFailed(33),
    ThreadStopFailed(34),
    Unknown(255),
    ;

    companion object {
        fun fromCode(code: Int): AudioCaptureError = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
