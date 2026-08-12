package io.warpnect.input.injection

/** Android-ready input injection contracts. RFC-004E owns portable-to-Android mapping. */
enum class InputInjectionMode {
    AsyncLowLatency,
    WaitForResultDiagnostics,
}

enum class InputInjectionState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}

enum class InputInjectionBackend {
    None,
    ShizukuUserService,
}

enum class PrivilegedUidKind {
    Unknown,
    Root,
    Shell,
    Other,
}

enum class InputResetScope {
    ThisSlot,
    AllSlots,
}

enum class InputResetReason {
    SessionStop,
    DeviceDisconnected,
    FocusLost,
    ErrorRecovery,
    UserRequest,
    TransportRecovery,
}

enum class InputInjectionError(
    val code: Int,
) {
    None(0),
    InvalidConfiguration(10),
    InvalidEvent(11),
    InvalidStateSlot(12),
    StateSlotCapacityReached(13),
    PressedKeyCapacityReached(14),
    InvalidTouchSequence(15),
    TargetUidUnsupported(16),
    DisplayTargetingUnsupported(17),
    InputApiUnavailable(18),
    InjectEventsPermissionDenied(19),
    InjectionRejected(20),
    ServiceUnavailable(21),
    ServiceDied(22),
    RemoteFailure(23),
    NotPrepared(24),
    NotRunning(25),
    AlreadyRunning(26),
    Closed(27),
    UnknownFailure(28),
    ShizukuUnavailable(29),
    ShizukuPermissionRequired(30),
    ShizukuPermissionDenied(31),
    UserServiceBindFailed(32),
    ;

    companion object {
        fun fromCode(code: Int): InputInjectionError = entries.firstOrNull { it.code == code } ?: UnknownFailure
    }
}

/** Internal synchronous AIDL result values for Privileged Input Injection Service Version 1. */
enum class InputInjectionServiceResult(
    val code: Int,
    val error: InputInjectionError,
) {
    SubmittedAsync(0, InputInjectionError.None),
    AcceptedWaitForResult(1, InputInjectionError.None),
    ResetComplete(2, InputInjectionError.None),
    ResetPartial(3, InputInjectionError.None),
    Prepared(4, InputInjectionError.None),
    InvalidConfiguration(10, InputInjectionError.InvalidConfiguration),
    InvalidEvent(11, InputInjectionError.InvalidEvent),
    InvalidStateSlot(12, InputInjectionError.InvalidStateSlot),
    StateSlotCapacityReached(13, InputInjectionError.StateSlotCapacityReached),
    PressedKeyCapacityReached(14, InputInjectionError.PressedKeyCapacityReached),
    InvalidTouchSequence(15, InputInjectionError.InvalidTouchSequence),
    TargetUidUnsupported(16, InputInjectionError.TargetUidUnsupported),
    DisplayTargetingUnsupported(17, InputInjectionError.DisplayTargetingUnsupported),
    InputApiUnavailable(18, InputInjectionError.InputApiUnavailable),
    InjectEventsPermissionDenied(19, InputInjectionError.InjectEventsPermissionDenied),
    InjectionRejected(20, InputInjectionError.InjectionRejected),
    ServiceUnavailable(21, InputInjectionError.ServiceUnavailable),
    ServiceDied(22, InputInjectionError.ServiceDied),
    RemoteFailure(23, InputInjectionError.RemoteFailure),
    NotPrepared(24, InputInjectionError.NotPrepared),
    NotRunning(25, InputInjectionError.NotRunning),
    AlreadyRunning(26, InputInjectionError.AlreadyRunning),
    Closed(27, InputInjectionError.Closed),
    UnknownFailure(28, InputInjectionError.UnknownFailure),
    ShizukuUnavailable(29, InputInjectionError.ShizukuUnavailable),
    ShizukuPermissionRequired(30, InputInjectionError.ShizukuPermissionRequired),
    ShizukuPermissionDenied(31, InputInjectionError.ShizukuPermissionDenied),
    UserServiceBindFailed(32, InputInjectionError.UserServiceBindFailed),
    ;

    val isAccepted: Boolean
        get() = this == SubmittedAsync || this == AcceptedWaitForResult

    companion object {
        fun fromCode(code: Int): InputInjectionServiceResult = entries.firstOrNull { it.code == code }
            ?: UnknownFailure
    }
}

