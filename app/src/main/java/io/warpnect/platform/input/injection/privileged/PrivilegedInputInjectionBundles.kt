package io.warpnect.platform.input.injection.privileged

import android.os.Bundle
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.input.injection.UhidCapability

private const val KEY_SERVICE_AVAILABLE = "service_available"
private const val KEY_BACKEND = "backend"
private const val KEY_UID = "uid"
private const val KEY_UID_KIND = "uid_kind"
private const val KEY_API_RESOLVED = "api_resolved"
private const val KEY_ASYNC = "async"
private const val KEY_WAIT = "wait"
private const val KEY_TARGET_UID = "target_uid_supported"
private const val KEY_DISPLAY = "display_supported"
private const val KEY_KEY = "key"
private const val KEY_TOUCH = "touch"
private const val KEY_POINTER = "pointer"
private const val KEY_JOYSTICK = "joystick"
private const val KEY_UHID_CAPABILITY = "uhid_capability"
private const val KEY_UHID_ERRNO = "uhid_errno"
private const val KEY_MAX_POINTERS = "max_pointers"
private const val KEY_MAX_SLOTS = "max_slots"
private const val KEY_ERROR = "error"

internal fun InputInjectionCapabilities.toBundle(): Bundle = Bundle().apply {
    putBoolean(KEY_SERVICE_AVAILABLE, serviceAvailable)
    putInt(KEY_BACKEND, backend.ordinal)
    privilegedUid?.let { putInt(KEY_UID, it) }
    putInt(KEY_UID_KIND, privilegedUidKind.ordinal)
    putBoolean(KEY_API_RESOLVED, inputManagerApiResolved)
    putBoolean(KEY_ASYNC, asyncInjectionSupported)
    putBoolean(KEY_WAIT, waitForResultSupported)
    putBoolean(KEY_TARGET_UID, targetUidInjectionSupported)
    putBoolean(KEY_DISPLAY, displayTargetingSupported)
    putBoolean(KEY_KEY, keyInjectionSupported)
    putBoolean(KEY_TOUCH, touchInjectionSupported)
    putBoolean(KEY_POINTER, pointerInjectionSupported)
    putBoolean(KEY_JOYSTICK, joystickInjectionSupported)
    putInt(KEY_UHID_CAPABILITY, uhidCapability.ordinal)
    uhidErrno?.let { putInt(KEY_UHID_ERRNO, it) }
    putInt(KEY_MAX_POINTERS, maxPointers)
    putInt(KEY_MAX_SLOTS, maxTrackedStateSlots)
    putInt(KEY_ERROR, lastError.code)
}

internal fun Bundle.toInputInjectionCapabilities(): InputInjectionCapabilities = InputInjectionCapabilities(
    serviceAvailable = getBoolean(KEY_SERVICE_AVAILABLE),
    backend = InputInjectionBackend.entries.getOrElse(getInt(KEY_BACKEND)) { InputInjectionBackend.None },
    privilegedUid = if (containsKey(KEY_UID)) getInt(KEY_UID) else null,
    privilegedUidKind = PrivilegedUidKind.entries.getOrElse(getInt(KEY_UID_KIND)) { PrivilegedUidKind.Unknown },
    inputManagerApiResolved = getBoolean(KEY_API_RESOLVED),
    asyncInjectionSupported = getBoolean(KEY_ASYNC),
    waitForResultSupported = getBoolean(KEY_WAIT),
    targetUidInjectionSupported = getBoolean(KEY_TARGET_UID),
    displayTargetingSupported = getBoolean(KEY_DISPLAY),
    keyInjectionSupported = getBoolean(KEY_KEY),
    touchInjectionSupported = getBoolean(KEY_TOUCH),
    pointerInjectionSupported = getBoolean(KEY_POINTER),
    joystickInjectionSupported = getBoolean(KEY_JOYSTICK),
    uhidCapability = UhidCapability.entries.getOrElse(getInt(KEY_UHID_CAPABILITY)) {
        UhidCapability.Unavailable
    },
    uhidErrno = if (containsKey(KEY_UHID_ERRNO)) getInt(KEY_UHID_ERRNO) else null,
    maxPointers = getInt(KEY_MAX_POINTERS),
    maxTrackedStateSlots = getInt(KEY_MAX_SLOTS),
    lastError = InputInjectionError.fromCode(getInt(KEY_ERROR)),
)

