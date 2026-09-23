package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.input.injection.DebugInputInjectionControllerOverrideRegistry
import io.warpnect.platform.input.injection.InputInjectionControllerSelector
import java.util.Base64
import kotlinx.coroutines.runBlocking

/** Arms the DEBUG-only DirectShell Input Session backend after its shell server is already running. */
class DirectShellInputSessionIntegrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val request = Intent(intent)
        Thread(
            {
                try {
                    log(execute(context.applicationContext, request))
                } finally {
                    pending.finish()
                }
            },
            "WarpnectDirectShellInputSessionControl",
        ).start()
    }

    private fun execute(context: Context, intent: Intent): Result {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: return Result.failure("none", "invalid", "missing_mode")
        val runId = safeRunId(intent.getStringExtra(EXTRA_RUN_ID))
        return when (mode) {
            MODE_ARM -> arm(runId, intent)
            MODE_STATUS -> status(runId)
            MODE_EXERCISE -> exercise(context, runId)
            MODE_DISARM -> disarm(runId)
            else -> Result.failure(runId, mode, "invalid_mode")
        }
    }

    private fun arm(runId: String, intent: Intent): Result {
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val generationId = intent.getLongExtra(EXTRA_GENERATION_ID, -1L)
        val encodedSecret = intent.getStringExtra(EXTRA_SECRET_BASE64)
            ?: return Result.failure(runId, MODE_ARM, "missing_secret")
        val secret = try {
            Base64.getUrlDecoder().decode(encodedSecret)
        } catch (_: IllegalArgumentException) {
            return Result.failure(runId, MODE_ARM, "invalid_secret")
        }
        val candidate = DirectShellInputSessionSource.create(port, generationId, secret)
            ?: return Result.failure(runId, MODE_ARM, "invalid_configuration")
        val probe = candidate.probe()
        if (!probe.available) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "server_unavailable_${probe.error.name}_${probe.detail}")
        }
        val previous = active
        if (!DebugInputInjectionControllerOverrideRegistry.install(candidate)) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "debug_override_disabled")
        }
        active = candidate
        previous?.clear()
        return Result.success(runId, MODE_ARM, "armed ${candidate.describe()} ${probe.detail}")
    }

    private fun status(runId: String): Result {
        val current = active ?: return Result.failure(runId, MODE_STATUS, "not_armed")
        val probe = current.probe()
        return if (probe.available) {
            Result.success(runId, MODE_STATUS, "armed ${current.describe()} ${probe.detail}")
        } else {
            Result.failure(runId, MODE_STATUS, "server_unavailable_${probe.error.name}_${probe.detail}")
        }
    }

    /**
     * Drives the exact normal-process controller composition through the armed DirectShell source.
     * The external DEBUG harness owns foregrounding the target Activity before this runs.
     */
    private fun exercise(context: Context, runId: String): Result {
        if (active == null) return Result.failure(runId, MODE_EXERCISE, "not_armed")
        val controller = InputInjectionControllerSelector.create(context)
        return try {
            val capabilities = runBlocking { controller.queryCapabilities() }
            if (!capabilities.isQualifiedDirectShell()) {
                return Result.failure(
                    runId,
                    MODE_EXERCISE,
                    "capability_failed backend=${capabilities.backend} error=${capabilities.lastError}",
                )
            }

            requireSuccess("prepare", runBlocking { controller.prepare(InputInjectionConfig()) })
            requireSuccess("start", controller.start())
            requireSuccess(
                "key_down",
                controller.injectKey(keyEvent(AndroidInjectionConstants.KEY_ACTION_DOWN)),
            )
            requireSuccess(
                "key_up",
                controller.injectKey(keyEvent(AndroidInjectionConstants.KEY_ACTION_UP)),
            )
            requireSuccess(
                "touch_down",
                controller.injectTouch(
                    touchEvent(
                        AndroidInjectionConstants.MOTION_ACTION_DOWN,
                        TOUCH_START_X,
                        TOUCH_START_Y,
                    ),
                ),
            )
            requireSuccess(
                "touch_move",
                controller.injectTouch(
                    touchEvent(
                        AndroidInjectionConstants.MOTION_ACTION_MOVE,
                        TOUCH_END_X,
                        TOUCH_END_Y,
                    ),
                ),
            )
            requireSuccess(
                "touch_up",
                controller.injectTouch(
                    touchEvent(
                        AndroidInjectionConstants.MOTION_ACTION_UP,
                        TOUCH_END_X,
                        TOUCH_END_Y,
                    ),
                ),
            )
            requireSuccess("pointer", controller.injectPointer(pointerEvent()))
            requireSuccess("joystick", controller.injectJoystick(joystickEvent()))
            requireSuccess(
                "reset",
                controller.resetState(InputResetScope.AllSlots, 0, InputResetReason.SessionStop),
            )
            val snapshot = controller.snapshot()
            if (!snapshot.isReleased()) {
                return Result.failure(runId, MODE_EXERCISE, "reset_incomplete ${snapshot.summary()}")
            }
            Result.success(
                runId,
                MODE_EXERCISE,
                "backend=DirectShell shizuku_input_producer=false key_submitted=${snapshot.keySubmitted} " +
                    "touch_submitted=${snapshot.touchSubmitted} pointer_submitted=${snapshot.pointerSubmitted} " +
                    "joystick_submitted=${snapshot.joystickSubmitted} reset_complete=${snapshot.resetComplete}",
            )
        } catch (failure: ExerciseFailure) {
            Result.failure(runId, MODE_EXERCISE, failure.message ?: "operation_failed")
        } finally {
            controller.close()
        }
    }

    private fun disarm(runId: String): Result {
        val current = active
        DebugInputInjectionControllerOverrideRegistry.clear(current)
        active = null
        val shutdown = current?.shutdown() ?: "not_armed"
        current?.clear()
        return Result.success(runId, MODE_DISARM, "disarmed $shutdown")
    }

    private fun InputInjectionCapabilities.isQualifiedDirectShell(): Boolean = serviceAvailable &&
        backend == InputInjectionBackend.DirectShell &&
        inputManagerApiResolved &&
        displayTargetingSupported &&
        keyInjectionSupported &&
        touchInjectionSupported &&
        pointerInjectionSupported &&
        joystickInjectionSupported &&
        lastError == InputInjectionError.None

    private fun requireSuccess(operation: String, result: InputInjectionResult) {
        if (!result.isSuccess) throw ExerciseFailure("$operation failed error=${result.error}")
    }

    private fun InputInjectionSnapshot.isReleased(): Boolean = activePressedKeys == 0 &&
        activeTouchStreams == 0 &&
        activePointerButtonStates == 0 &&
        activeJoystickStates == 0 &&
        !stateMayRemainInjected &&
        lastError == InputInjectionError.None

    private fun InputInjectionSnapshot.summary(): String =
        "keys=$activePressedKeys touch=$activeTouchStreams pointer=$activePointerButtonStates " +
            "joystick=$activeJoystickStates retained=$stateMayRemainInjected error=$lastError"

    private fun keyEvent(action: Int): AndroidKeyInjectionEvent = AndroidKeyInjectionEvent(
        stateSlot = KEY_SLOT,
        sourceEventTimeUs = nextEventTimeUs(),
        action = action,
        keyCode = KeyEvent.KEYCODE_A,
        scanCode = DIRECTSHELL_SCAN_CODE,
        source = InputDevice.SOURCE_KEYBOARD,
        displayId = DISPLAY_ID,
    )

    private fun touchEvent(action: Int, x: Float, y: Float): AndroidTouchInjectionEvent = AndroidTouchInjectionEvent(
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
        displayId = DISPLAY_ID,
    )

    private fun pointerEvent(): AndroidPointerInjectionEvent = AndroidPointerInjectionEvent(
        stateSlot = POINTER_SLOT,
        sourceEventTimeUs = nextEventTimeUs(),
        action = AndroidInjectionConstants.MOTION_ACTION_HOVER_MOVE,
        xPx = POINTER_X,
        yPx = POINTER_Y,
        source = InputDevice.SOURCE_MOUSE,
        displayId = DISPLAY_ID,
    )

    private fun joystickEvent(): AndroidJoystickInjectionEvent = AndroidJoystickInjectionEvent(
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
        displayId = DISPLAY_ID,
    )

    private fun nextEventTimeUs(): Long = SystemClock.elapsedRealtimeNanos() / MICROSECONDS_PER_NANOSECOND

    private fun log(result: Result) {
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_INPUT_SESSION_DIAGNOSTICS command=${result.command} " +
                safe(result.detail),
        )
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_INPUT_SESSION_RESULT command=${result.command} " +
                "result=${if (result.success) "OK" else "FAIL"}",
        )
    }

    private fun safeRunId(value: String?): String =
        value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) } ?: "none"

    private fun safe(value: String): String = value
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(MAX_LOG_CHARS)

    private data class Result(
        val runId: String,
        val command: String,
        val success: Boolean,
        val detail: String,
    ) {
        companion object {
            fun success(runId: String, command: String, detail: String) = Result(runId, command, true, detail)

            fun failure(runId: String, command: String, detail: String) = Result(runId, command, false, detail)
        }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellInput"
        const val MAX_LOG_CHARS = 1_200

        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET_BASE64 = "secretBase64"
        const val EXTRA_GENERATION_ID = "generationId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUN_ID = "runId"

        const val MODE_ARM = "arm"
        const val MODE_STATUS = "status"
        const val MODE_EXERCISE = "exercise"
        const val MODE_DISARM = "disarm"

        const val KEY_SLOT = 41
        const val TOUCH_SLOT = 42
        const val POINTER_SLOT = 43
        const val JOYSTICK_SLOT = 44
        const val DIRECTSHELL_SCAN_CODE = 22_222
        const val DISPLAY_ID = 0
        const val MICROSECONDS_PER_NANOSECOND = 1_000L
        const val TOUCH_START_X = 160f
        const val TOUCH_START_Y = 220f
        const val TOUCH_END_X = 420f
        const val TOUCH_END_Y = 520f
        const val POINTER_X = 300f
        const val POINTER_Y = 360f

        @Volatile
        var active: DirectShellInputSessionSource? = null
    }

    private class ExerciseFailure(message: String) : IllegalStateException(message)
}
