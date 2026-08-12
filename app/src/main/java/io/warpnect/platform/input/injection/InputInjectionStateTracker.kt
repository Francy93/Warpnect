package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.injection.MAX_TOUCH_POINTERS

/**
 * Bounded state required for local Android event timing and explicit reset. Callers serialize access.
 */
internal class InputInjectionStateTracker(
    private val config: InputInjectionConfig,
    private val localUptimeClock: () -> Long,
    private val dispatcher: AndroidInputEventDispatcher,
) {
    private val slots = arrayOfNulls<TrackedSlot>(config.maxTrackedInjectionSlots)
    private var lastSourceEventTimeUs: Long? = null
    private var lastLocalInjectionTimeMs: Long? = null
    private var lastError = InputInjectionError.None
    private var keyAttempts = 0L
    private var keySubmitted = 0L
    private var keyFailures = 0L
    private var touchAttempts = 0L
    private var touchSubmitted = 0L
    private var touchFailures = 0L
    private var pointerAttempts = 0L
    private var pointerSubmitted = 0L
    private var pointerFailures = 0L
    private var joystickAttempts = 0L
    private var joystickSubmitted = 0L
    private var joystickFailures = 0L
    private var resetRequests = 0L
    private var resetComplete = 0L
    private var resetPartial = 0L
    private var syntheticKeyUps = 0L
    private var syntheticTouchCancels = 0L
    private var syntheticPointerReleases = 0L
    private var syntheticJoystickNeutralEvents = 0L
    private var orphanKeyUps = 0L
    private var invalidTouchSequences = 0L
    private var permissionFailures = 0L
    private var serviceFailures = 0L
    private var stateMayRemainInjected = false

    fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionServiceResult {
        val validation = validateKey(event)
        if (validation != InputInjectionError.None) return reject(validation)
        var slot = findSlot(event.stateSlot)
        val existing = slot?.pressedKeys?.get(event.keyCode)
        if (event.action == AndroidInjectionConstants.KEY_ACTION_DOWN && existing == null) {
            slot = findOrCreateSlot(event.stateSlot) ?: return reject(InputInjectionError.StateSlotCapacityReached)
            if (slot.pressedKeys.size >= config.maxPressedKeysPerSlot) {
                return reject(InputInjectionError.PressedKeyCapacityReached)
            }
        }
        val localEventTime = localUptimeClock()
        val downTime = when (event.action) {
            AndroidInjectionConstants.KEY_ACTION_DOWN -> existing?.downTimeMs ?: localEventTime
            else -> existing?.downTimeMs ?: localEventTime
        }
        if (event.action == AndroidInjectionConstants.KEY_ACTION_UP && existing == null) {
            orphanKeyUps += 1
        }

        keyAttempts += 1
        val result = dispatchKey(event, localEventTime, downTime)
        recordTiming(event.sourceEventTimeUs, localEventTime)
        if (!result.isAccepted) {
            keyFailures += 1
            return result
        }
        keySubmitted += 1
        when (event.action) {
            AndroidInjectionConstants.KEY_ACTION_DOWN -> {
                requireNotNull(slot).pressedKeys[event.keyCode] = KeyState(event, downTime)
            }
            AndroidInjectionConstants.KEY_ACTION_UP -> slot?.pressedKeys?.remove(event.keyCode)
        }
        slot?.let(::removeSlotIfInactive)
        return result
    }

    fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionServiceResult {
        val validation = validateTouch(event)
        if (validation != InputInjectionError.None) return reject(validation)
        val slot = findOrCreateSlot(event.stateSlot) ?: return reject(InputInjectionError.StateSlotCapacityReached)
        val existing = slot.touch
        val isDown = event.actionMasked == AndroidInjectionConstants.MOTION_ACTION_DOWN
        val isContinuation = !isDown
        if (isDown && existing != null) {
            invalidTouchSequences += 1
            return reject(InputInjectionError.InvalidTouchSequence)
        }
        if (isContinuation && existing == null) {
            invalidTouchSequences += 1
            return reject(InputInjectionError.InvalidTouchSequence)
        }
        val localEventTime = localUptimeClock()
        val downTime = if (isDown) localEventTime else requireNotNull(existing).downTimeMs
        touchAttempts += 1
        val result = dispatchTouch(event, localEventTime, downTime)
        recordTiming(event.sourceEventTimeUs, localEventTime)
        if (!result.isAccepted) {
            touchFailures += 1
            return result
        }
        touchSubmitted += 1
        when (event.actionMasked) {
            AndroidInjectionConstants.MOTION_ACTION_UP,
            AndroidInjectionConstants.MOTION_ACTION_CANCEL,
            -> slot.touch = null
            AndroidInjectionConstants.MOTION_ACTION_POINTER_UP -> slot.touch = TouchState(
                event.withLiftedPointerRemoved(),
                downTime,
            )
            else -> slot.touch = TouchState(event.copy(pointers = event.pointers.copyOf()), downTime)
        }
        removeSlotIfInactive(slot)
        return result
    }

    fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionServiceResult {
        val validation = validatePointer(event)
        if (validation != InputInjectionError.None) return reject(validation)
        val slot = findOrCreateSlot(event.stateSlot) ?: return reject(InputInjectionError.StateSlotCapacityReached)
        val existing = slot.pointer
        val localEventTime = localUptimeClock()
        val downTime = when (event.action) {
            AndroidInjectionConstants.MOTION_ACTION_DOWN -> existing?.downTimeMs ?: localEventTime
            AndroidInjectionConstants.MOTION_ACTION_MOVE,
            AndroidInjectionConstants.MOTION_ACTION_UP,
            AndroidInjectionConstants.MOTION_ACTION_CANCEL,
            -> existing?.downTimeMs ?: localEventTime
            else -> localEventTime
        }
        pointerAttempts += 1
        val result = dispatchPointer(event, localEventTime, downTime)
        recordTiming(event.sourceEventTimeUs, localEventTime)
        if (!result.isAccepted) {
            pointerFailures += 1
            return result
        }
        pointerSubmitted += 1
        slot.pointer = when (event.action) {
            AndroidInjectionConstants.MOTION_ACTION_UP,
            AndroidInjectionConstants.MOTION_ACTION_CANCEL,
            -> null
            else -> PointerState(event, downTime)
        }
        removeSlotIfInactive(slot)
        return result
    }

    fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionServiceResult {
        val validation = validateJoystick(event)
        if (validation != InputInjectionError.None) return reject(validation)
        val slot = findOrCreateSlot(event.stateSlot) ?: return reject(InputInjectionError.StateSlotCapacityReached)
        val localEventTime = localUptimeClock()
        joystickAttempts += 1
        val result = dispatchJoystick(event, localEventTime)
        recordTiming(event.sourceEventTimeUs, localEventTime)
        if (!result.isAccepted) {
            joystickFailures += 1
            return result
        }
        joystickSubmitted += 1
        slot.joystick = if (event.isNeutral()) null else event
        removeSlotIfInactive(slot)
        return result
    }

    fun reset(scope: InputResetScope, stateSlot: Int, reason: Int): InputInjectionServiceResult {
        if (scope == InputResetScope.ThisSlot && !validStateSlot(stateSlot)) {
            return reject(InputInjectionError.InvalidStateSlot)
        }
        resetRequests += 1
        var partial = false
        when (scope) {
            InputResetScope.ThisSlot -> findSlot(stateSlot)?.let {
                if (!resetSlot(it, reason)) partial = true
            }
            InputResetScope.AllSlots -> slots.forEach { slot ->
                if (slot != null && !resetSlot(slot, reason)) partial = true
            }
        }
        if (partial) {
            resetPartial += 1
            stateMayRemainInjected = true
            return InputInjectionServiceResult.ResetPartial
        }
        resetComplete += 1
        return InputInjectionServiceResult.ResetComplete
    }

    fun snapshot(
        state: InputInjectionState,
        apiResolved: Boolean,
        targetUidSupported: Boolean,
        displayTargetingSupported: Boolean,
    ): InputInjectionSnapshot {
        val activeSlots = slots.filterNotNull()
        return InputInjectionSnapshot(
            state = state,
            injectionMode = config.injectionMode,
            targetUid = config.targetUid,
            apiResolved = apiResolved,
            targetUidSupported = targetUidSupported,
            displayTargetingSupported = displayTargetingSupported,
            keyAttempts = keyAttempts,
            keySubmitted = keySubmitted,
            keyFailures = keyFailures,
            touchAttempts = touchAttempts,
            touchSubmitted = touchSubmitted,
            touchFailures = touchFailures,
            pointerAttempts = pointerAttempts,
            pointerSubmitted = pointerSubmitted,
            pointerFailures = pointerFailures,
            joystickAttempts = joystickAttempts,
            joystickSubmitted = joystickSubmitted,
            joystickFailures = joystickFailures,
            resetRequests = resetRequests,
            resetComplete = resetComplete,
            resetPartial = resetPartial,
            syntheticKeyUps = syntheticKeyUps,
            syntheticTouchCancels = syntheticTouchCancels,
            syntheticPointerReleases = syntheticPointerReleases,
            syntheticJoystickNeutralEvents = syntheticJoystickNeutralEvents,
            orphanKeyUps = orphanKeyUps,
            invalidTouchSequences = invalidTouchSequences,
            trackedStateSlots = activeSlots.size,
            activePressedKeys = activeSlots.sumOf { it.pressedKeys.size },
            activeTouchStreams = activeSlots.count { it.touch != null },
            activePointerButtonStates = activeSlots.count { it.pointer?.event?.buttonState != 0 },
            activeJoystickStates = activeSlots.count { it.joystick != null },
            lastSourceEventTimeUs = lastSourceEventTimeUs,
            lastLocalInjectionTimeMs = lastLocalInjectionTimeMs,
            permissionFailures = permissionFailures,
            serviceFailures = serviceFailures,
            stateMayRemainInjected = stateMayRemainInjected,
            lastError = lastError,
        )
    }

    private fun resetSlot(slot: TrackedSlot, reason: Int): Boolean {
        var complete = true
        val resetTime = localUptimeClock()
        for (keyState in slot.pressedKeys.values.sortedBy { it.event.keyCode }) {
            val up = keyState.event.copy(
                action = AndroidInjectionConstants.KEY_ACTION_UP,
                repeatCount = 0,
            )
            val result = dispatchKey(up, resetTime, keyState.downTimeMs)
            if (result.isAccepted) {
                slot.pressedKeys.remove(keyState.event.keyCode)
                syntheticKeyUps += 1
            } else {
                complete = false
            }
        }
        slot.touch?.let { touch ->
            val cancel = touch.event.copy(
                actionMasked = AndroidInjectionConstants.MOTION_ACTION_CANCEL,
                actionIndex = 0,
            )
            if (dispatchTouch(cancel, resetTime, touch.downTimeMs).isAccepted) {
                slot.touch = null
                syntheticTouchCancels += 1
            } else {
                complete = false
            }
        }
        slot.pointer?.let { pointer ->
            if (pointer.event.buttonState != 0) {
                val neutral = pointer.event.copy(
                    action = AndroidInjectionConstants.MOTION_ACTION_CANCEL,
                    actionButton = 0,
                    buttonState = 0,
                )
                if (dispatchPointer(neutral, resetTime, pointer.downTimeMs).isAccepted) {
                    slot.pointer = null
                    syntheticPointerReleases += 1
                } else {
                    complete = false
                }
            } else {
                slot.pointer = null
            }
        }
        slot.joystick?.let { joystick ->
            if (dispatchJoystick(joystick.neutral(), resetTime).isAccepted) {
                slot.joystick = null
                syntheticJoystickNeutralEvents += 1
            } else {
                complete = false
            }
        }
        removeSlotIfInactive(slot)
        return complete
    }

    private fun dispatchKey(
        event: AndroidKeyInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
    ): InputInjectionServiceResult = recordDispatcherResult(
        dispatcher.submitKey(event, eventTimeMs, downTimeMs, config.injectionMode, config.targetUid),
    )

    private fun dispatchTouch(
        event: AndroidTouchInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
    ): InputInjectionServiceResult = recordDispatcherResult(
        dispatcher.submitTouch(event, eventTimeMs, downTimeMs, config.injectionMode, config.targetUid),
    )

    private fun dispatchPointer(
        event: AndroidPointerInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
    ): InputInjectionServiceResult = recordDispatcherResult(
        dispatcher.submitPointer(event, eventTimeMs, downTimeMs, config.injectionMode, config.targetUid),
    )

    private fun dispatchJoystick(
        event: AndroidJoystickInjectionEvent,
        eventTimeMs: Long,
    ): InputInjectionServiceResult = recordDispatcherResult(
        dispatcher.submitJoystick(event, eventTimeMs, config.injectionMode, config.targetUid),
    )

    private fun recordDispatcherResult(result: InputInjectionServiceResult): InputInjectionServiceResult {
        lastError = result.error
        when (result.error) {
            InputInjectionError.InjectEventsPermissionDenied -> permissionFailures += 1
            InputInjectionError.ServiceUnavailable,
            InputInjectionError.ServiceDied,
            InputInjectionError.RemoteFailure,
            -> serviceFailures += 1
            else -> Unit
        }
        return result
    }

    private fun recordTiming(sourceEventTimeUs: Long, localEventTimeMs: Long) {
        lastSourceEventTimeUs = sourceEventTimeUs
        lastLocalInjectionTimeMs = localEventTimeMs
    }

    private fun reject(error: InputInjectionError): InputInjectionServiceResult {
        lastError = error
        return InputInjectionServiceResult.entries.firstOrNull { it.error == error }
            ?: InputInjectionServiceResult.UnknownFailure
    }

    private fun findOrCreateSlot(stateSlot: Int): TrackedSlot? {
        if (!validStateSlot(stateSlot)) return null
        findSlot(stateSlot)?.let { return it }
        val index = slots.indexOfFirst { it == null }
        if (index < 0) return null
        return TrackedSlot(stateSlot).also { slots[index] = it }
    }

    private fun findSlot(stateSlot: Int): TrackedSlot? = slots.firstOrNull { it?.stateSlot == stateSlot }

    private fun removeSlotIfInactive(slot: TrackedSlot) {
        if (slot.pressedKeys.isEmpty() && slot.touch == null && slot.pointer == null && slot.joystick == null) {
            val index = slots.indexOfFirst { it === slot }
            if (index >= 0) slots[index] = null
        }
    }

    private fun validateKey(event: AndroidKeyInjectionEvent): InputInjectionError = when {
        !validCommon(event.stateSlot, event.sourceEventTimeUs, event.androidDeviceId, event.displayId) ->
            InputInjectionError.InvalidEvent
        event.action != AndroidInjectionConstants.KEY_ACTION_DOWN &&
            event.action != AndroidInjectionConstants.KEY_ACTION_UP ->
            InputInjectionError.InvalidEvent
        event.keyCode < 0 || event.repeatCount < 0 || event.metaState < 0 || event.scanCode < 0 ->
            InputInjectionError.InvalidEvent
        event.flags and AndroidInjectionConstants.KEY_FLAG_LONG_PRESS.inv() != 0 -> InputInjectionError.InvalidEvent
        !isKeySource(event.source) -> InputInjectionError.InvalidEvent
        else -> InputInjectionError.None
    }

    private fun validateTouch(event: AndroidTouchInjectionEvent): InputInjectionError {
        if (!validCommon(event.stateSlot, event.sourceEventTimeUs, event.androidDeviceId, event.displayId) ||
            !isPointerSource(event.source) || event.pointers.size !in 1..MAX_TOUCH_POINTERS ||
            event.metaState < 0 || event.buttonState < 0
        ) {
            return InputInjectionError.InvalidEvent
        }
        if (!isTouchAction(event.actionMasked) || event.actionIndex !in event.pointers.indices) {
            return InputInjectionError.InvalidEvent
        }
        when (event.actionMasked) {
            AndroidInjectionConstants.MOTION_ACTION_DOWN,
            AndroidInjectionConstants.MOTION_ACTION_UP,
            -> if (event.pointers.size != 1 || event.actionIndex != 0) {
                return InputInjectionError.InvalidEvent
            }
            AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN,
            AndroidInjectionConstants.MOTION_ACTION_POINTER_UP,
            -> if (event.pointers.size < 2) {
                return InputInjectionError.InvalidEvent
            }
            else -> Unit
        }
        if ((
                event.actionMasked == AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN ||
                    event.actionMasked == AndroidInjectionConstants.MOTION_ACTION_POINTER_UP
                ) &&
            event.actionIndex !in event.pointers.indices
        ) {
            return InputInjectionError.InvalidEvent
        }
        var pointerIdMask = 0
        for (pointer in event.pointers) {
            val pointerBit = if (pointer.pointerId in 0..MAX_ANDROID_POINTER_ID) {
                1 shl pointer.pointerId
            } else {
                0
            }
            if (pointer.pointerId !in 0..MAX_ANDROID_POINTER_ID || pointer.toolType !in VALID_TOOL_TYPES ||
                !pointer.xPx.isFinite() || !pointer.yPx.isFinite() || !pointer.pressure.isFinite() ||
                !pointer.size.isFinite() || pointerBit == 0 || pointerIdMask and pointerBit != 0
            ) {
                return InputInjectionError.InvalidEvent
            }
            pointerIdMask = pointerIdMask or pointerBit
        }
        return InputInjectionError.None
    }

    private fun validatePointer(event: AndroidPointerInjectionEvent): InputInjectionError = when {
        !validCommon(event.stateSlot, event.sourceEventTimeUs, event.androidDeviceId, event.displayId) ||
            !isPointerSource(event.source) || !isPointerAction(event.action) || event.actionButton < 0 ||
            event.metaState < 0 || event.buttonState < 0 -> InputInjectionError.InvalidEvent
        !event.xPx.isFinite() || !event.yPx.isFinite() || !event.relativeXPx.isFinite() ||
            !event.relativeYPx.isFinite() || !event.horizontalScroll.isFinite() ||
            !event.verticalScroll.isFinite() || !event.pressure.isFinite() || !event.size.isFinite() ->
            InputInjectionError.InvalidEvent
        else -> InputInjectionError.None
    }

    private fun validateJoystick(event: AndroidJoystickInjectionEvent): InputInjectionError = when {
        !validCommon(event.stateSlot, event.sourceEventTimeUs, event.androidDeviceId, event.displayId) ||
            !isJoystickSource(event.source) || event.metaState < 0 -> InputInjectionError.InvalidEvent
        !event.leftX.isFinite() || !event.leftY.isFinite() || !event.rightX.isFinite() ||
            !event.rightY.isFinite() || !event.leftTrigger.isFinite() || !event.rightTrigger.isFinite() ||
            !event.hatX.isFinite() || !event.hatY.isFinite() -> InputInjectionError.InvalidEvent
        else -> InputInjectionError.None
    }

    private fun validCommon(stateSlot: Int, sourceEventTimeUs: Long, deviceId: Int, displayId: Int): Boolean =
        validStateSlot(stateSlot) && sourceEventTimeUs >= 0L && deviceId >= 0 && displayId >= 0

    private fun validStateSlot(stateSlot: Int): Boolean = stateSlot in 0..MAX_STATE_SLOT

    private fun isKeySource(source: Int): Boolean =
        (source and AndroidInjectionConstants.SOURCE_KEYBOARD) == AndroidInjectionConstants.SOURCE_KEYBOARD ||
            (source and AndroidInjectionConstants.SOURCE_GAMEPAD) == AndroidInjectionConstants.SOURCE_GAMEPAD

    private fun isPointerSource(source: Int): Boolean =
        (source and AndroidInjectionConstants.SOURCE_CLASS_MASK) == AndroidInjectionConstants.SOURCE_CLASS_POINTER ||
            source == AndroidInjectionConstants.SOURCE_TOUCHPAD ||
            source == AndroidInjectionConstants.SOURCE_MOUSE_RELATIVE

    private fun isJoystickSource(source: Int): Boolean =
        (source and AndroidInjectionConstants.SOURCE_CLASS_MASK) == AndroidInjectionConstants.SOURCE_CLASS_JOYSTICK

    private data class TrackedSlot(
        val stateSlot: Int,
        val pressedKeys: LinkedHashMap<Int, KeyState> = LinkedHashMap(),
        var touch: TouchState? = null,
        var pointer: PointerState? = null,
        var joystick: AndroidJoystickInjectionEvent? = null,
    )

    private data class KeyState(
        val event: AndroidKeyInjectionEvent,
        val downTimeMs: Long,
    )

    private data class TouchState(
        val event: AndroidTouchInjectionEvent,
        val downTimeMs: Long,
    )

    private data class PointerState(
        val event: AndroidPointerInjectionEvent,
        val downTimeMs: Long,
    )

    private companion object {
        const val MAX_STATE_SLOT = 65_535
        const val MAX_ANDROID_POINTER_ID = 31
        val VALID_TOOL_TYPES = setOf(1, 2, 3, 4)
    }

    private fun isTouchAction(action: Int): Boolean = when (action) {
        AndroidInjectionConstants.MOTION_ACTION_DOWN,
        AndroidInjectionConstants.MOTION_ACTION_UP,
        AndroidInjectionConstants.MOTION_ACTION_MOVE,
        AndroidInjectionConstants.MOTION_ACTION_CANCEL,
        AndroidInjectionConstants.MOTION_ACTION_POINTER_DOWN,
        AndroidInjectionConstants.MOTION_ACTION_POINTER_UP,
        -> true
        else -> false
    }

    private fun isPointerAction(action: Int): Boolean = when (action) {
        AndroidInjectionConstants.MOTION_ACTION_DOWN,
        AndroidInjectionConstants.MOTION_ACTION_UP,
        AndroidInjectionConstants.MOTION_ACTION_MOVE,
        AndroidInjectionConstants.MOTION_ACTION_CANCEL,
        AndroidInjectionConstants.MOTION_ACTION_HOVER_MOVE,
        AndroidInjectionConstants.MOTION_ACTION_SCROLL,
        AndroidInjectionConstants.MOTION_ACTION_BUTTON_PRESS,
        AndroidInjectionConstants.MOTION_ACTION_BUTTON_RELEASE,
        -> true
        else -> false
    }
}

