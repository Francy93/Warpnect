package io.warpnect.platform.input.mapping

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputResetReason as InjectionResetReason
import io.warpnect.input.injection.InputResetScope as InjectionResetScope
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_A
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_B
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_DOWN
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_LEFT
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_RIGHT
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_UP
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_GUIDE_MODE
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_STICK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_LEFT_TRIGGER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_SHOULDER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_STICK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_RIGHT_TRIGGER
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_SELECT_BACK
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_START
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_X
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_Y
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_ALT
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_CONTROL
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_GUI
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_SHIFT
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_ALT
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_CONTROL
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_GUI
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_SHIFT
import io.warpnect.input.model.INPUT_POINTER_ABSOLUTE_PRESSURE_VALID
import io.warpnect.input.model.INPUT_POINTER_BUTTON_BACK
import io.warpnect.input.model.INPUT_POINTER_BUTTON_FORWARD
import io.warpnect.input.model.INPUT_POINTER_BUTTON_PRIMARY
import io.warpnect.input.model.INPUT_POINTER_BUTTON_SECONDARY
import io.warpnect.input.model.INPUT_POINTER_BUTTON_TERTIARY
import io.warpnect.input.model.INPUT_TOUCH_PRESSURE_VALID
import io.warpnect.input.model.INPUT_TOUCH_SIZE_VALID
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputModelError
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputScroll
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.input.model.WarpnectInputEvent

enum class DpadInjectionMode {
    HatAxes,
    KeyEvents,
}

data class AndroidTargetInputMappingConfig(
    val targetDisplayId: Int = 0,
    val keyboardDeviceResolutionPolicy: AndroidTargetDeviceResolutionPolicy =
        AndroidTargetDeviceResolutionPolicy.SyntheticDefault,
    val touchDeviceResolutionPolicy: AndroidTargetDeviceResolutionPolicy =
        AndroidTargetDeviceResolutionPolicy.SyntheticDefault,
    val mouseDeviceResolutionPolicy: AndroidTargetDeviceResolutionPolicy =
        AndroidTargetDeviceResolutionPolicy.SyntheticDefault,
    val gamepadDeviceResolutionPolicy: AndroidTargetDeviceResolutionPolicy =
        AndroidTargetDeviceResolutionPolicy.SyntheticDefault,
    val dpadInjectionMode: DpadInjectionMode = DpadInjectionMode.HatAxes,
    val maxTrackedSlots: Int = DEFAULT_MAX_TRACKED_SLOTS,
) {
    fun isValid(): Boolean = targetDisplayId >= 0 && maxTrackedSlots in 1..MAX_TRACKED_SLOTS

    fun deviceResolutionPolicyFor(kind: InputDeviceKind): AndroidTargetDeviceResolutionPolicy = when (kind) {
        InputDeviceKind.Keyboard -> keyboardDeviceResolutionPolicy
        InputDeviceKind.Touchscreen,
        InputDeviceKind.Stylus,
        -> touchDeviceResolutionPolicy
        InputDeviceKind.Mouse,
        InputDeviceKind.Touchpad,
        -> mouseDeviceResolutionPolicy
        InputDeviceKind.Gamepad -> gamepadDeviceResolutionPolicy
        InputDeviceKind.Unknown -> AndroidTargetDeviceResolutionPolicy.RequireSourceCompatible
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_SLOTS = 32
        const val MAX_TRACKED_SLOTS = 32
    }
}

enum class AndroidTargetInputMappingState {
    Ready,
    Degraded,
    Closed,
}

enum class AndroidTargetInputMappingOutcome {
    Mapped,
    MappedMultiple,
    NoOp,
    Suppressed,
    InvalidInput,
    TargetDisplayUnavailable,
    TargetDeviceUnavailable,
    UnsupportedHidUsage,
    UnsupportedDeviceKind,
    InvalidTouchAction,
    InvalidActionPointer,
    InvalidCoordinate,
    CapacityExceeded,
    InjectionFailure,
    ResetInjectionFailure,
    Closed,
}

data class AndroidTargetInputMappingResult(
    val outcome: AndroidTargetInputMappingOutcome,
    val androidEventsProduced: Int = 0,
    val injectionError: InputInjectionError = InputInjectionError.None,
) {
    val isSuccess: Boolean
        get() = outcome == AndroidTargetInputMappingOutcome.Mapped ||
            outcome == AndroidTargetInputMappingOutcome.MappedMultiple ||
            outcome == AndroidTargetInputMappingOutcome.NoOp ||
            outcome == AndroidTargetInputMappingOutcome.Suppressed
}

data class AndroidTargetInputMappingSnapshot(
    val state: AndroidTargetInputMappingState = AndroidTargetInputMappingState.Ready,
    val targetDisplayId: Int = 0,
    val logicalWidthPx: Int = 0,
    val logicalHeightPx: Int = 0,
    val rotation: Int = 0,
    val geometryGeneration: Long = 0L,
    val eventsReceived: Long = 0L,
    val eventsSuppressed: Long = 0L,
    val androidEventsProduced: Long = 0L,
    val keysMapped: Long = 0L,
    val touchFramesMapped: Long = 0L,
    val pointerSnapshotsMapped: Long = 0L,
    val pointerAndroidEventsProduced: Long = 0L,
    val scrollMapped: Long = 0L,
    val gamepadSnapshotsMapped: Long = 0L,
    val gamepadKeyTransitions: Long = 0L,
    val gamepadMotionEvents: Long = 0L,
    val identicalGamepadNoOps: Long = 0L,
    val unsupportedHid: Long = 0L,
    val opposingDpadStates: Long = 0L,
    val mappingResets: Long = 0L,
    val geometryResets: Long = 0L,
    val trackedSlots: Int = 0,
    val lastError: AndroidTargetInputMappingOutcome = AndroidTargetInputMappingOutcome.Mapped,
)

interface TargetInputMapper : AutoCloseable {
    fun mapAndInject(sourceEventTimeUs: Long, event: WarpnectInputEvent): AndroidTargetInputMappingResult

