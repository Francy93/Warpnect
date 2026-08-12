package io.warpnect.platform.input.injection.privileged

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.warpnect.input.injection.ANDROID_INVALID_UID
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionBackend
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.injection.MAX_TOUCH_POINTERS
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.platform.input.injection.AndroidInjectedEventFactory
import io.warpnect.platform.input.injection.InputInjectionStateTracker
import io.warpnect.platform.input.injection.ReflectivePrivilegedInputManagerApi

/** Runs in the Shizuku/Sui UserService process. Binder calls are synchronous by design. */
class PrivilegedInputInjectionUserService : IPrivilegedInputInjectionService.Stub() {
    private val lock = Any()
    private val inputManager = ReflectivePrivilegedInputManagerApi()
    private val dispatcher = AndroidInjectedEventFactory(inputManager)
    private var config: InputInjectionConfig? = null
    private var tracker: InputInjectionStateTracker? = null
    private var state = InputInjectionState.Stopped
    private var lastError = InputInjectionError.None

    override fun getServiceVersion(): Int = PrivilegedInputInjectionContract.SERVICE_VERSION

    override fun getCapabilities(): Bundle = synchronized(lock) {
        capabilitiesLocked().toBundle()
    }

    override fun prepare(targetUid: Int, injectionMode: Int, maxTrackedSlots: Int, maxPressedKeys: Int): Int =
        synchronized(lock) {
            if (state == InputInjectionState.Closed) return@synchronized InputInjectionError.Closed.code
            val mode = PrivilegedInputInjectionContract.modeFromWire(injectionMode)
                ?: return@synchronized fail(InputInjectionError.InvalidConfiguration)
            val requested = InputInjectionConfig(
                targetUid = targetUid,
                injectionMode = mode,
                maxTrackedInjectionSlots = maxTrackedSlots,
                maxPressedKeysPerSlot = maxPressedKeys,
            )
            requested.validate().takeIf { it != InputInjectionError.None }?.let {
                return@synchronized fail(it)
            }
            val capabilities = inputManager.resolve()
            if (!capabilities.apiResolved) return@synchronized fail(capabilities.lastError)
            if (targetUid != ANDROID_INVALID_UID && !capabilities.targetUidInjectionSupported) {
                return@synchronized fail(InputInjectionError.TargetUidUnsupported)
            }
            if (!capabilities.displayTargetingSupported) {
                return@synchronized fail(InputInjectionError.DisplayTargetingUnsupported)
            }
            if (mode == io.warpnect.input.injection.InputInjectionMode.AsyncLowLatency &&
                !capabilities.asyncInjectionSupported
            ) {
                return@synchronized fail(InputInjectionError.InputApiUnavailable)
            }
            if (mode == io.warpnect.input.injection.InputInjectionMode.WaitForResultDiagnostics &&
                !capabilities.waitForResultSupported
            ) {
                return@synchronized fail(InputInjectionError.InputApiUnavailable)
            }
            config = requested
            tracker = InputInjectionStateTracker(requested, SystemClock::uptimeMillis, dispatcher)
            state = InputInjectionState.Prepared
            lastError = InputInjectionError.None
            InputInjectionServiceResult.Prepared.code
        }

    override fun startInjection(): Int = synchronized(lock) {
        when (state) {
            InputInjectionState.Prepared -> {
                state = InputInjectionState.Running
                InputInjectionServiceResult.SubmittedAsync.code
            }
            InputInjectionState.Running -> InputInjectionError.AlreadyRunning.code
            InputInjectionState.Closed -> InputInjectionError.Closed.code
            else -> InputInjectionError.NotPrepared.code
        }
    }

    override fun stopInjection(resetAll: Boolean): Int = synchronized(lock) {
        if (state == InputInjectionState.Closed) return@synchronized InputInjectionError.Closed.code
        val activeTracker = tracker
        if (activeTracker == null) {
            state = InputInjectionState.Stopped
            return@synchronized InputInjectionServiceResult.ResetComplete.code
        }
        state = InputInjectionState.Stopping
        val result = if (resetAll) {
            activeTracker.reset(InputResetScope.AllSlots, 0, RESET_REASON_SESSION_STOP)
        } else {
            InputInjectionServiceResult.ResetComplete
        }
        state = InputInjectionState.Stopped
        lastError = result.error
        result.code
    }

    override fun injectKey(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        action: Int,
        keyCode: Int,
        repeatCount: Int,
        metaState: Int,
        scanCode: Int,
        flags: Int,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
    ): Int = injectLocked {
        injectKey(
            AndroidKeyInjectionEvent(
                stateSlot, sourceEventTimeUs, action, keyCode, repeatCount, metaState, scanCode, flags,
                source, androidDeviceId, displayId,
            ),
        )
    }

    override fun injectTouch(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        actionMasked: Int,
        actionIndex: Int,
        pointerCount: Int,
        pointerIds: IntArray?,
        toolTypes: IntArray?,
        xPx: FloatArray?,
        yPx: FloatArray?,
        pressure: FloatArray?,
        size: FloatArray?,
        metaState: Int,
        buttonState: Int,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
    ): Int = injectLocked {
        if (pointerCount !in 1..MAX_TOUCH_POINTERS ||
            pointerIds?.size != pointerCount || toolTypes?.size != pointerCount ||
            xPx?.size != pointerCount || yPx?.size != pointerCount ||
            pressure?.size != pointerCount || size?.size != pointerCount
        ) {
            return@injectLocked InputInjectionServiceResult.InvalidEvent
        }
        val pointers = Array(pointerCount) { index ->
            AndroidTouchPointer(
                pointerId = pointerIds[index],
                toolType = toolTypes[index],
                xPx = xPx[index],
                yPx = yPx[index],
                pressure = pressure[index],
                size = size[index],
            )
        }
        injectTouch(
            AndroidTouchInjectionEvent(
                stateSlot, sourceEventTimeUs, actionMasked, actionIndex, pointers, metaState, buttonState,
                source, androidDeviceId, displayId,
            ),
        )
    }

