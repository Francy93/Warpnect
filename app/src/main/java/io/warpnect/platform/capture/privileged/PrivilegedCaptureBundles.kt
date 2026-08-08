package io.warpnect.platform.capture.privileged

import android.os.Build
import android.os.Bundle
import io.warpnect.capture.CaptureBackend
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureDisplayInfo
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureState

internal fun CaptureCapabilities.toBundle(): Bundle = Bundle().apply {
    putInt(PrivilegedCaptureContract.KEY_ERROR, lastError.code)
    putString(PrivilegedCaptureContract.KEY_PRIVILEGE_STATE, privilegeState.name)
    putString(PrivilegedCaptureContract.KEY_BACKEND, backend.name)
    putBoolean(PrivilegedCaptureContract.KEY_BACKEND_AVAILABLE, backendAvailable)
    putBoolean(PrivilegedCaptureContract.KEY_SUPPORTS_DYNAMIC_PROJECTION, supportsDynamicProjection)
    putInt(PrivilegedCaptureContract.KEY_PLATFORM_API_LEVEL, platformApiLevel)
    putIntArray(
        PrivilegedCaptureContract.KEY_DISPLAY_IDS,
        supportedSourceDisplays.map { it.displayId }.toIntArray(),
    )
    putIntArray(
        PrivilegedCaptureContract.KEY_DISPLAY_WIDTHS,
        supportedSourceDisplays.map { it.logicalWidth }.toIntArray(),
    )
    putIntArray(
        PrivilegedCaptureContract.KEY_DISPLAY_HEIGHTS,
        supportedSourceDisplays.map { it.logicalHeight }.toIntArray(),
    )
    putIntArray(
        PrivilegedCaptureContract.KEY_DISPLAY_ROTATIONS,
        supportedSourceDisplays.map { it.rotation }.toIntArray(),
    )
    putFloatArray(
        PrivilegedCaptureContract.KEY_DISPLAY_REFRESH_RATES,
        supportedSourceDisplays.map { it.refreshRate ?: Float.NaN }.toFloatArray(),
    )
}

internal fun Bundle.toCaptureCapabilities(): CaptureCapabilities {
    val displayIds = getIntArray(PrivilegedCaptureContract.KEY_DISPLAY_IDS) ?: intArrayOf()
    val widths = getIntArray(PrivilegedCaptureContract.KEY_DISPLAY_WIDTHS) ?: intArrayOf()
    val heights = getIntArray(PrivilegedCaptureContract.KEY_DISPLAY_HEIGHTS) ?: intArrayOf()
    val rotations = getIntArray(PrivilegedCaptureContract.KEY_DISPLAY_ROTATIONS) ?: intArrayOf()
    val refreshRates =
        getFloatArray(PrivilegedCaptureContract.KEY_DISPLAY_REFRESH_RATES) ?: floatArrayOf()
    val displayCount = minOf(
        displayIds.size,
        widths.size,
        heights.size,
        rotations.size,
    )

    return CaptureCapabilities(
        privilegeState = enumValueOrDefault(
            getString(PrivilegedCaptureContract.KEY_PRIVILEGE_STATE),
            CapturePrivilegeState.BackendUnavailable,
        ),
        backend = enumValueOrDefault(
            getString(PrivilegedCaptureContract.KEY_BACKEND),
            CaptureBackend.None,
        ),
        backendAvailable = getBoolean(PrivilegedCaptureContract.KEY_BACKEND_AVAILABLE, false),
        supportedSourceDisplays = (0 until displayCount).map { index ->
            CaptureDisplayInfo(
                displayId = displayIds[index],
                logicalWidth = widths[index],
                logicalHeight = heights[index],
                rotation = rotations[index],
                refreshRate = refreshRates.getOrNull(index)?.takeUnless { it.isNaN() },
            )
        },
        supportsDynamicProjection =
        getBoolean(PrivilegedCaptureContract.KEY_SUPPORTS_DYNAMIC_PROJECTION, false),
        platformApiLevel = getInt(PrivilegedCaptureContract.KEY_PLATFORM_API_LEVEL, Build.VERSION.SDK_INT),
        lastError = CaptureError.fromCode(getInt(PrivilegedCaptureContract.KEY_ERROR)),
    )
}

internal fun CaptureSessionSnapshot.toBundle(): Bundle = Bundle().apply {
    putString(PrivilegedCaptureContract.KEY_STATE, state.name)
    putString(PrivilegedCaptureContract.KEY_BACKEND, backend.name)
    putInt(PrivilegedCaptureContract.KEY_ERROR, lastError.code)
    sourceDisplayId?.let { putInt(PrivilegedCaptureContract.KEY_SOURCE_DISPLAY_ID, it) }
    sourceWidth?.let { putInt(PrivilegedCaptureContract.KEY_SOURCE_WIDTH, it) }
    sourceHeight?.let { putInt(PrivilegedCaptureContract.KEY_SOURCE_HEIGHT, it) }
    sourceRotation?.let { putInt(PrivilegedCaptureContract.KEY_SOURCE_ROTATION, it) }
    targetWidth?.let { putInt(PrivilegedCaptureContract.KEY_TARGET_WIDTH, it) }
    targetHeight?.let { putInt(PrivilegedCaptureContract.KEY_TARGET_HEIGHT, it) }
    startedAtMonotonicUs?.let {
        putLong(PrivilegedCaptureContract.KEY_STARTED_AT_MONOTONIC_US, it)
    }
    putInt(PrivilegedCaptureContract.KEY_RECONFIGURATION_COUNT, reconfigurationCount)
}

internal fun Bundle.toCaptureSessionSnapshot(): CaptureSessionSnapshot = CaptureSessionSnapshot(
    state = enumValueOrDefault(
        getString(PrivilegedCaptureContract.KEY_STATE),
        CaptureState.Error,
    ),
    backend = enumValueOrDefault(
        getString(PrivilegedCaptureContract.KEY_BACKEND),
        CaptureBackend.None,
    ),
    sourceDisplayId = optionalInt(PrivilegedCaptureContract.KEY_SOURCE_DISPLAY_ID),
    sourceWidth = optionalInt(PrivilegedCaptureContract.KEY_SOURCE_WIDTH),
    sourceHeight = optionalInt(PrivilegedCaptureContract.KEY_SOURCE_HEIGHT),
    sourceRotation = optionalInt(PrivilegedCaptureContract.KEY_SOURCE_ROTATION),
    targetWidth = optionalInt(PrivilegedCaptureContract.KEY_TARGET_WIDTH),
    targetHeight = optionalInt(PrivilegedCaptureContract.KEY_TARGET_HEIGHT),
    startedAtMonotonicUs = optionalLong(PrivilegedCaptureContract.KEY_STARTED_AT_MONOTONIC_US),
    reconfigurationCount = getInt(PrivilegedCaptureContract.KEY_RECONFIGURATION_COUNT, 0),
    lastError = CaptureError.fromCode(getInt(PrivilegedCaptureContract.KEY_ERROR)),
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T = value?.let {
    runCatching { enumValueOf<T>(it) }.getOrNull()
} ?: default

private fun Bundle.optionalInt(key: String): Int? = if (containsKey(key)) getInt(key) else null

private fun Bundle.optionalLong(key: String): Long? = if (containsKey(key)) getLong(key) else null