data class InputInjectionConfig(
    val targetUid: Int = ANDROID_INVALID_UID,
    val injectionMode: InputInjectionMode = InputInjectionMode.AsyncLowLatency,
    val maxTrackedInjectionSlots: Int = 32,
    val maxPressedKeysPerSlot: Int = 64,
    val resetAllOnStop: Boolean = true,
) {
    fun validate(): InputInjectionError = when {
        targetUid < ANDROID_INVALID_UID -> InputInjectionError.InvalidConfiguration
        maxTrackedInjectionSlots !in 1..MAX_TRACKED_INJECTION_SLOTS -> InputInjectionError.InvalidConfiguration
        maxPressedKeysPerSlot !in 1..MAX_PRESSED_KEYS_PER_SLOT -> InputInjectionError.InvalidConfiguration
        else -> InputInjectionError.None
    }

    companion object {
        const val MAX_TRACKED_INJECTION_SLOTS = 32
        const val MAX_PRESSED_KEYS_PER_SLOT = 64
    }
}

data class InputInjectionCapabilities(
    val serviceAvailable: Boolean = false,
    val backend: InputInjectionBackend = InputInjectionBackend.None,
    val privilegedUid: Int? = null,
    val privilegedUidKind: PrivilegedUidKind = PrivilegedUidKind.Unknown,
    val inputManagerApiResolved: Boolean = false,
    val asyncInjectionSupported: Boolean = false,
    val waitForResultSupported: Boolean = false,
    val targetUidInjectionSupported: Boolean = false,
    val displayTargetingSupported: Boolean = false,
    val keyInjectionSupported: Boolean = false,
    val touchInjectionSupported: Boolean = false,
    val pointerInjectionSupported: Boolean = false,
    val joystickInjectionSupported: Boolean = false,
    val maxPointers: Int = MAX_TOUCH_POINTERS,
    val maxTrackedStateSlots: Int = InputInjectionConfig.MAX_TRACKED_INJECTION_SLOTS,
    val lastError: InputInjectionError = InputInjectionError.None,
)

data class InputInjectionSnapshot(
    val state: InputInjectionState = InputInjectionState.Stopped,
    val backend: InputInjectionBackend = InputInjectionBackend.None,
    val privilegedUid: Int? = null,
    val privilegedUidKind: PrivilegedUidKind = PrivilegedUidKind.Unknown,
    val injectionMode: InputInjectionMode = InputInjectionMode.AsyncLowLatency,
    val targetUid: Int = ANDROID_INVALID_UID,
    val apiResolved: Boolean = false,
    val targetUidSupported: Boolean = false,
    val displayTargetingSupported: Boolean = false,
    val keyAttempts: Long = 0L,
    val keySubmitted: Long = 0L,
    val keyFailures: Long = 0L,
    val touchAttempts: Long = 0L,
    val touchSubmitted: Long = 0L,
    val touchFailures: Long = 0L,
    val pointerAttempts: Long = 0L,
    val pointerSubmitted: Long = 0L,
    val pointerFailures: Long = 0L,
    val joystickAttempts: Long = 0L,
    val joystickSubmitted: Long = 0L,
    val joystickFailures: Long = 0L,
    val resetRequests: Long = 0L,
    val resetComplete: Long = 0L,
    val resetPartial: Long = 0L,
    val syntheticKeyUps: Long = 0L,
    val syntheticTouchCancels: Long = 0L,
    val syntheticPointerReleases: Long = 0L,
    val syntheticJoystickNeutralEvents: Long = 0L,
    val orphanKeyUps: Long = 0L,
    val invalidTouchSequences: Long = 0L,
    val trackedStateSlots: Int = 0,
    val activePressedKeys: Int = 0,
    val activeTouchStreams: Int = 0,
    val activePointerButtonStates: Int = 0,
    val activeJoystickStates: Int = 0,
    val lastSourceEventTimeUs: Long? = null,
    val lastLocalInjectionTimeMs: Long? = null,
    val permissionFailures: Long = 0L,
    val serviceFailures: Long = 0L,
    val stateMayRemainInjected: Boolean = false,
    val lastError: InputInjectionError = InputInjectionError.None,
)

