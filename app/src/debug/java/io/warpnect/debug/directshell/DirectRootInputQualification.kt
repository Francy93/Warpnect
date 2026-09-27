package io.warpnect.debug.directshell

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import io.warpnect.debug.input.InputSessionTargetActivity
import io.warpnect.debug.input.InputSessionTargetProbeState
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.input.injection.DirectRootInputCapability
import io.warpnect.platform.input.injection.DirectRootInputInjectionGateway
import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.readiness.AndroidPersistentCapabilityEvidenceKeyFactory
import io.warpnect.platform.privileged.readiness.PersistentCapabilityEvidenceRepository
import io.warpnect.platform.privileged.readiness.SharedPreferencesPersistentCapabilityEvidenceStore
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import kotlinx.coroutines.runBlocking

/** Explicit DUMP-gated lab action. No qualification injection runs during background readiness. */
internal object DirectRootInputQualification {
    fun hold(context: Context, gateway: DirectRootInputInjectionGateway): Long = runBlocking {
        val observation = InputSessionTargetProbeState.begin()
        context.startActivity(
            Intent(context, InputSessionTargetActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        val target = InputSessionTargetProbeState.awaitTouchTarget(observation, 3_000) ?: error("TARGET_NOT_READY")
        check(
            gateway.prepare(InputInjectionConfig(injectionMode = InputInjectionMode.WaitForResultDiagnostics)) ==
                InputInjectionError.None,
        )
        check(gateway.start() == InputInjectionError.None)
        check(
            InputInjectionServiceResult.fromCode(
                gateway.injectKey(
                    AndroidKeyInjectionEvent(
                        0,
                        SystemClock.uptimeMillis() * 1000,
                        KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_A,
                        source = InputDevice.SOURCE_KEYBOARD,
                        displayId = 0,
                    ),
                ),
            ).isAccepted,
        )
        check(
            InputInjectionServiceResult.fromCode(
                gateway.injectTouch(
                    AndroidTouchInjectionEvent(
                        1,
                        SystemClock.uptimeMillis() * 1000,
                        MotionEvent.ACTION_DOWN,
                        0,
                        arrayOf(
                            AndroidTouchPointer(
                                0,
                                MotionEvent.TOOL_TYPE_FINGER,
                                target.x.toFloat(),
                                target.y.toFloat(),
                            ),
                        ),
                        source = InputDevice.SOURCE_TOUCHSCREEN,
                        displayId = 0,
                    ),
                ),
            ).isAccepted,
        )
        check(InputSessionTargetProbeState.awaitOrdered(observation, listOf("KEY_DOWN", "TOUCH_DOWN"), 3_000))
        Log.i("WarpnectDirectRoot", "direct_root_input_held result=PASS")
        observation
    }

    fun observeDisconnectRelease(observation: Long) {
        check(
            InputSessionTargetProbeState.awaitReset(
                observation,
                listOf("KEY_DOWN", "TOUCH_DOWN"),
                setOf("KEY_UP", "TOUCH_CANCEL"),
                3_000,
            ),
        ) { "DISCONNECT_RESET_NOT_OBSERVED" }
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_input_disconnect_reset result=PASS target_key_up=true target_touch_cancel=true",
        )
    }

    fun run(context: Context, runtime: DirectRootRuntime) = runBlocking {
        val gateway = DirectRootInputInjectionGateway(runtime)
        val observation = InputSessionTargetProbeState.begin()
        context.startActivity(
            Intent(context, InputSessionTargetActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        try {
            val target = InputSessionTargetProbeState.awaitTouchTarget(observation, 3_000)
                ?: error("TARGET_NOT_READY")
            val capabilities = gateway.queryCapabilities()
            Log.i("WarpnectDirectRoot", "direct_root_input_api capabilities=$capabilities")
            Log.i("WarpnectDirectRoot", "direct_root_input_backend_snapshot ${gateway.snapshot()}")
            check(capabilities.inputManagerApiResolved) { "API_UNAVAILABLE" }
            check(
                gateway.prepare(InputInjectionConfig(injectionMode = InputInjectionMode.WaitForResultDiagnostics)) ==
                    InputInjectionError.None,
            ) { "PREPARE_FAILED" }
            check(gateway.start() == InputInjectionError.None) { "START_FAILED" }
            fun accepted(code: Int) = check(InputInjectionServiceResult.fromCode(code).isAccepted) {
                "SUBMISSION_${InputInjectionServiceResult.fromCode(code)}"
            }
            fun key(action: Int) = AndroidKeyInjectionEvent(
                0,
                SystemClock.uptimeMillis() * 1000,
                action,
                KeyEvent.KEYCODE_A,
                source = InputDevice.SOURCE_KEYBOARD,
                displayId = 0,
            )
            fun touch(action: Int) = AndroidTouchInjectionEvent(
                1,
                SystemClock.uptimeMillis() * 1000,
                action,
                0,
                arrayOf(AndroidTouchPointer(0, MotionEvent.TOOL_TYPE_FINGER, target.x.toFloat(), target.y.toFloat())),
                source = InputDevice.SOURCE_TOUCHSCREEN,
                displayId = 0,
            )
            accepted(gateway.injectKey(key(KeyEvent.ACTION_DOWN)))
            accepted(gateway.injectKey(key(KeyEvent.ACTION_UP)))
            accepted(gateway.injectTouch(touch(MotionEvent.ACTION_DOWN)))
            accepted(gateway.injectTouch(touch(MotionEvent.ACTION_MOVE)))
            accepted(gateway.injectTouch(touch(MotionEvent.ACTION_UP)))
            accepted(
                gateway.injectPointer(
                    AndroidPointerInjectionEvent(
                        2,
                        SystemClock.uptimeMillis() * 1000,
                        MotionEvent.ACTION_HOVER_MOVE,
                        xPx = target.x.toFloat(),
                        yPx = target.y.toFloat(),
                        source = InputDevice.SOURCE_MOUSE,
                        displayId = 0,
                    ),
                ),
            )
            accepted(
                gateway.injectJoystick(
                    AndroidJoystickInjectionEvent(
                        3, SystemClock.uptimeMillis() * 1000,
                        .25f, -.25f, 0f, 0f, 0f, 0f, 0f, 0f, source = InputDevice.SOURCE_JOYSTICK, displayId = 0,
                    ),
                ),
            )
            // Android may synthesize HOVER_ENTER before HOVER_MOVE. Key/touch/joystick order stays strict.
            val ordered = listOf("KEY_DOWN", "KEY_UP", "TOUCH_DOWN", "TOUCH_MOVE", "TOUCH_UP", "JOYSTICK")
            check(InputSessionTargetProbeState.awaitEvents(observation, setOf("POINTER"), 3_000).isEmpty()) {
                "POINTER_TARGET_NOT_OBSERVED"
            }
            check(InputSessionTargetProbeState.awaitOrdered(observation, ordered, 3_000, setOf("POINTER"))) {
                "TARGET_NOT_OBSERVED_OR_ORDER"
            }
            accepted(gateway.injectKey(key(KeyEvent.ACTION_DOWN)))
            accepted(gateway.injectTouch(touch(MotionEvent.ACTION_DOWN)))
            check(
                gateway.reset(InputResetScope.AllSlots, 0, InputResetReason.SessionStop) ==
                    InputInjectionServiceResult.ResetComplete.code,
            ) { "RESET_FAILED" }
            check(
                InputSessionTargetProbeState.awaitReset(
                    observation,
                    ordered + listOf("KEY_DOWN", "TOUCH_DOWN"),
                    setOf("JOYSTICK", "KEY_UP", "TOUCH_CANCEL"),
                    3_000,
                    setOf("POINTER"),
                ),
            ) {
                "RESET_TARGET_NOT_OBSERVED"
            }
            val snapshot = gateway.snapshot()
            check(
                snapshot.activePressedKeys == 0 && snapshot.activeTouchStreams == 0 &&
                    snapshot.activePointerButtonStates == 0 && snapshot.activeJoystickStates == 0,
            ) { "INPUT_STUCK" }
            val qualification = DirectRootInputCapability.record(
                runtime.snapshot,
                true,
                "root_input_owned_target_observed",
            )
            persist(context, qualification)
            Log.i(
                "WarpnectDirectRoot",
                "direct_root_input_qualification result=PASS generation=${runtime.snapshot.generation} " +
                    "key=PASS touch=PASS pointer=PASS joystick=PASS ordered=PASS reset=PASS active=0 " +
                    "snapshot=$snapshot",
            )
        } catch (error: Exception) {
            if (runtime.snapshot.ready) {
                DirectRootInputCapability.record(
                    runtime.snapshot,
                    false,
                    error.message.orEmpty().take(150).ifBlank { "input_failure" },
                )
            }
            Log.i("WarpnectDirectRoot", "direct_root_input_qualification result=FAIL boundary=${error.message}")
        } finally {
            gateway.close()
        }
    }

    private fun persist(context: Context, result: CapabilityQualification) {
        val repository = PersistentCapabilityEvidenceRepository(
            SharedPreferencesPersistentCapabilityEvidenceStore(context),
            AndroidPersistentCapabilityEvidenceKeyFactory(shizukuRuntimeVersion = {
                null
            }),
            recordedAtEpochMs = System::currentTimeMillis,
        )
        val matrix = PrivilegedCapabilityMatrix.complete { provider, capability ->
            if (provider == result.provider && capability == result.capability) {
                result
            } else {
                CapabilityQualification.unavailable(
                    provider,
                    capability,
                    CapabilityQualificationState.DEFERRED,
                    "not_probed",
                    CapabilityQualificationProvenance(
                        CapabilityQualificationSource.PROVIDER_ADAPTER,
                    ),
                )
            }
        }
        repository.reconcileAuthoritativeMatrix(matrix)
        Log.i(
            "WarpnectDirectRoot",
            "direct_root_input_cache reusable=${repository.loadReusableEvidence().reusableRecords.size}",
        )
    }
}
