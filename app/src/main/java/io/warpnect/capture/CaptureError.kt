package io.warpnect.capture

enum class CaptureError(
    val code: Int,
) {
    None(0),
    ShizukuUnavailable(1),
    ShizukuBinderUnavailable(2),
    ShizukuPermissionRequired(3),
    ShizukuPermissionDenied(4),
    PrivilegedServiceUnavailable(5),
    PrivilegedServiceDied(6),
    UnsupportedAndroidVersion(7),
    CaptureBackendUnavailable(8),
    HiddenApiUnavailable(9),
    CapturePermissionDenied(10),
    SourceDisplayNotFound(11),
    InvalidCaptureDimensions(12),
    InvalidTargetSurface(13),
    AlreadyRunning(14),
    NotRunning(15),
    CaptureCreationFailed(16),
    SurfaceAttachFailed(17),
    ProjectionConfigurationFailed(18),
    DisplayConfigurationFailed(19),
    DisplayRemoved(20),
    BackendInvocationFailed(21),
    BackendReleased(22),
    UnknownFailure(23),
    ;

    companion object {
        fun fromCode(code: Int): CaptureError = entries.firstOrNull { it.code == code } ?: UnknownFailure
    }
}
