package io.warpnect.debug.directshell

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.input.injection.AndroidInjectedEventFactory
import io.warpnect.platform.input.injection.InputInjectionStateTracker
import io.warpnect.platform.input.injection.ReflectivePrivilegedInputManagerApi

/**
 * DEBUG-only DirectShell adapter over Warpnect's production resolver, event factory, and state
 * tracker. It deliberately owns no Shizuku/Binder dependency and has no Session integration.
 */
internal class DirectShellInputProbeEngine(
    private val displayId: Int,
) {
    private val inputManager = ReflectivePrivilegedInputManagerApi()
    private val dispatcher = AndroidInjectedEventFactory(inputManager)
    private var tracker: InputInjectionStateTracker? = null
    private var prepared = false
    private var keySequences = 0L
    private var touchGestures = 0L
    private var pointerEvents = 0L
    private var joystickEvents = 0L
    private var resetRequests = 0L

    fun prepare(): Result {
        val capabilities = inputManager.resolve()
        if (!capabilities.apiResolved) {
            return Result.failure(
                "INPUT_API_UNAVAILABLE_${capabilities.lastError.name}",
                diagnosticsPayload("prepare_failed"),
            )
        }
        if (!capabilities.displayTargetingSupported || !capabilities.waitForResultSupported) {
            return Result.failure("INPUT_API_REQUIRED_CAPABILITY_UNAVAILABLE", diagnosticsPayload("prepare_failed"))
        }
        tracker = InputInjectionStateTracker(
            InputInjectionConfig(
                injectionMode = InputInjectionMode.WaitForResultDiagnostics,
                resetAllOnStop = true,
            ),
            SystemClock::uptimeMillis,
            dispatcher,
        )
        prepared = true
        return Result.success("INPUT_PREPARED", diagnosticsPayload("prepared"))
    }

    fun injectKeySequence(count: Int): Result = withTracker("KEY_SEQUENCE") { active ->
        repeat(count) {
            val down = active.injectKey(keyEvent(AndroidInjectionConstants.KEY_ACTION_DOWN, KeyEvent.KEYCODE_A))
            if (!down.isAccepted) return@withTracker failureFor("KEY_DOWN", down)
            val up = active.injectKey(keyEvent(AndroidInjectionConstants.KEY_ACTION_UP, KeyEvent.KEYCODE_A))
            if (!up.isAccepted) return@withTracker failureFor("KEY_UP", up)
            keySequences += 1
        }
        Result.success("KEY_SEQUENCE_OK", diagnosticsPayload("key_sequence count=$count"))
    }

    fun injectTouchGesture(gesture: DirectShellInputProbeRequest.TouchGesture): Result =
        withTracker("TOUCH_GESTURE") { active ->
            val startX = gesture.targetX?.toFloat() ?: TOUCH_START_X
            val startY = gesture.targetY?.toFloat() ?: TOUCH_START_Y
            val endX = gesture.targetX?.plus(TOUCH_TARGET_DELTA_PX)?.toFloat() ?: TOUCH_END_X
            val endY = gesture.targetY?.plus(TOUCH_TARGET_DELTA_PX)?.toFloat() ?: TOUCH_END_Y
            repeat(gesture.count) {
                val down = active.injectTouch(touchEvent(MotionEvent.ACTION_DOWN, startX, startY))
                if (!down.isAccepted) return@withTracker failureFor("TOUCH_DOWN", down)
                val move = active.injectTouch(touchEvent(MotionEvent.ACTION_MOVE, endX, endY))
                if (!move.isAccepted) return@withTracker failureFor("TOUCH_MOVE", move)
                val up = active.injectTouch(touchEvent(MotionEvent.ACTION_UP, endX, endY))
                if (!up.isAccepted) return@withTracker failureFor("TOUCH_UP", up)
                touchGestures += 1
            }
            Result.success("TOUCH_GESTURE_OK", diagnosticsPayload("touch_gesture count=${gesture.count}"))
        }

    fun injectPointer(): Result = withTracker("POINTER") { active ->
        val result = active.injectPointer(
            AndroidPointerInjectionEvent(
                stateSlot = POINTER_SLOT,
                sourceEventTimeUs = nextEventTimeUs(),
                action = MotionEvent.ACTION_HOVER_MOVE,
                xPx = POINTER_X,
                yPx = POINTER_Y,
                source = InputDevice.SOURCE_MOUSE,
                displayId = displayId,
            ),
        )
        if (!result.isAccepted) return@withTracker failureFor("POINTER", result)
        pointerEvents += 1
        Result.success("POINTER_OK", diagnosticsPayload("pointer"))
    }

    fun injectJoystick(): Result = withTracker("JOYSTICK") { active ->
        val result = active.injectJoystick(
            AndroidJoystickInjectionEvent(
                stateSlot = JOYSTICK_SLOT,
                sourceEventTimeUs = nextEventTimeUs(),
                leftX = 0.5f,
                leftY = -0.5f,
                rightX = 0f,
                rightY = 0f,
                leftTrigger = 0f,
                rightTrigger = 0f,
                hatX = 0f,
                hatY = 0f,
                source = InputDevice.SOURCE_JOYSTICK,
                displayId = displayId,
            ),
        )
        if (!result.isAccepted) return@withTracker failureFor("JOYSTICK", result)
        joystickEvents += 1
        Result.success("JOYSTICK_OK", diagnosticsPayload("joystick"))
    }

    /** Leaves a tracked key down briefly, then exercises the production reset/release path. */
    fun resetAfterPressedKey(): Result = withTracker("RESET") { active ->
        val down = active.injectKey(keyEvent(AndroidInjectionConstants.KEY_ACTION_DOWN, KeyEvent.KEYCODE_B))
        if (!down.isAccepted) return@withTracker failureFor("RESET_KEY_DOWN", down)
        val reset = active.reset(
            InputResetScope.AllSlots,
            0,
            InputResetReason.ErrorRecovery.ordinal + 1,
        )
        if (reset.error != io.warpnect.input.injection.InputInjectionError.None) {
            return@withTracker failureFor("RESET", reset)
        }
        resetRequests += 1
        Result.success("RESET_OK", diagnosticsPayload("reset"))
    }

    fun diagnostics(): Result = Result.success("INPUT_DIAGNOSTICS", diagnosticsPayload("diagnostics"))

    fun shutdown(): Result {
        val active = tracker
        if (active != null) {
            active.reset(InputResetScope.AllSlots, 0, InputResetReason.SessionStop.ordinal + 1)
        }
        tracker = null
        prepared = false
        return Result.success("INPUT_STOPPED", diagnosticsPayload("stopped"))
    }

    private inline fun withTracker(operation: String, block: (InputInjectionStateTracker) -> Result): Result {
        val active = tracker ?: return Result.failure("INPUT_NOT_PREPARED", diagnosticsPayload(operation))
        return block(active)
    }

    private fun failureFor(stage: String, result: InputInjectionServiceResult): Result =
        Result.failure("${stage}_${result.name}", diagnosticsPayload("failure_$stage"))

    private fun keyEvent(action: Int, keyCode: Int) = AndroidKeyInjectionEvent(
        stateSlot = KEY_SLOT,
        sourceEventTimeUs = nextEventTimeUs(),
        action = action,
        keyCode = keyCode,
        scanCode = DIRECTSHELL_SCAN_CODE,
        source = InputDevice.SOURCE_KEYBOARD,
        displayId = displayId,
    )

    private fun touchEvent(action: Int, x: Float, y: Float) = AndroidTouchInjectionEvent(
        stateSlot = TOUCH_SLOT,
        sourceEventTimeUs = nextEventTimeUs(),
        actionMasked = action,
        actionIndex = 0,
        pointers = arrayOf(
            AndroidTouchPointer(
                pointerId = 0,
                toolType = MotionEvent.TOOL_TYPE_FINGER,
                xPx = x,
                yPx = y,
            ),
        ),
        source = InputDevice.SOURCE_TOUCHSCREEN,
        displayId = displayId,
    )

    private fun nextEventTimeUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L

    private fun diagnosticsPayload(event: String): String {
        val capabilities = inputManager.resolve()
        val snapshot = tracker?.snapshot(
            state = if (prepared) InputInjectionState.Running else InputInjectionState.Stopped,
            apiResolved = capabilities.apiResolved,
            targetUidSupported = capabilities.targetUidInjectionSupported,
            displayTargetingSupported = capabilities.displayTargetingSupported,
        )
        val resolution = inputManager.resolutionDiagnostics()
        val backend = resolution.selectedBackend?.name ?: "NONE"
        return listOf(
            "event=$event",
            "backend=$backend",
            "modern_failure=${resolution.modernFailure.name}",
            "legacy_failure=${resolution.legacyFailure.name}",
            "api_resolved=${capabilities.apiResolved}",
            "async_supported=${capabilities.asyncInjectionSupported}",
            "wait_for_result_supported=${capabilities.waitForResultSupported}",
            "display_targeting_supported=${capabilities.displayTargetingSupported}",
            "display_id=$displayId",
            "key_sequences=$keySequences",
            "touch_gestures=$touchGestures",
            "pointer_events=$pointerEvents",
            "joystick_events=$joystickEvents",
            "reset_requests=$resetRequests",
            "key_submitted=${snapshot?.keySubmitted ?: 0}",
            "touch_submitted=${snapshot?.touchSubmitted ?: 0}",
            "pointer_submitted=${snapshot?.pointerSubmitted ?: 0}",
            "synthetic_key_ups=${snapshot?.syntheticKeyUps ?: 0}",
            "synthetic_joystick_neutral_events=${snapshot?.syntheticJoystickNeutralEvents ?: 0}",
            "active_pressed_keys=${snapshot?.activePressedKeys ?: 0}",
            "active_touch_streams=${snapshot?.activeTouchStreams ?: 0}",
            "last_error=${snapshot?.lastError?.name ?: capabilities.lastError.name}",
        ).joinToString(" ")
    }

    data class Result(
        val success: Boolean,
        val classification: String,
        val payload: String,
    ) {
        companion object {
            fun success(classification: String, payload: String) = Result(true, classification, payload)

            fun failure(classification: String, payload: String) = Result(false, classification, payload)
        }
    }

    private companion object {
        const val KEY_SLOT = 11
        const val TOUCH_SLOT = 12
        const val POINTER_SLOT = 13
        const val JOYSTICK_SLOT = 14
        const val DIRECTSHELL_SCAN_CODE = 22_222
        const val TOUCH_START_X = 160f
        const val TOUCH_START_Y = 220f
        const val TOUCH_END_X = 420f
        const val TOUCH_END_Y = 520f
        const val TOUCH_TARGET_DELTA_PX = 1
        const val POINTER_X = 300f
        const val POINTER_Y = 360f
    }
}