private const val KEY_SNAPSHOT_PREFIX = "s_"

internal fun InputInjectionSnapshot.toBundle(): Bundle = Bundle().apply {
    putInt(KEY_SNAPSHOT_PREFIX + "state", PrivilegedInputInjectionContract.stateCode(state))
    putInt(KEY_SNAPSHOT_PREFIX + "backend", backend.ordinal)
    privilegedUid?.let { putInt(KEY_SNAPSHOT_PREFIX + "uid", it) }
    putInt(KEY_SNAPSHOT_PREFIX + "uid_kind", privilegedUidKind.ordinal)
    putInt(KEY_SNAPSHOT_PREFIX + "mode", injectionMode.ordinal)
    putInt(KEY_SNAPSHOT_PREFIX + "target_uid", targetUid)
    putBoolean(KEY_SNAPSHOT_PREFIX + "api", apiResolved)
    putBoolean(KEY_SNAPSHOT_PREFIX + "target", targetUidSupported)
    putBoolean(KEY_SNAPSHOT_PREFIX + "display", displayTargetingSupported)
    SNAPSHOT_LONG_FIELDS.forEach { (key, value) -> putLong(KEY_SNAPSHOT_PREFIX + key, value(this@toBundle)) }
    putInt(KEY_SNAPSHOT_PREFIX + "slots", trackedStateSlots)
    putInt(KEY_SNAPSHOT_PREFIX + "keys", activePressedKeys)
    putInt(KEY_SNAPSHOT_PREFIX + "touch", activeTouchStreams)
    putInt(KEY_SNAPSHOT_PREFIX + "pointer", activePointerButtonStates)
    putInt(KEY_SNAPSHOT_PREFIX + "joystick", activeJoystickStates)
    lastSourceEventTimeUs?.let { putLong(KEY_SNAPSHOT_PREFIX + "source_time", it) }
    lastLocalInjectionTimeMs?.let { putLong(KEY_SNAPSHOT_PREFIX + "local_time", it) }
    putBoolean(KEY_SNAPSHOT_PREFIX + "may_remain", stateMayRemainInjected)
    putInt(KEY_SNAPSHOT_PREFIX + "error", lastError.code)
}

