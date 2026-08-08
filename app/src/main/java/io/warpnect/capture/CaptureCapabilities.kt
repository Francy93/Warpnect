package io.warpnect.capture

data class CaptureCapabilities(
    val privilegeState: CapturePrivilegeState,
    val backend: CaptureBackend,
    val backendAvailable: Boolean,
    val supportedSourceDisplays: List<CaptureDisplayInfo>,
    val supportsDynamicProjection: Boolean,
    val platformApiLevel: Int,
    val lastError: CaptureError,
)

enum class CapturePrivilegeState {
    Unknown,
    ShizukuUnavailable,
    PermissionRequired,
    PermissionDenied,
    UserServiceUnavailable,
    BackendUnavailable,
    Ready,
}

data class CaptureDisplayInfo(
    val displayId: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val rotation: Int,
    val refreshRate: Float? = null,
    val layerStack: Int = displayId,
)