data class InputInjectionResult(
    val serviceResult: InputInjectionServiceResult,
    val snapshot: InputInjectionSnapshot,
) {
    val error: InputInjectionError
        get() = serviceResult.error

    val isSuccess: Boolean
        get() = error == InputInjectionError.None
}

data class InputInjectionPermissionResult(
    val error: InputInjectionError,
    val requestIssued: Boolean = false,
)

data class AndroidKeyInjectionEvent(
    val stateSlot: Int,
    val sourceEventTimeUs: Long,
    val action: Int,
    val keyCode: Int,
    val repeatCount: Int = 0,
    val metaState: Int = 0,
    val scanCode: Int = 0,
    val flags: Int = 0,
    val source: Int,
    val androidDeviceId: Int = 0,
    val displayId: Int,
)

data class AndroidTouchPointer(
    val pointerId: Int,
    val toolType: Int,
    val xPx: Float,
    val yPx: Float,
    val pressure: Float = 1f,
    val size: Float = 0f,
)

data class AndroidTouchInjectionEvent(
    val stateSlot: Int,
    val sourceEventTimeUs: Long,
    val actionMasked: Int,
    val actionIndex: Int,
    val pointers: Array<AndroidTouchPointer>,
    val metaState: Int = 0,
    val buttonState: Int = 0,
    val source: Int,
    val androidDeviceId: Int = 0,
    val displayId: Int,
)

data class AndroidPointerInjectionEvent(
    val stateSlot: Int,
    val sourceEventTimeUs: Long,
    val action: Int,
    val actionButton: Int = 0,
    val xPx: Float,
    val yPx: Float,
    val relativeXPx: Float = 0f,
    val relativeYPx: Float = 0f,
    val horizontalScroll: Float = 0f,
    val verticalScroll: Float = 0f,
    val pressure: Float = 0f,
    val size: Float = 0f,
    val metaState: Int = 0,
    val buttonState: Int = 0,
    val source: Int,
    val androidDeviceId: Int = 0,
    val displayId: Int,
)

data class AndroidJoystickInjectionEvent(
    val stateSlot: Int,
    val sourceEventTimeUs: Long,
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
    val leftTrigger: Float,
    val rightTrigger: Float,
    val hatX: Float,
    val hatY: Float,
    val metaState: Int = 0,
    val source: Int,
    val androidDeviceId: Int = 0,
    val displayId: Int,
)

interface InputInjectionController : AutoCloseable {
    suspend fun queryCapabilities(): InputInjectionCapabilities

    suspend fun requestPermission(): InputInjectionPermissionResult

    suspend fun prepare(config: InputInjectionConfig): InputInjectionResult

    fun start(): InputInjectionResult

    fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionResult

    fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionResult

    fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionResult

    fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionResult

    fun resetState(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): InputInjectionResult

    fun stop(): InputInjectionResult

    fun snapshot(): InputInjectionSnapshot

    override fun close()
}

const val ANDROID_INVALID_UID = -1
const val MAX_TOUCH_POINTERS = 32

/** Values mirror Android InputEvent constants while keeping contract validation unit-testable. */
internal object AndroidInjectionConstants {
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    const val MOTION_ACTION_DOWN = 0
    const val MOTION_ACTION_UP = 1
    const val MOTION_ACTION_MOVE = 2
    const val MOTION_ACTION_CANCEL = 3
    const val MOTION_ACTION_POINTER_DOWN = 5
    const val MOTION_ACTION_POINTER_UP = 6
    const val MOTION_ACTION_HOVER_MOVE = 7
    const val MOTION_ACTION_SCROLL = 8
    const val MOTION_ACTION_BUTTON_PRESS = 11
    const val MOTION_ACTION_BUTTON_RELEASE = 12

    const val SOURCE_CLASS_MASK = 0x000000ff
    const val SOURCE_CLASS_BUTTON = 0x00000001
    const val SOURCE_CLASS_POINTER = 0x00000002
    const val SOURCE_CLASS_JOYSTICK = 0x00000010
    const val SOURCE_KEYBOARD = 0x00000101
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_TOUCHSCREEN = 0x00001002
    const val SOURCE_MOUSE = 0x00002002
    const val SOURCE_STYLUS = 0x00004002
    const val SOURCE_TOUCHPAD = 0x00100008
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_MOUSE_RELATIVE = 0x00020004
    const val KEY_FLAG_LONG_PRESS = 0x00000080
}
