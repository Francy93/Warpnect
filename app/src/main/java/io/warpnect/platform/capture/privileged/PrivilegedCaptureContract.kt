package io.warpnect.platform.capture.privileged

internal object PrivilegedCaptureContract {
    const val SERVICE_VERSION = 1

    const val KEY_ERROR = "error"
    const val KEY_PRIVILEGE_STATE = "privilege_state"
    const val KEY_BACKEND = "backend"
    const val KEY_BACKEND_AVAILABLE = "backend_available"
    const val KEY_SUPPORTS_DYNAMIC_PROJECTION = "supports_dynamic_projection"
    const val KEY_PLATFORM_API_LEVEL = "platform_api_level"
    const val KEY_DISPLAY_IDS = "display_ids"
    const val KEY_DISPLAY_WIDTHS = "display_widths"
    const val KEY_DISPLAY_HEIGHTS = "display_heights"
    const val KEY_DISPLAY_ROTATIONS = "display_rotations"
    const val KEY_DISPLAY_REFRESH_RATES = "display_refresh_rates"

    const val KEY_STATE = "state"
    const val KEY_SOURCE_DISPLAY_ID = "source_display_id"
    const val KEY_SOURCE_WIDTH = "source_width"
    const val KEY_SOURCE_HEIGHT = "source_height"
    const val KEY_SOURCE_ROTATION = "source_rotation"
    const val KEY_TARGET_WIDTH = "target_width"
    const val KEY_TARGET_HEIGHT = "target_height"
    const val KEY_STARTED_AT_MONOTONIC_US = "started_at_monotonic_us"
    const val KEY_RECONFIGURATION_COUNT = "reconfiguration_count"
}
