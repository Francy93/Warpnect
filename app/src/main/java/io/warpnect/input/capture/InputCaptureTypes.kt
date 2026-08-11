package io.warpnect.input.capture

import android.view.View
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.WarpnectInputEvent

fun interface InputEventSink {
    fun onInputEvent(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult
}

sealed interface InputSinkResult {
    data object Accepted : InputSinkResult

    data class Rejected(
        val reason: String,
    ) : InputSinkResult
}

interface InputCaptureController : AutoCloseable {
    fun queryCapabilities(): InputCaptureCapabilities

    fun prepare(surface: View, config: InputCaptureConfig, sink: InputEventSink): InputCaptureResult

    fun start(): InputCaptureResult

    fun requestPointerCapture(): InputCaptureResult

    fun releasePointerCapture(): InputCaptureResult

    fun stop(): InputCaptureResult

    fun snapshot(): InputCaptureSnapshot

    override fun close()
}

data class InputCaptureConfig(
    val enabledKinds: Set<InputDeviceKind> = setOf(
        InputDeviceKind.Keyboard,
        InputDeviceKind.Touchscreen,
        InputDeviceKind.Mouse,
        InputDeviceKind.Gamepad,
        InputDeviceKind.Stylus,
        InputDeviceKind.Touchpad,
    ),
    val consumeCapturedEvents: Boolean = true,
    val maxTrackedLogicalDevices: Int = 32,
    val captureTouchHistory: Boolean = true,
    val captureGamepadHistory: Boolean = true,
    val enablePointerCapture: Boolean = true,
    val relativePointerNormalizationMode: RelativePointerNormalizationMode =
        RelativePointerNormalizationMode.CaptureSurface,
) {
    fun validate(): InputCaptureError = when {
        enabledKinds.isEmpty() -> InputCaptureError.InvalidConfiguration
        enabledKinds.any { it == InputDeviceKind.Unknown } -> InputCaptureError.InvalidConfiguration
        maxTrackedLogicalDevices !in 1..65_535 -> InputCaptureError.InvalidConfiguration
        else -> InputCaptureError.None
    }
}

enum class RelativePointerNormalizationMode {
    CaptureSurface,
}

enum class InputCaptureState {
    Stopped,
    Preparing,
    Prepared,
    Running,
    Stopping,
    Error,
    Closed,
}

enum class InputCaptureError {
    None,
    InvalidConfiguration,
    InvalidSurface,
    NotPrepared,
    AlreadyRunning,
    Closed,
    DeviceRegistryFull,
    InputDeviceUnavailable,
    UnsupportedKeyboardKey,
    UnsupportedGamepadButton,
    UnsupportedInputSource,
    InvalidPointerId,
    TooManyTouchPointers,
    InvalidSurfaceDimensions,
    InvalidAxisRange,
    InvalidAxisValue,
    PointerCaptureUnsupported,
    PointerCaptureRequestFailed,
    PointerCaptureLost,
    SinkFailure,
    InternalStateError,
}

data class InputCaptureResult(
    val error: InputCaptureError,
    val snapshot: InputCaptureSnapshot,
) {
    val isSuccess: Boolean
        get() = error == InputCaptureError.None
}

data class InputCaptureCapabilities(
    val touchAvailable: Boolean = false,
    val hardwareKeyboardDetected: Boolean = false,
    val mouseDetected: Boolean = false,
    val pointerCaptureSupported: Boolean = false,
    val relativePointerAxesSupported: Boolean = false,
    val gamepadCount: Int = 0,
)

data class InputCaptureSnapshot(
    val state: InputCaptureState = InputCaptureState.Stopped,
    val trackedLogicalDevices: Int = 0,
    val highestAssignedSlot: Int? = null,
    val keyEventsCaptured: Long = 0,
    val touchFramesCaptured: Long = 0,
    val touchHistoricalFramesCaptured: Long = 0,
    val pointerAbsoluteEvents: Long = 0,
    val pointerRelativeEvents: Long = 0,
    val scrollEvents: Long = 0,
    val gamepadStatesCaptured: Long = 0,
    val gamepadHistoricalStatesCaptured: Long = 0,
    val resetEvents: Long = 0,
    val unsupportedKeyEvents: Long = 0,
    val unsupportedGamepadButtons: Long = 0,
    val invalidPointerIds: Long = 0,
    val sinkFailures: Long = 0,
    val deviceRegistryFull: Long = 0,
    val pointerCaptureRequested: Boolean = false,
    val pointerCaptureActive: Boolean = false,
    val lastEventTimeUs: Long? = null,
    val lastCallbackDelayUs: Long? = null,
    val lastError: InputCaptureError = InputCaptureError.None,
)