    override fun injectPointer(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        action: Int,
        actionButton: Int,
        xPx: Float,
        yPx: Float,
        relativeXPx: Float,
        relativeYPx: Float,
        horizontalScroll: Float,
        verticalScroll: Float,
        pressure: Float,
        size: Float,
        metaState: Int,
        buttonState: Int,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
    ): Int = injectLocked {
        injectPointer(
            AndroidPointerInjectionEvent(
                stateSlot, sourceEventTimeUs, action, actionButton, xPx, yPx, relativeXPx, relativeYPx,
                horizontalScroll, verticalScroll, pressure, size, metaState, buttonState, source,
                androidDeviceId, displayId,
            ),
        )
    }

    override fun injectJoystick(
        sourceEventTimeUs: Long,
        stateSlot: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
        leftTrigger: Float,
        rightTrigger: Float,
        hatX: Float,
        hatY: Float,
        metaState: Int,
        source: Int,
        androidDeviceId: Int,
        displayId: Int,
    ): Int = injectLocked {
        injectJoystick(
            AndroidJoystickInjectionEvent(
                stateSlot, sourceEventTimeUs, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger,
                hatX, hatY, metaState, source, androidDeviceId, displayId,
            ),
        )
    }

    override fun resetState(scope: Int, stateSlot: Int, reason: Int): Int = synchronized(lock) {
        if (state != InputInjectionState.Running) return@synchronized InputInjectionError.NotRunning.code
        val parsedScope = PrivilegedInputInjectionContract.resetScopeFromWire(scope)
            ?: return@synchronized fail(InputInjectionError.InvalidConfiguration)
        if (reason !in 1..MAX_RESET_REASON) return@synchronized fail(InputInjectionError.InvalidConfiguration)
        val result = tracker?.reset(parsedScope, stateSlot, reason)
            ?: InputInjectionServiceResult.NotPrepared
        lastError = result.error
        result.code
    }

    override fun getSnapshot(): Bundle = synchronized(lock) { snapshotLocked().toBundle() }

    @Suppress("unused")
    fun destroy() {
        synchronized(lock) {
            tracker?.reset(InputResetScope.AllSlots, 0, RESET_REASON_SESSION_STOP)
            state = InputInjectionState.Closed
            tracker = null
            config = null
        }
    }

    private inline fun injectLocked(block: InputInjectionStateTracker.() -> InputInjectionServiceResult): Int =
        synchronized(lock) {
            if (state != InputInjectionState.Running) return@synchronized InputInjectionError.NotRunning.code
            val activeTracker = tracker ?: return@synchronized InputInjectionError.NotPrepared.code
            val result = try {
                activeTracker.block()
            } catch (_: Throwable) {
                InputInjectionServiceResult.UnknownFailure
            }
            lastError = result.error
            result.code
        }

    private fun capabilitiesLocked(): InputInjectionCapabilities {
        val api = inputManager.resolve()
        val usable = api.apiResolved && api.displayTargetingSupported
        return InputInjectionCapabilities(
            serviceAvailable = api.apiResolved,
            backend = if (api.apiResolved) InputInjectionBackend.ShizukuUserService else InputInjectionBackend.None,
            privilegedUid = Process.myUid(),
            privilegedUidKind = Process.myUid().toUidKind(),
            inputManagerApiResolved = api.apiResolved,
            asyncInjectionSupported = api.asyncInjectionSupported,
            waitForResultSupported = api.waitForResultSupported,
            targetUidInjectionSupported = api.targetUidInjectionSupported,
            displayTargetingSupported = api.displayTargetingSupported,
            keyInjectionSupported = usable,
            touchInjectionSupported = usable,
            pointerInjectionSupported = usable,
            joystickInjectionSupported = usable,
            lastError = api.lastError,
        )
    }

    private fun snapshotLocked(): InputInjectionSnapshot {
        val api = inputManager.resolve()
        val base = tracker?.snapshot(
            state = state,
            apiResolved = api.apiResolved,
            targetUidSupported = api.targetUidInjectionSupported,
            displayTargetingSupported = api.displayTargetingSupported,
        ) ?: InputInjectionSnapshot(state = state, lastError = lastError)
        return base.copy(
            backend = if (api.apiResolved) InputInjectionBackend.ShizukuUserService else InputInjectionBackend.None,
            privilegedUid = Process.myUid(),
            privilegedUidKind = Process.myUid().toUidKind(),
            lastError = if (base.lastError == InputInjectionError.None) lastError else base.lastError,
        )
    }

    private fun fail(error: InputInjectionError): Int {
        lastError = error
        state = InputInjectionState.Error
        return error.code
    }

    private companion object {
        const val RESET_REASON_SESSION_STOP = 1
        const val MAX_RESET_REASON = 6
    }
}

private fun Int.toUidKind(): PrivilegedUidKind = when (this) {
    0 -> PrivilegedUidKind.Root
    2000 -> PrivilegedUidKind.Shell
    else -> PrivilegedUidKind.Other
}