    fun reset(): AndroidTargetInputMappingResult

    fun snapshot(): AndroidTargetInputMappingSnapshot
}

/**
 * Maps portable Input Payload V1 semantics to RFC-004D Android-ready injection events.
 * It is synchronous, queue-free, and bounded by [AndroidTargetInputMappingConfig.maxTrackedSlots].
 */
class AndroidTargetInputMapper(
    private val injectionController: InputInjectionController,
    private val displayGeometryProvider: TargetDisplayGeometryProvider,
    private val deviceResolver: TargetInputDeviceResolver,
    private val config: AndroidTargetInputMappingConfig = AndroidTargetInputMappingConfig(),
) : TargetInputMapper {
    private val slots: Array<TargetSlot?>
    private var lastGeometry: TargetDisplayGeometry? = null
    private var closed = false
    private var snapshot = AndroidTargetInputMappingSnapshot(targetDisplayId = config.targetDisplayId)

    init {
        require(config.isValid()) { "Android target input mapping configuration is invalid" }
        slots = arrayOfNulls(config.maxTrackedSlots)
    }

    override fun mapAndInject(sourceEventTimeUs: Long, event: WarpnectInputEvent): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(eventsReceived = snapshot.eventsReceived + 1L)
        if (closed) return fail(AndroidTargetInputMappingOutcome.Closed)
        if (sourceEventTimeUs < 0L || event.validationError() != InputModelError.None) {
            return fail(AndroidTargetInputMappingOutcome.InvalidInput)
        }
        return when (event) {
            is InputKeyEvent -> mapKey(sourceEventTimeUs, event)
            is InputTouchFrame -> mapTouch(sourceEventTimeUs, event)
            is InputPointerAbsolute -> mapPointerAbsolute(sourceEventTimeUs, event)
            is InputPointerRelative -> mapPointerRelative(sourceEventTimeUs, event)
            is InputScroll -> mapScroll(sourceEventTimeUs, event)
            is InputGamepadState -> mapGamepad(sourceEventTimeUs, event)
            is InputResetState -> mapReset(event)
        }
    }

    override fun reset(): AndroidTargetInputMappingResult {
        if (closed) return fail(AndroidTargetInputMappingOutcome.Closed)
        val result = injectionController.resetState(
            InjectionResetScope.AllSlots,
            stateSlot = 0,
            reason = InjectionResetReason.SessionStop,
        )
        if (!result.isSuccess) return resetFailure(result.error)
        clearAllSlots()
        snapshot = snapshot.copy(mappingResets = snapshot.mappingResets + 1L)
        return mapped(1)
    }

    override fun snapshot(): AndroidTargetInputMappingSnapshot = snapshot.copy(
        trackedSlots = slots.count { it != null },
    )

    override fun close() {
        if (closed) return
        closed = true
        clearAllSlots()
        deviceResolver.close()
        if (displayGeometryProvider is AutoCloseable) {
            displayGeometryProvider.close()
        }
        snapshot = snapshot.copy(
            state = AndroidTargetInputMappingState.Closed,
            lastError = AndroidTargetInputMappingOutcome.Closed,
        )
    }

    private fun mapKey(sourceEventTimeUs: Long, event: InputKeyEvent): AndroidTargetInputMappingResult {
        val geometry = acquireGeometry()?.geometry
            ?: return fail(snapshot.lastError)
        val keyCode = AndroidHidKeyboardMappingTable.hidToAndroid(event.usagePage, event.usageId)
            ?: run {
                snapshot = snapshot.copy(unsupportedHid = snapshot.unsupportedHid + 1L)
                return fail(
                    AndroidTargetInputMappingOutcome.UnsupportedHidUsage,
                )
            }
        val deviceId = resolveDevice(InputDeviceKind.Keyboard)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val result = injectionController.injectKey(
            AndroidKeyInjectionEvent(
                stateSlot = event.deviceSlot,
                sourceEventTimeUs = sourceEventTimeUs,
                action = when (event.action) {
                    InputKeyAction.Down -> AndroidInjectionConstants.KEY_ACTION_DOWN
                    InputKeyAction.Up -> AndroidInjectionConstants.KEY_ACTION_UP
                    InputKeyAction.Unknown -> return fail(AndroidTargetInputMappingOutcome.InvalidInput)
                },
                keyCode = keyCode,
                repeatCount = event.repeatCount,
                metaState = androidMetaState(event.modifierMask),
                source = InputDevice.SOURCE_KEYBOARD,
                androidDeviceId = deviceId,
                displayId = geometry.displayId,
            ),
        )
        if (!recordInjection(result)) return injectionFailure(result.error, 0)
        snapshot = snapshot.copy(keysMapped = snapshot.keysMapped + 1L)
        return mapped(1)
    }

    private fun mapTouch(sourceEventTimeUs: Long, event: InputTouchFrame): AndroidTargetInputMappingResult {
        if (event.action == InputTouchAction.Cancel && event.contacts.isEmpty()) {
            val reset = injectionController.resetState(
                InjectionResetScope.ThisSlot,
                event.deviceSlot,
                InjectionResetReason.ErrorRecovery,
            )
            if (!reset.isSuccess) return resetFailure(reset.error)
            clearSlot(event.deviceSlot)
            snapshot = snapshot.copy(mappingResets = snapshot.mappingResets + 1L)
            return mapped(1)
        }
        val geometryAcquisition = acquireGeometry()
            ?: return fail(snapshot.lastError)
        if (geometryAcquisition.resetActiveCoordinates) return suppressed()
        val geometry = geometryAcquisition.geometry
        val deviceId = resolveDevice(event.deviceKind)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val action = touchAction(event.action)
            ?: return fail(AndroidTargetInputMappingOutcome.InvalidTouchAction)
        val actionIndex = if (event.action.usesTransitionPointer()) {
            event.contacts.indexOfFirst { it.pointerId == event.actionPointerId }.takeIf { it >= 0 }
                ?: return fail(AndroidTargetInputMappingOutcome.InvalidActionPointer)
        } else {
            0
        }
        val actionNeedsTrackedTouch = event.action == InputTouchAction.Down ||
            event.action == InputTouchAction.PointerDown || event.action == InputTouchAction.Move
        if (actionNeedsTrackedTouch && !hasTrackingCapacity(event.deviceSlot)) {
            return fail(AndroidTargetInputMappingOutcome.CapacityExceeded)
        }
        val pointers = Array(event.contacts.size) { index ->
            touchPointer(event.contacts[index], event, geometry)
        }
        val result = injectionController.injectTouch(
            AndroidTouchInjectionEvent(
                stateSlot = event.deviceSlot,
                sourceEventTimeUs = sourceEventTimeUs,
                actionMasked = action,
                actionIndex = actionIndex,
                pointers = pointers,
                source = touchSource(event.deviceKind),
                androidDeviceId = deviceId,
                displayId = geometry.displayId,
            ),
        )
        if (!recordInjection(result)) return injectionFailure(result.error, 0)
        updateTouchState(event)
        snapshot = snapshot.copy(touchFramesMapped = snapshot.touchFramesMapped + 1L)
        return mapped(1)
    }

    private fun mapPointerAbsolute(
        sourceEventTimeUs: Long,
        event: InputPointerAbsolute,
    ): AndroidTargetInputMappingResult {
        val geometryAcquisition = acquireGeometry()
            ?: return fail(snapshot.lastError)
        if (geometryAcquisition.resetActiveCoordinates) return suppressed()
        val geometry = geometryAcquisition.geometry
        val deviceId = resolveDevice(event.deviceKind)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val coordinates = absoluteCoordinates(event.xNormalized, event.yNormalized, geometry)
            ?: return fail(AndroidTargetInputMappingOutcome.InvalidCoordinate)
        return mapPointerSnapshot(
            sourceEventTimeUs = sourceEventTimeUs,
            deviceSlot = event.deviceSlot,
            deviceKind = event.deviceKind,
            currentButtonMask = event.buttonMask,
            xPx = coordinates.first,
            yPx = coordinates.second,
            relativeXPx = 0f,
            relativeYPx = 0f,
            pressure = if (event.pointerFlags and INPUT_POINTER_ABSOLUTE_PRESSURE_VALID != 0) {
                event.pressure.toFloat() / NORMALIZED_MAX.toFloat()
            } else {
                0f
            },
            source = pointerSource(event.deviceKind, relative = false),
            androidDeviceId = deviceId,
            displayId = geometry.displayId,
            absolute = true,
            moved = true,
        )
    }

    private fun mapPointerRelative(
        sourceEventTimeUs: Long,
        event: InputPointerRelative,
    ): AndroidTargetInputMappingResult {
        val geometryAcquisition = acquireGeometry()
            ?: return fail(snapshot.lastError)
        if (geometryAcquisition.resetActiveCoordinates) return suppressed()
        val geometry = geometryAcquisition.geometry
        val deviceId = resolveDevice(event.deviceKind)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val relativeX = relativePixels(event.deltaXQ16_16, geometry.logicalWidthPx)
            ?: return fail(AndroidTargetInputMappingOutcome.InvalidCoordinate)
        val relativeY = relativePixels(event.deltaYQ16_16, geometry.logicalHeightPx)
            ?: return fail(AndroidTargetInputMappingOutcome.InvalidCoordinate)
        return mapPointerSnapshot(
            sourceEventTimeUs = sourceEventTimeUs,
            deviceSlot = event.deviceSlot,
            deviceKind = event.deviceKind,
            currentButtonMask = event.buttonMask,
            xPx = 0f,
            yPx = 0f,
            relativeXPx = relativeX,
            relativeYPx = relativeY,
            pressure = 0f,
            source = pointerSource(event.deviceKind, relative = true),
            androidDeviceId = deviceId,
            displayId = geometry.displayId,
            absolute = false,
            moved = relativeX != 0f || relativeY != 0f,
        )
    }

    private fun mapScroll(sourceEventTimeUs: Long, event: InputScroll): AndroidTargetInputMappingResult {
        val geometry = acquireGeometry()?.geometry
            ?: return fail(snapshot.lastError)
        val deviceId = resolveDevice(event.deviceKind)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val result = injectionController.injectPointer(
            AndroidPointerInjectionEvent(
                stateSlot = event.deviceSlot,
                sourceEventTimeUs = sourceEventTimeUs,
                action = AndroidInjectionConstants.MOTION_ACTION_SCROLL,
                xPx = 0f,
                yPx = 0f,
                horizontalScroll = event.horizontalQ8_8.toFloat() / SCROLL_SCALE.toFloat(),
                verticalScroll = event.verticalQ8_8.toFloat() / SCROLL_SCALE.toFloat(),
                buttonState = androidButtonMask(
                    findSlot(event.deviceSlot)?.pointerButtons ?: event.buttonMask,
                ),
                source = InputDevice.SOURCE_MOUSE,
                androidDeviceId = deviceId,
                displayId = geometry.displayId,
            ),
        )
        if (!recordInjection(result)) return injectionFailure(result.error, 0)
        snapshot = snapshot.copy(scrollMapped = snapshot.scrollMapped + 1L)
        return mapped(1)
    }

    private fun mapGamepad(sourceEventTimeUs: Long, event: InputGamepadState): AndroidTargetInputMappingResult {
        val geometry = acquireGeometry()?.geometry
            ?: return fail(snapshot.lastError)
        val deviceId = resolveDevice(InputDeviceKind.Gamepad)
            ?: return fail(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable)
        val existing = findSlot(event.deviceSlot)
        if (existing == null && !hasTrackingCapacity(event.deviceSlot)) {
            return fail(AndroidTargetInputMappingOutcome.CapacityExceeded)
        }
        val previous = existing?.gamepadState ?: neutralGamepadState(event.deviceSlot)
        val firstState = existing?.gamepadState == null
        if (!firstState && previous == event) {
            snapshot = snapshot.copy(identicalGamepadNoOps = snapshot.identicalGamepadNoOps + 1L)
            return noOp()
        }

        var produced = 0
        val released = previous.buttonMask and event.buttonMask.inv()
        for (mapping in gamepadButtonMappings()) {
            if (released and mapping.portableBit != 0) {
                val result =
                    injectGamepadKey(
                        sourceEventTimeUs,
                        event.deviceSlot,
                        mapping.keyCode,
                        down = false,
                        deviceId,
                        geometry.displayId,
                    )
                if (!recordInjection(result)) return injectionFailure(result.error, produced)
                produced += 1
                snapshot = snapshot.copy(gamepadKeyTransitions = snapshot.gamepadKeyTransitions + 1L)
            }
        }

        val previousHat = if (config.dpadInjectionMode == DpadInjectionMode.HatAxes) {
            deriveHat(previous.buttonMask)
        } else {
            HatState(0f, 0f, opposing = false)
        }
        val currentHat = if (config.dpadInjectionMode == DpadInjectionMode.HatAxes) {
            deriveHat(event.buttonMask)
        } else {
            HatState(0f, 0f, opposing = false)
        }
        if (previousHat.opposing || currentHat.opposing) {
            snapshot = snapshot.copy(opposingDpadStates = snapshot.opposingDpadStates + 1L)
        }
        if (firstState || previous.analogStateChanged(event) || previousHat != currentHat) {
            val result = injectionController.injectJoystick(
                AndroidJoystickInjectionEvent(
                    stateSlot = event.deviceSlot,
                    sourceEventTimeUs = sourceEventTimeUs,
                    leftX = axisToUnit(event.leftX),
                    leftY = axisToUnit(event.leftY),
                    rightX = axisToUnit(event.rightX),
                    rightY = axisToUnit(event.rightY),
                    leftTrigger = triggerToUnit(event.leftTrigger),
                    rightTrigger = triggerToUnit(event.rightTrigger),
                    hatX = currentHat.x,
                    hatY = currentHat.y,
                    source = InputDevice.SOURCE_JOYSTICK,
                    androidDeviceId = deviceId,
                    displayId = geometry.displayId,
                ),
            )
            if (!recordInjection(result)) return injectionFailure(result.error, produced)
            produced += 1
            snapshot = snapshot.copy(gamepadMotionEvents = snapshot.gamepadMotionEvents + 1L)
        }

        val pressed = event.buttonMask and previous.buttonMask.inv()
        for (mapping in gamepadButtonMappings()) {
            if (pressed and mapping.portableBit != 0) {
                val result =
                    injectGamepadKey(
                        sourceEventTimeUs,
                        event.deviceSlot,
                        mapping.keyCode,
                        down = true,
                        deviceId,
                        geometry.displayId,
                    )
                if (!recordInjection(result)) return injectionFailure(result.error, produced)
                produced += 1
                snapshot = snapshot.copy(gamepadKeyTransitions = snapshot.gamepadKeyTransitions + 1L)
            }
        }
        requireNotNull(findOrCreateSlot(event.deviceSlot)).gamepadState = event
        snapshot = snapshot.copy(gamepadSnapshotsMapped = snapshot.gamepadSnapshotsMapped + 1L)
        return mapped(produced)
    }

    private fun mapReset(event: InputResetState): AndroidTargetInputMappingResult {
        val scope = when (event.scope) {
            InputResetScope.ThisDevice -> InjectionResetScope.ThisSlot
            InputResetScope.AllDevices -> InjectionResetScope.AllSlots
            InputResetScope.Unknown -> return fail(AndroidTargetInputMappingOutcome.InvalidInput)
        }
        val result = injectionController.resetState(
            scope,
            if (scope == InjectionResetScope.ThisSlot) event.deviceSlot else 0,
            event.reason.toInjectionReason(),
        )
        if (!result.isSuccess) return resetFailure(result.error)
        if (scope == InjectionResetScope.ThisSlot) clearSlot(event.deviceSlot) else clearAllSlots()
        snapshot = snapshot.copy(mappingResets = snapshot.mappingResets + 1L)
        return mapped(1)
    }

    private fun mapPointerSnapshot(
        sourceEventTimeUs: Long,
        deviceSlot: Int,
        deviceKind: InputDeviceKind,
        currentButtonMask: Int,
        xPx: Float,
        yPx: Float,
        relativeXPx: Float,
        relativeYPx: Float,
        pressure: Float,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
        absolute: Boolean,
        moved: Boolean,
    ): AndroidTargetInputMappingResult {
        val existing = findSlot(deviceSlot)
        if (existing == null && currentButtonMask != 0 && !hasTrackingCapacity(deviceSlot)) {
            return fail(AndroidTargetInputMappingOutcome.CapacityExceeded)
        }
        val previousButtonMask = findSlot(deviceSlot)?.pointerButtons ?: 0
        val released = previousButtonMask and currentButtonMask.inv()
        val pressed = currentButtonMask and previousButtonMask.inv()
        var produced = 0
        var progressiveState = previousButtonMask
        for (mapping in POINTER_BUTTON_MAPPINGS) {
            if (released and mapping.portableBit != 0) {
                progressiveState = progressiveState and mapping.portableBit.inv()
                val result = injectPointer(
                    sourceEventTimeUs,
                    deviceSlot,
                    AndroidInjectionConstants.MOTION_ACTION_BUTTON_RELEASE,
                    mapping.androidButton,
                    xPx,
                    yPx,
                    relativeXPx,
                    relativeYPx,
                    pressureForButtons(currentButtonMask, pressure),
                    androidButtonMask(progressiveState),
                    source,
                    androidDeviceId,
                    displayId,
                )
                if (!recordInjection(result)) return injectionFailure(result.error, produced)
                produced += 1
            }
        }
        val previousMainDown = isMainPointerDown(previousButtonMask)
        val currentMainDown = isMainPointerDown(currentButtonMask)
        val mainAction = when {
            !previousMainDown && currentMainDown -> AndroidInjectionConstants.MOTION_ACTION_DOWN
            previousMainDown && !currentMainDown -> AndroidInjectionConstants.MOTION_ACTION_UP
            currentMainDown -> AndroidInjectionConstants.MOTION_ACTION_MOVE
            absolute -> AndroidInjectionConstants.MOTION_ACTION_HOVER_MOVE
            moved -> AndroidInjectionConstants.MOTION_ACTION_MOVE
            else -> null
        }
        if (mainAction != null) {
            val result = injectPointer(
                sourceEventTimeUs,
                deviceSlot,
                mainAction,
                actionButton = 0,
                xPx = xPx,
                yPx = yPx,
                relativeXPx = relativeXPx,
                relativeYPx = relativeYPx,
                pressure = pressureForButtons(currentButtonMask, pressure),
                buttonState = androidButtonMask(currentButtonMask),
                source = source,
                androidDeviceId = androidDeviceId,
                displayId = displayId,
            )
            if (!recordInjection(result)) return injectionFailure(result.error, produced)
            produced += 1
        }
        progressiveState = previousButtonMask and released.inv()
        for (mapping in POINTER_BUTTON_MAPPINGS) {
            if (pressed and mapping.portableBit != 0) {
                progressiveState = progressiveState or mapping.portableBit
                val result = injectPointer(
                    sourceEventTimeUs,
                    deviceSlot,
                    AndroidInjectionConstants.MOTION_ACTION_BUTTON_PRESS,
                    mapping.androidButton,
                    xPx,
                    yPx,
                    relativeXPx,
                    relativeYPx,
                    pressureForButtons(currentButtonMask, pressure),
                    androidButtonMask(progressiveState),
                    source,
                    androidDeviceId,
                    displayId,
                )
                if (!recordInjection(result)) return injectionFailure(result.error, produced)
                produced += 1
            }
        }
        if (absolute && previousMainDown && !currentMainDown) {
            val result = injectPointer(
                sourceEventTimeUs,
                deviceSlot,
                AndroidInjectionConstants.MOTION_ACTION_HOVER_MOVE,
                actionButton = 0,
                xPx = xPx,
                yPx = yPx,
                relativeXPx = 0f,
                relativeYPx = 0f,
                pressure = 0f,
                buttonState = androidButtonMask(currentButtonMask),
                source = source,
                androidDeviceId = androidDeviceId,
                displayId = displayId,
            )
            if (!recordInjection(result)) return injectionFailure(result.error, produced)
            produced += 1
        }
        updatePointerState(deviceSlot, currentButtonMask)
        snapshot = snapshot.copy(
            pointerSnapshotsMapped = snapshot.pointerSnapshotsMapped + 1L,
            pointerAndroidEventsProduced = snapshot.pointerAndroidEventsProduced + produced.toLong(),
        )
        return if (produced == 0) noOp() else mapped(produced)
    }

    private fun acquireGeometry(): GeometryAcquisition? {
        val geometry = displayGeometryProvider.geometryFor(config.targetDisplayId)
        if (geometry == null || !geometry.isUsable()) {
            snapshot = snapshot.copy(lastError = AndroidTargetInputMappingOutcome.TargetDisplayUnavailable)
            return null
        }
        val previous = lastGeometry
        var resetActiveCoordinates = false
        if (previous != null && previous.geometryIdentityChanged(geometry)) {
            for (slot in slots.filterNotNull().filter { it.hasActiveCoordinates }) {
                val result = injectionController.resetState(
                    InjectionResetScope.ThisSlot,
                    slot.deviceSlot,
                    InjectionResetReason.ErrorRecovery,
                )
                if (!result.isSuccess) {
                    snapshot = snapshot.copy(
                        state = AndroidTargetInputMappingState.Degraded,
                        lastError = AndroidTargetInputMappingOutcome.ResetInjectionFailure,
                    )
                    return null
                }
                clearSlot(slot.deviceSlot)
                resetActiveCoordinates = true
                snapshot = snapshot.copy(
                    geometryResets = snapshot.geometryResets + 1L,
                    mappingResets = snapshot.mappingResets + 1L,
                )
            }
        }
        lastGeometry = geometry
        snapshot = snapshot.copy(
            state = AndroidTargetInputMappingState.Ready,
            targetDisplayId = geometry.displayId,
            logicalWidthPx = geometry.logicalWidthPx,
            logicalHeightPx = geometry.logicalHeightPx,
            rotation = geometry.rotation,
            geometryGeneration = geometry.generation,
            lastError = AndroidTargetInputMappingOutcome.Mapped,
        )
        return GeometryAcquisition(geometry, resetActiveCoordinates)
    }

    private fun resolveDevice(kind: InputDeviceKind): Int? {
        if (kind == InputDeviceKind.Unknown) return null
        val resolution = deviceResolver.resolve(kind, config.deviceResolutionPolicyFor(kind))
        return resolution.deviceId
    }

    private fun touchPointer(
        contact: InputTouchContact,
        event: InputTouchFrame,
        geometry: TargetDisplayGeometry,
    ): AndroidTouchPointer {
        val coordinates = absoluteCoordinates(contact.xNormalized, contact.yNormalized, geometry)
            ?: throw IllegalArgumentException("Validated normalized coordinate did not map")
        val isReleasedActionPointer = event.action.isReleaseOrCancel() && contact.pointerId == event.actionPointerId
        val pressure = if (contact.pointerFlags and INPUT_TOUCH_PRESSURE_VALID != 0) {
            contact.pressure.toFloat() / NORMALIZED_MAX.toFloat()
        } else if (isReleasedActionPointer) {
            0f
        } else {
            1f
        }
        val size = if (contact.pointerFlags and INPUT_TOUCH_SIZE_VALID != 0) {
            contact.size.toFloat() / NORMALIZED_MAX.toFloat()
        } else {
            1f
        }
        return AndroidTouchPointer(
            pointerId = contact.pointerId,
            toolType = touchToolType(contact.toolType),
            xPx = coordinates.first,
            yPx = coordinates.second,
            pressure = pressure,
            size = size,
        )
    }

    private fun injectPointer(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        action: Int,
        actionButton: Int,
        xPx: Float,
        yPx: Float,
        relativeXPx: Float,
        relativeYPx: Float,
        pressure: Float,
        buttonState: Int,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
    ) = injectionController.injectPointer(
        AndroidPointerInjectionEvent(
            stateSlot = stateSlot,
            sourceEventTimeUs = sourceEventTimeUs,
            action = action,
            actionButton = actionButton,
            xPx = xPx,
            yPx = yPx,
            relativeXPx = relativeXPx,
            relativeYPx = relativeYPx,
            pressure = pressure,
            buttonState = buttonState,
            source = source,
            androidDeviceId = androidDeviceId,
            displayId = displayId,
        ),
    )

    private fun injectGamepadKey(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        keyCode: Int,
        down: Boolean,
        androidDeviceId: Int,
        displayId: Int,
    ) = injectionController.injectKey(
        AndroidKeyInjectionEvent(
            stateSlot = stateSlot,
            sourceEventTimeUs = sourceEventTimeUs,
            action = if (down) AndroidInjectionConstants.KEY_ACTION_DOWN else AndroidInjectionConstants.KEY_ACTION_UP,
            keyCode = keyCode,
            source = InputDevice.SOURCE_GAMEPAD,
            androidDeviceId = androidDeviceId,
            displayId = displayId,
        ),
    )

    private fun updateTouchState(event: InputTouchFrame) {
        when (event.action) {
            InputTouchAction.Down,
            InputTouchAction.PointerDown,
            InputTouchAction.Move,
            -> requireNotNull(findOrCreateSlot(event.deviceSlot)).touchActive = true
            InputTouchAction.PointerUp -> findSlot(event.deviceSlot)?.let {
                it.touchActive = event.contacts.size > 1
                removeSlotIfIdle(it)
            }
            InputTouchAction.Up,
            InputTouchAction.Cancel,
            InputTouchAction.Unknown,
            -> findSlot(event.deviceSlot)?.let {
                it.touchActive = false
                removeSlotIfIdle(it)
            }
        }
    }

    private fun updatePointerState(deviceSlot: Int, buttonMask: Int) {
        val slot = findSlot(deviceSlot) ?: if (buttonMask != 0) findOrCreateSlot(deviceSlot) else null
        if (slot == null) return
        slot.pointerButtons = buttonMask
        removeSlotIfIdle(slot)
    }

    private fun absoluteCoordinates(
        xNormalized: Int,
        yNormalized: Int,
        geometry: TargetDisplayGeometry,
    ): Pair<Float, Float>? {
        if (!geometry.isUsable() || xNormalized !in 0..NORMALIZED_MAX ||
            yNormalized !in 0..NORMALIZED_MAX
        ) {
            return null
        }
        return normalizedToPixel(xNormalized, geometry.logicalWidthPx).toFloat() to
            normalizedToPixel(yNormalized, geometry.logicalHeightPx).toFloat()
    }

    private fun normalizedToPixel(normalized: Int, dimension: Int): Int = when {
        dimension <= 1 -> 0
        else -> (
            (normalized.toLong() * (dimension - 1).toLong() + NORMALIZED_MAX / 2L) /
                NORMALIZED_MAX
            ).toInt()
    }

    private fun relativePixels(value: Int, dimension: Int): Float? = try {
        val numerator = Math.multiplyExact(value.toLong(), dimension.toLong())
        val result = numerator.toDouble() / RELATIVE_SCALE.toDouble()
        if (result.isFinite() && result in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()) {
            result.toFloat()
        } else {
            null
        }
    } catch (_: ArithmeticException) {
        null
    }

    private fun touchAction(action: InputTouchAction): Int? = when (action) {
        InputTouchAction.Down -> AndroidInjectionConstants.MOTION_ACTION_DOWN
        InputTouchAction.Up -> AndroidInjectionConstants.MOTION_ACTION_UP
        InputTouchAction.Move -> AndroidInjectionConstants.MOTION_ACTION_MOVE
        InputTouchAction.Cancel -> AndroidInjectionConstants.MOTION_ACTION_CANCEL
        InputTouchAction.PointerDown -> AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN
        InputTouchAction.PointerUp -> AndroidInjectionConstants.MOTION_ACTION_POINTER_UP
        InputTouchAction.Unknown -> null
    }

    private fun touchSource(kind: InputDeviceKind): Int = when (kind) {
        InputDeviceKind.Touchscreen -> InputDevice.SOURCE_TOUCHSCREEN
        InputDeviceKind.Stylus -> InputDevice.SOURCE_STYLUS
        InputDeviceKind.Touchpad -> InputDevice.SOURCE_TOUCHPAD
        else -> InputDevice.SOURCE_TOUCHSCREEN
    }

    private fun pointerSource(kind: InputDeviceKind, relative: Boolean): Int = when {
        relative -> InputDevice.SOURCE_MOUSE_RELATIVE
        kind == InputDeviceKind.Stylus -> InputDevice.SOURCE_STYLUS
        kind == InputDeviceKind.Touchpad -> InputDevice.SOURCE_TOUCHPAD
        else -> InputDevice.SOURCE_MOUSE
    }

    private fun touchToolType(type: InputTouchToolType): Int = when (type) {
        InputTouchToolType.Unknown -> MotionEvent.TOOL_TYPE_UNKNOWN
        InputTouchToolType.Finger -> MotionEvent.TOOL_TYPE_FINGER
        InputTouchToolType.Stylus -> MotionEvent.TOOL_TYPE_STYLUS
        InputTouchToolType.Eraser -> MotionEvent.TOOL_TYPE_ERASER
        InputTouchToolType.Mouse -> MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun androidMetaState(modifierMask: Int): Int {
        var metaState = 0
        if (modifierMask and INPUT_MODIFIER_LEFT_CONTROL != 0) {
            metaState = metaState or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        }
        if (modifierMask and INPUT_MODIFIER_RIGHT_CONTROL != 0) {
            metaState = metaState or KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON
        }
        if (modifierMask and INPUT_MODIFIER_LEFT_SHIFT != 0) {
            metaState = metaState or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
        }
        if (modifierMask and INPUT_MODIFIER_RIGHT_SHIFT != 0) {
            metaState = metaState or KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
        }
        if (modifierMask and INPUT_MODIFIER_LEFT_ALT != 0) {
            metaState = metaState or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON
        }
        if (modifierMask and INPUT_MODIFIER_RIGHT_ALT != 0) {
            metaState = metaState or KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
        }
        if (modifierMask and INPUT_MODIFIER_LEFT_GUI != 0) {
            metaState = metaState or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_ON
        }
        if (modifierMask and INPUT_MODIFIER_RIGHT_GUI != 0) {
            metaState = metaState or KeyEvent.META_META_RIGHT_ON or KeyEvent.META_META_ON
        }
        return metaState
    }

    private fun androidButtonMask(portableMask: Int): Int {
        var result = 0
        for (mapping in POINTER_BUTTON_MAPPINGS) {
            if (portableMask and mapping.portableBit != 0) result = result or mapping.androidButton
        }
        return result
    }

    private fun pressureForButtons(buttonMask: Int, pressure: Float): Float =
        if (isMainPointerDown(buttonMask)) 1f else pressure.coerceAtLeast(0f)

    private fun isMainPointerDown(mask: Int): Boolean =
        mask and (INPUT_POINTER_BUTTON_PRIMARY or INPUT_POINTER_BUTTON_SECONDARY or INPUT_POINTER_BUTTON_TERTIARY) != 0

    private fun gamepadButtonMappings(): List<GamepadButtonMapping> =
        if (config.dpadInjectionMode == DpadInjectionMode.HatAxes) {
            GAMEPAD_BUTTON_MAPPINGS_WITHOUT_DPAD
        } else {
            GAMEPAD_BUTTON_MAPPINGS
        }

    private fun deriveHat(buttonMask: Int): HatState {
        val horizontalConflict = buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_LEFT != 0 &&
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_RIGHT != 0
        val verticalConflict = buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_UP != 0 &&
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_DOWN != 0
        val x = when {
            horizontalConflict -> 0f
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_LEFT != 0 -> -1f
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_RIGHT != 0 -> 1f
            else -> 0f
        }
        val y = when {
            verticalConflict -> 0f
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_UP != 0 -> -1f
            buttonMask and INPUT_GAMEPAD_BUTTON_DPAD_DOWN != 0 -> 1f
            else -> 0f
        }
        return HatState(x, y, horizontalConflict || verticalConflict)
    }

    private fun axisToUnit(value: Int): Float = value.toFloat() / GAMEPAD_AXIS_MAX.toFloat()

    private fun triggerToUnit(value: Int): Float = value.toFloat() / NORMALIZED_MAX.toFloat()

    private fun InputGamepadState.analogStateChanged(other: InputGamepadState): Boolean =
        leftX != other.leftX || leftY != other.leftY || rightX != other.rightX || rightY != other.rightY ||
            leftTrigger != other.leftTrigger || rightTrigger != other.rightTrigger

    private fun InputTouchAction.usesTransitionPointer(): Boolean = when (this) {
        InputTouchAction.Down,
        InputTouchAction.Up,
        InputTouchAction.PointerDown,
        InputTouchAction.PointerUp,
        -> true
        else -> false
    }

    private fun InputTouchAction.isReleaseOrCancel(): Boolean = when (this) {
        InputTouchAction.Up,
        InputTouchAction.PointerUp,
        InputTouchAction.Cancel,
        -> true
        else -> false
    }

    private fun InputResetReason.toInjectionReason(): InjectionResetReason = when (this) {
        InputResetReason.SessionStop -> InjectionResetReason.SessionStop
        InputResetReason.DeviceDisconnected -> InjectionResetReason.DeviceDisconnected
        InputResetReason.FocusLost -> InjectionResetReason.FocusLost
        InputResetReason.ErrorRecovery -> InjectionResetReason.ErrorRecovery
        InputResetReason.UserRequest -> InjectionResetReason.UserRequest
        InputResetReason.Unknown -> InjectionResetReason.ErrorRecovery
    }

    private fun TargetDisplayGeometry.geometryIdentityChanged(other: TargetDisplayGeometry): Boolean =
        displayId != other.displayId ||
            logicalWidthPx != other.logicalWidthPx ||
            logicalHeightPx != other.logicalHeightPx ||
            rotation != other.rotation ||
            generation != other.generation

    private fun recordInjection(result: io.warpnect.input.injection.InputInjectionResult): Boolean {
        if (!result.isSuccess) {
            snapshot = snapshot.copy(
                state = AndroidTargetInputMappingState.Degraded,
                lastError = AndroidTargetInputMappingOutcome.InjectionFailure,
            )
            return false
        }
        snapshot = snapshot.copy(
            androidEventsProduced = snapshot.androidEventsProduced + 1L,
        )
        return true
    }

    private fun mapped(produced: Int): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(
            state = AndroidTargetInputMappingState.Ready,
            lastError = AndroidTargetInputMappingOutcome.Mapped,
        )
        val outcome = if (produced == 1) {
            AndroidTargetInputMappingOutcome.Mapped
        } else {
            AndroidTargetInputMappingOutcome.MappedMultiple
        }
        return AndroidTargetInputMappingResult(
            outcome = outcome,
            androidEventsProduced = produced,
        )
    }

    private fun noOp(): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(lastError = AndroidTargetInputMappingOutcome.NoOp)
        return AndroidTargetInputMappingResult(AndroidTargetInputMappingOutcome.NoOp)
    }

    private fun suppressed(): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(
            eventsSuppressed = snapshot.eventsSuppressed + 1L,
            lastError = AndroidTargetInputMappingOutcome.Suppressed,
        )
        return AndroidTargetInputMappingResult(AndroidTargetInputMappingOutcome.Suppressed)
    }

    private fun fail(outcome: AndroidTargetInputMappingOutcome): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(lastError = outcome)
        return AndroidTargetInputMappingResult(outcome)
    }

    private fun injectionFailure(error: InputInjectionError, produced: Int): AndroidTargetInputMappingResult =
        AndroidTargetInputMappingResult(AndroidTargetInputMappingOutcome.InjectionFailure, produced, error)

    private fun resetFailure(error: InputInjectionError): AndroidTargetInputMappingResult {
        snapshot = snapshot.copy(
            state = AndroidTargetInputMappingState.Degraded,
            lastError = AndroidTargetInputMappingOutcome.ResetInjectionFailure,
        )
        return AndroidTargetInputMappingResult(
            AndroidTargetInputMappingOutcome.ResetInjectionFailure,
            injectionError = error,
        )
    }

    private fun findSlot(deviceSlot: Int): TargetSlot? = slots.firstOrNull { it?.deviceSlot == deviceSlot }

    private fun findOrCreateSlot(deviceSlot: Int): TargetSlot? {
        findSlot(deviceSlot)?.let { return it }
        val index = slots.indexOfFirst { it == null }
        if (index < 0) return null
        return TargetSlot(deviceSlot).also { slots[index] = it }
    }

    private fun hasTrackingCapacity(deviceSlot: Int): Boolean = findSlot(deviceSlot) != null || slots.any { it == null }

    private fun clearSlot(deviceSlot: Int) {
        val index = slots.indexOfFirst { it?.deviceSlot == deviceSlot }
        if (index >= 0) slots[index] = null
    }

    private fun clearAllSlots() {
        slots.fill(null)
    }

    private fun removeSlotIfIdle(slot: TargetSlot) {
        if (!slot.touchActive && slot.pointerButtons == 0 && slot.gamepadState == null) clearSlot(slot.deviceSlot)
    }

    private fun neutralGamepadState(deviceSlot: Int): InputGamepadState = InputGamepadState(
        deviceSlot = deviceSlot,
        buttonMask = 0,
        leftX = 0,
        leftY = 0,
        rightX = 0,
        rightY = 0,
        leftTrigger = 0,
        rightTrigger = 0,
    )

    private fun WarpnectInputEvent.validationError(): InputModelError = when (this) {
        is InputKeyEvent -> validate()
        is InputTouchFrame -> validate()
        is InputPointerAbsolute -> validate()
        is InputPointerRelative -> validate()
        is InputScroll -> validate()
        is InputGamepadState -> validate()
        is InputResetState -> validate()
    }

    private data class TargetSlot(
        val deviceSlot: Int,
        var touchActive: Boolean = false,
        var pointerButtons: Int = 0,
        var gamepadState: InputGamepadState? = null,
    ) {
        val hasActiveCoordinates: Boolean
            get() = touchActive || pointerButtons != 0
    }

    private data class PointerButtonMapping(
        val portableBit: Int,
        val androidButton: Int,
    )

    private data class GamepadButtonMapping(
        val portableBit: Int,
        val keyCode: Int,
        val isDpad: Boolean = false,
    )

    private data class HatState(
        val x: Float,
        val y: Float,
        val opposing: Boolean,
    )

    private data class GeometryAcquisition(
        val geometry: TargetDisplayGeometry,
        val resetActiveCoordinates: Boolean,
    )

    private companion object {
        const val NORMALIZED_MAX = 65_535
        const val RELATIVE_SCALE = 65_536
        const val SCROLL_SCALE = 256
        const val GAMEPAD_AXIS_MAX = 32_767

        val POINTER_BUTTON_MAPPINGS = listOf(
            PointerButtonMapping(INPUT_POINTER_BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY),
            PointerButtonMapping(INPUT_POINTER_BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY),
            PointerButtonMapping(INPUT_POINTER_BUTTON_TERTIARY, MotionEvent.BUTTON_TERTIARY),
            PointerButtonMapping(INPUT_POINTER_BUTTON_BACK, MotionEvent.BUTTON_BACK),
            PointerButtonMapping(INPUT_POINTER_BUTTON_FORWARD, MotionEvent.BUTTON_FORWARD),
        )

        val GAMEPAD_BUTTON_MAPPINGS = listOf(
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_A, KeyEvent.KEYCODE_BUTTON_A),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_B, KeyEvent.KEYCODE_BUTTON_B),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_X, KeyEvent.KEYCODE_BUTTON_X),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_Y),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_LEFT_SHOULDER, KeyEvent.KEYCODE_BUTTON_L1),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_RIGHT_SHOULDER, KeyEvent.KEYCODE_BUTTON_R1),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_LEFT_TRIGGER, KeyEvent.KEYCODE_BUTTON_L2),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_RIGHT_TRIGGER, KeyEvent.KEYCODE_BUTTON_R2),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_SELECT_BACK, KeyEvent.KEYCODE_BUTTON_SELECT),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_START, KeyEvent.KEYCODE_BUTTON_START),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_GUIDE_MODE, KeyEvent.KEYCODE_BUTTON_MODE),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_LEFT_STICK, KeyEvent.KEYCODE_BUTTON_THUMBL),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_RIGHT_STICK, KeyEvent.KEYCODE_BUTTON_THUMBR),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, isDpad = true),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, isDpad = true),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, isDpad = true),
            GamepadButtonMapping(INPUT_GAMEPAD_BUTTON_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, isDpad = true),
        )

        val GAMEPAD_BUTTON_MAPPINGS_WITHOUT_DPAD = GAMEPAD_BUTTON_MAPPINGS.filterNot { it.isDpad }
    }
}