internal fun Bundle.toInputInjectionSnapshot(): InputInjectionSnapshot {
    fun count(key: String): Long = getLong(KEY_SNAPSHOT_PREFIX + key)
    return InputInjectionSnapshot(
        state = PrivilegedInputInjectionContract.stateFromCode(getInt(KEY_SNAPSHOT_PREFIX + "state")),
        backend = InputInjectionBackend.entries.getOrElse(getInt(KEY_SNAPSHOT_PREFIX + "backend")) {
            InputInjectionBackend.None
        },
        privilegedUid = if (containsKey(KEY_SNAPSHOT_PREFIX + "uid")) getInt(KEY_SNAPSHOT_PREFIX + "uid") else null,
        privilegedUidKind = PrivilegedUidKind.entries.getOrElse(getInt(KEY_SNAPSHOT_PREFIX + "uid_kind")) {
            PrivilegedUidKind.Unknown
        },
        injectionMode = InputInjectionMode.entries.getOrElse(getInt(KEY_SNAPSHOT_PREFIX + "mode")) {
            InputInjectionMode.AsyncLowLatency
        },
        targetUid = getInt(KEY_SNAPSHOT_PREFIX + "target_uid"),
        apiResolved = getBoolean(KEY_SNAPSHOT_PREFIX + "api"),
        targetUidSupported = getBoolean(KEY_SNAPSHOT_PREFIX + "target"),
        displayTargetingSupported = getBoolean(KEY_SNAPSHOT_PREFIX + "display"),
        keyAttempts = count("key_attempts"), keySubmitted = count("key_submitted"), keyFailures = count("key_failures"),
        touchAttempts = count(
            "touch_attempts",
        ),
        touchSubmitted = count("touch_submitted"), touchFailures = count("touch_failures"),
        pointerAttempts = count(
            "pointer_attempts",
        ),
        pointerSubmitted = count("pointer_submitted"), pointerFailures = count("pointer_failures"),
        joystickAttempts = count(
            "joystick_attempts",
        ),
        joystickSubmitted = count("joystick_submitted"), joystickFailures = count("joystick_failures"),
        resetRequests = count(
            "reset_requests",
        ),
        resetComplete = count("reset_complete"), resetPartial = count("reset_partial"),
        syntheticKeyUps = count("synthetic_key_ups"), syntheticTouchCancels = count("synthetic_touch_cancels"),
        syntheticPointerReleases = count(
            "synthetic_pointer_releases",
        ),
        syntheticJoystickNeutralEvents = count("synthetic_joystick_neutral"),
        orphanKeyUps = count("orphan_key_ups"), invalidTouchSequences = count("invalid_touch_sequences"),
        trackedStateSlots = getInt(
            KEY_SNAPSHOT_PREFIX + "slots",
        ),
        activePressedKeys = getInt(KEY_SNAPSHOT_PREFIX + "keys"),
        activeTouchStreams = getInt(
            KEY_SNAPSHOT_PREFIX + "touch",
        ),
        activePointerButtonStates = getInt(KEY_SNAPSHOT_PREFIX + "pointer"),
        activeJoystickStates = getInt(KEY_SNAPSHOT_PREFIX + "joystick"),
        lastSourceEventTimeUs = if (containsKey(
                KEY_SNAPSHOT_PREFIX + "source_time",
            )
        ) {
            getLong(KEY_SNAPSHOT_PREFIX + "source_time")
        } else {
            null
        },
        lastLocalInjectionTimeMs = if (containsKey(
                KEY_SNAPSHOT_PREFIX + "local_time",
            )
        ) {
            getLong(KEY_SNAPSHOT_PREFIX + "local_time")
        } else {
            null
        },
        permissionFailures = count("permission_failures"), serviceFailures = count("service_failures"),
        stateMayRemainInjected = getBoolean(KEY_SNAPSHOT_PREFIX + "may_remain"),
        lastError = InputInjectionError.fromCode(getInt(KEY_SNAPSHOT_PREFIX + "error")),
    )
}

private val SNAPSHOT_LONG_FIELDS: List<Pair<String, (InputInjectionSnapshot) -> Long>> = listOf(
    "key_attempts" to { it.keyAttempts }, "key_submitted" to { it.keySubmitted }, "key_failures" to { it.keyFailures },
    "touch_attempts" to {
        it.touchAttempts
    },
    "touch_submitted" to { it.touchSubmitted }, "touch_failures" to { it.touchFailures },
    "pointer_attempts" to {
        it.pointerAttempts
    },
    "pointer_submitted" to { it.pointerSubmitted }, "pointer_failures" to { it.pointerFailures },
    "joystick_attempts" to {
        it.joystickAttempts
    },
    "joystick_submitted" to { it.joystickSubmitted }, "joystick_failures" to { it.joystickFailures },
    "reset_requests" to {
        it.resetRequests
    },
    "reset_complete" to { it.resetComplete }, "reset_partial" to { it.resetPartial },
    "synthetic_key_ups" to { it.syntheticKeyUps }, "synthetic_touch_cancels" to { it.syntheticTouchCancels },
    "synthetic_pointer_releases" to {
        it.syntheticPointerReleases
    },
    "synthetic_joystick_neutral" to { it.syntheticJoystickNeutralEvents },
    "orphan_key_ups" to { it.orphanKeyUps }, "invalid_touch_sequences" to { it.invalidTouchSequences },
    "permission_failures" to { it.permissionFailures }, "service_failures" to { it.serviceFailures },
)