internal interface AndroidInputEventDispatcher {
    fun submitKey(
        event: AndroidKeyInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult

    fun submitTouch(
        event: AndroidTouchInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult

    fun submitPointer(
        event: AndroidPointerInjectionEvent,
        eventTimeMs: Long,
        downTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult

    fun submitJoystick(
        event: AndroidJoystickInjectionEvent,
        eventTimeMs: Long,
        mode: InputInjectionMode,
        targetUid: Int,
    ): InputInjectionServiceResult
}

private fun AndroidJoystickInjectionEvent.isNeutral(): Boolean =
    leftX == 0f && leftY == 0f && rightX == 0f && rightY == 0f &&
        leftTrigger == 0f && rightTrigger == 0f && hatX == 0f && hatY == 0f

private fun AndroidJoystickInjectionEvent.neutral(): AndroidJoystickInjectionEvent = copy(
    leftX = 0f,
    leftY = 0f,
    rightX = 0f,
    rightY = 0f,
    leftTrigger = 0f,
    rightTrigger = 0f,
    hatX = 0f,
    hatY = 0f,
)

private fun AndroidTouchInjectionEvent.withLiftedPointerRemoved(): AndroidTouchInjectionEvent {
    val remaining = Array(pointers.size - 1) { index ->
        pointers[if (index < actionIndex) index else index + 1]
    }
    return copy(
        actionMasked = AndroidInjectionConstants.MOTION_ACTION_MOVE,
        actionIndex = 0,
        pointers = remaining,
    )
}
