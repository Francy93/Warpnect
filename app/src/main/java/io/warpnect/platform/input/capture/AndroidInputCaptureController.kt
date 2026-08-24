package io.warpnect.platform.input.capture

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import io.warpnect.input.capture.InputCaptureCapabilities
import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.capture.InputCaptureController
import io.warpnect.input.capture.InputCaptureError
import io.warpnect.input.capture.InputCaptureResult
import io.warpnect.input.capture.InputCaptureSnapshot
import io.warpnect.input.capture.InputCaptureState
import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.INPUT_MAX_TOUCH_CONTACTS
import io.warpnect.input.model.INPUT_NO_ACTION_POINTER_ID
import io.warpnect.input.model.INPUT_POINTER_ABSOLUTE_PRESSURE_VALID
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
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.telemetry.InputSenderTelemetry
import kotlin.math.max

class AndroidInputCaptureController(
    context: Context,
    private val telemetry: InputSenderTelemetry? = null,
) : InputCaptureController,
    InputManager.InputDeviceListener {
    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var captureView: WarpnectInputCaptureView? = null
    private var config: InputCaptureConfig? = null
    private var sink: InputEventSink? = null
    private var registry = AndroidInputDeviceRegistry(DEFAULT_MAX_TRACKED_DEVICES)
    private val modifiers = AndroidKeyboardModifierTracker()
    private val gamepads = AndroidGamepadStateCache()
    private val mouseButtonMasks = mutableMapOf<Int, Int>()
    private var capturedMouseSlot: Int? = null
    private var listenerRegistered = false
    private var snapshot = InputCaptureSnapshot()

    override fun queryCapabilities(): InputCaptureCapabilities {
        val devices = inputManager.inputDeviceIds.toList().mapNotNull { inputManager.getInputDevice(it) }
        return InputCaptureCapabilities(
            touchAvailable = devices.any { it.sources.containsInputSource(InputDevice.SOURCE_TOUCHSCREEN) },
            hardwareKeyboardDetected = devices.any { it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC },
            mouseDetected = devices.any { it.sources.containsInputSource(InputDevice.SOURCE_MOUSE) },
            pointerCaptureSupported = Build.VERSION.SDK_INT >= 26,
            relativePointerAxesSupported = Build.VERSION.SDK_INT >= 26,
            gamepadCount = devices.count {
                it.sources.containsInputSource(InputDevice.SOURCE_GAMEPAD) ||
                    it.sources.containsInputSource(InputDevice.SOURCE_JOYSTICK)
            },
        )
    }

    override fun prepare(surface: View, config: InputCaptureConfig, sink: InputEventSink): InputCaptureResult {
        if (snapshot.state == InputCaptureState.Closed) return result(InputCaptureError.Closed)
        val validation = config.validate()
        if (validation != InputCaptureError.None) {
            recordError(validation, InputCaptureState.Error)
            return result(validation)
        }
        val inputView = surface as? WarpnectInputCaptureView
        if (inputView == null) {
            recordError(InputCaptureError.InvalidSurface, InputCaptureState.Error)
            return result(InputCaptureError.InvalidSurface)
        }
        updateSnapshot { it.copy(state = InputCaptureState.Preparing, lastError = InputCaptureError.None) }
        captureView?.detachController(this)
        captureView = inputView
        this.config = config
        this.sink = sink
        registry = AndroidInputDeviceRegistry(config.maxTrackedLogicalDevices)
        clearLocalInputState()
        inputView.attachController(this)
        inputView.isFocusable = true
        inputView.isFocusableInTouchMode = true
        registerInputListener()
        updateSnapshot {
            it.copy(
                state = InputCaptureState.Prepared,
                trackedLogicalDevices = 0,
                highestAssignedSlot = null,
                pointerCaptureRequested = false,
                pointerCaptureActive = false,
                lastError = InputCaptureError.None,
            )
        }
        return result(InputCaptureError.None)
    }

    override fun start(): InputCaptureResult {
        val current = snapshot.state
        if (current == InputCaptureState.Closed) return result(InputCaptureError.Closed)
        if (current == InputCaptureState.Running) return result(InputCaptureError.AlreadyRunning)
        if (current != InputCaptureState.Prepared) {
            recordError(InputCaptureError.NotPrepared)
            return result(InputCaptureError.NotPrepared)
        }
        registry.clearForNewLifecycle()
        clearLocalInputState()
        updateSnapshot {
            it.copy(
                state = InputCaptureState.Running,
                trackedLogicalDevices = 0,
                highestAssignedSlot = null,
                pointerCaptureRequested = false,
                pointerCaptureActive = false,
                lastError = InputCaptureError.None,
            )
        }
        return result(InputCaptureError.None)
    }

    override fun requestPointerCapture(): InputCaptureResult {
        if (snapshot.state == InputCaptureState.Closed) return result(InputCaptureError.Closed)
        if (snapshot.state != InputCaptureState.Running) return result(InputCaptureError.NotPrepared)
        val currentConfig = config ?: return result(InputCaptureError.NotPrepared)
        val view = captureView ?: return result(InputCaptureError.InvalidSurface)
        if (!currentConfig.enablePointerCapture || Build.VERSION.SDK_INT < 26) {
            recordError(InputCaptureError.PointerCaptureUnsupported)
            return result(InputCaptureError.PointerCaptureUnsupported)
        }
        view.requestFocus()
        view.requestPointerCapture()
        updateSnapshot { it.copy(pointerCaptureRequested = true, lastError = InputCaptureError.None) }
        return result(InputCaptureError.None)
    }

    override fun releasePointerCapture(): InputCaptureResult {
        if (snapshot.state == InputCaptureState.Closed) return result(InputCaptureError.Closed)
        emitMouseResetIfPresent(InputResetReason.UserRequest)
        captureView?.releasePointerCapture()
        updateSnapshot {
            it.copy(
                pointerCaptureRequested = false,
                pointerCaptureActive = false,
                lastError = InputCaptureError.None,
            )
        }
        return result(InputCaptureError.None)
    }

    override fun stop(): InputCaptureResult {
        if (snapshot.state == InputCaptureState.Closed) return result(InputCaptureError.Closed)
        if (snapshot.state == InputCaptureState.Stopped) return result(InputCaptureError.None)
        updateSnapshot { it.copy(state = InputCaptureState.Stopping) }
        if (sink != null) {
            emitReset(
                eventTimeUs = AndroidInputEventClock.callbackUptimeUs(),
                deviceKind = InputDeviceKind.Unknown,
                deviceSlot = 65_535,
                scope = InputResetScope.AllDevices,
                reason = InputResetReason.SessionStop,
            )
        }
        captureView?.releasePointerCapture()
        captureView?.detachController(this)
        unregisterInputListener()
        clearLocalInputState()
        registry.clearForNewLifecycle()
        sink = null
        config = null
        captureView = null
        updateSnapshot {
            it.copy(
                state = InputCaptureState.Stopped,
                trackedLogicalDevices = 0,
                highestAssignedSlot = null,
                pointerCaptureRequested = false,
                pointerCaptureActive = false,
                lastError = InputCaptureError.None,
            )
        }
        return result(InputCaptureError.None)
    }

    override fun snapshot(): InputCaptureSnapshot = snapshot

    override fun close() {
        if (snapshot.state == InputCaptureState.Closed) return
        stop()
        clearLocalInputState()
        sink = null
        config = null
        captureView = null
        updateSnapshot { it.copy(state = InputCaptureState.Closed, lastError = InputCaptureError.Closed) }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isRunning()) return false
        return if (isGamepadSource(event.source)) {
            handleGamepadKeyEvent(event)
        } else {
            handleKeyboardKeyEvent(event)
        }
    }

    fun handleTouchEvent(view: View, event: MotionEvent): Boolean {
        if (!isRunning() || !isEnabled(InputDeviceKind.Touchscreen)) return false
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> InputTouchAction.Down
            MotionEvent.ACTION_UP -> InputTouchAction.Up
            MotionEvent.ACTION_MOVE -> InputTouchAction.Move
            MotionEvent.ACTION_CANCEL -> InputTouchAction.Cancel
            MotionEvent.ACTION_POINTER_DOWN -> InputTouchAction.PointerDown
            MotionEvent.ACTION_POINTER_UP -> InputTouchAction.PointerUp
            else -> return false
        }
        val slot = slotFor(event.deviceId, touchDeviceKind(event.source)) ?: return consumedIfConfigured()
        if (event.actionMasked == MotionEvent.ACTION_MOVE && config?.captureTouchHistory == true) {
            for (historyIndex in 0 until event.historySize) {
                emitTouchFrame(
                    view = view,
                    event = event,
                    deviceSlot = slot,
                    action = InputTouchAction.Move,
                    actionPointerId = INPUT_NO_ACTION_POINTER_ID,
                    historyIndex = historyIndex,
                    eventTimeUs = AndroidInputEventClock.historicalMotionEventTimeUs(event, historyIndex),
                    historical = true,
                )
            }
        }
        val actionPointerId = if (action.usesTransitionPointer()) {
            pointerIdOrNull(event, event.actionIndex) ?: return consumedAfterInvalidPointer()
        } else {
            INPUT_NO_ACTION_POINTER_ID
        }
        emitTouchFrame(
            view = view,
            event = event,
            deviceSlot = slot,
            action = action,
            actionPointerId = actionPointerId,
            historyIndex = null,
            eventTimeUs = AndroidInputEventClock.motionEventTimeUs(event),
            historical = false,
        )
        return consumedIfConfigured()
    }

    fun handleGenericMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!isRunning()) return false
        if (isGamepadSource(event.source)) {
            return handleGamepadMotionEvent(event)
        }
        if (!isPointerSource(event.source)) return false
        return if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            handleScrollEvent(event)
        } else {
            handlePointerAbsoluteEvent(view, event)
        }
    }

    fun handleCapturedPointerEvent(view: View, event: MotionEvent): Boolean {
        if (!isRunning() || !isEnabled(InputDeviceKind.Mouse)) return false
        val slot = slotFor(event.deviceId, InputDeviceKind.Mouse) ?: return consumedIfConfigured()
        capturedMouseSlot = slot
        val previousButtonMask = mouseButtonMasks[slot] ?: 0
        if (config?.enablePointerCapture != true) return false
        for (historyIndex in 0 until event.historySize) {
            emitRelativePointerSample(view, event, slot, historyIndex, historical = true)
        }
        val emitted = emitRelativePointerSample(view, event, slot, historyIndex = null, historical = false)
        val currentButtonMask = AndroidPointerMapper.buttonMask(event.buttonState)
        if (!emitted && currentButtonMask != previousButtonMask) {
            emitPointerRelative(
                eventTimeUs = AndroidInputEventClock.motionEventTimeUs(event),
                deviceSlot = slot,
                deltaX = 0,
                deltaY = 0,
                buttonMask = currentButtonMask,
                historical = false,
            )
        }
        mouseButtonMasks[slot] = currentButtonMask
        return consumedIfConfigured()
    }

    fun onPointerCaptureChanged(hasCapture: Boolean) {
        val wasActive = snapshot.pointerCaptureActive
        updateSnapshot {
            it.copy(
                pointerCaptureActive = hasCapture,
                pointerCaptureRequested = hasCapture && it.pointerCaptureRequested,
                lastError = if (!hasCapture && wasActive) {
                    InputCaptureError.PointerCaptureLost
                } else {
                    it.lastError
                },
            )
        }
        if (!hasCapture && wasActive) {
            emitMouseResetIfPresent(InputResetReason.ErrorRecovery)
        }
    }

    fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus && isRunning()) {
            emitReset(
                eventTimeUs = AndroidInputEventClock.callbackUptimeUs(),
                deviceKind = InputDeviceKind.Unknown,
                deviceSlot = 65_535,
                scope = InputResetScope.AllDevices,
                reason = InputResetReason.FocusLost,
            )
            clearLocalInputState()
            captureView?.releasePointerCapture()
            updateSnapshot {
                it.copy(pointerCaptureRequested = false, pointerCaptureActive = false)
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        telemetry?.deviceAdded?.increment()
    }

    override fun onInputDeviceChanged(deviceId: Int) = Unit

    override fun onInputDeviceRemoved(deviceId: Int) {
        telemetry?.deviceRemoved?.increment()
        if (snapshot.state == InputCaptureState.Closed) return
        val removed = registry.removeAndroidDevice(deviceId)
        val nowUs = AndroidInputEventClock.callbackUptimeUs()
        for (assignment in removed) {
            emitReset(
                eventTimeUs = nowUs,
                deviceKind = assignment.kind,
                deviceSlot = assignment.slot,
                scope = InputResetScope.ThisDevice,
                reason = InputResetReason.DeviceDisconnected,
            )
            modifiers.removeSlot(assignment.slot)
            gamepads.removeSlot(assignment.slot)
            mouseButtonMasks.remove(assignment.slot)
            if (capturedMouseSlot == assignment.slot) capturedMouseSlot = null
        }
        updateDeviceSnapshot()
    }

    private fun handleKeyboardKeyEvent(event: KeyEvent): Boolean {
        if (!isEnabled(InputDeviceKind.Keyboard)) return false
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> InputKeyAction.Down
            KeyEvent.ACTION_UP -> InputKeyAction.Up
            else -> return false
        }
        val hid = AndroidKeyboardHidMapper.mapKeyCode(event.keyCode)
        if (hid == null) {
            incrementUnsupportedKey()
            return false
        }
        val slot = slotFor(event.deviceId, InputDeviceKind.Keyboard) ?: return consumedIfConfigured()
        val modifierMask = modifiers.update(
            deviceSlot = slot,
            keyCode = event.keyCode,
            down = action == InputKeyAction.Down,
        )
        val input = InputKeyEvent(
            deviceSlot = slot,
            usagePage = hid.usagePage,
            usageId = hid.usageId,
            action = action,
            repeatCount = if (action == InputKeyAction.Down) event.repeatCount.coerceIn(0, 65_535) else 0,
            modifierMask = modifierMask,
        )
        emitModel(
            eventTimeUs = AndroidInputEventClock.keyEventTimeUs(event),
            event = input,
        ) { it.copy(keyEventsCaptured = it.keyEventsCaptured + 1) }
        return consumedIfConfigured()
    }

    private fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (!isEnabled(InputDeviceKind.Gamepad)) return false
        val actionDown = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val buttonMask = AndroidGamepadMapper.buttonMaskForKeyCode(event.keyCode)
        if (buttonMask == 0) {
            incrementUnsupportedGamepadButton()
            return false
        }
        val slot = slotFor(event.deviceId, InputDeviceKind.Gamepad) ?: return consumedIfConfigured()
        val state = gamepads.updateButton(slot, buttonMask, actionDown)
        emitGamepadState(AndroidInputEventClock.keyEventTimeUs(event), state, historical = false)
        return consumedIfConfigured()
    }

    private fun handleGamepadMotionEvent(event: MotionEvent): Boolean {
        if (!isEnabled(InputDeviceKind.Gamepad) || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return false
        }
        val slot = slotFor(event.deviceId, InputDeviceKind.Gamepad) ?: return consumedIfConfigured()
        if (config?.captureGamepadHistory == true) {
            for (historyIndex in 0 until event.historySize) {
                emitGamepadAxes(event, slot, historyIndex, historical = true)
            }
        }
        emitGamepadAxes(event, slot, historyIndex = null, historical = false)
        return consumedIfConfigured()
    }

    private fun handlePointerAbsoluteEvent(view: View, event: MotionEvent): Boolean {
        if (!isEnabled(InputDeviceKind.Mouse)) return false
        val slot = slotFor(event.deviceId, InputDeviceKind.Mouse) ?: return consumedIfConfigured()
        val x = AndroidPointerMapper.normalizedCoordinate(event.x, view.width)
        val y = AndroidPointerMapper.normalizedCoordinate(event.y, view.height)
        if (x == null || y == null) {
            recordError(InputCaptureError.InvalidSurfaceDimensions)
            return consumedIfConfigured()
        }
        val buttonMask = AndroidPointerMapper.buttonMask(event.buttonState)
        val pressure = AndroidTouchMapper.normalizedAxis(event.pressure, event.axisRange(MotionEvent.AXIS_PRESSURE))
        val pointerFlags = if (pressure != null) INPUT_POINTER_ABSOLUTE_PRESSURE_VALID else 0
        val input = InputPointerAbsolute(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = slot,
            xNormalized = x,
            yNormalized = y,
            buttonMask = buttonMask,
            pointerFlags = pointerFlags,
            pressure = pressure ?: 0,
        )
        mouseButtonMasks[slot] = buttonMask
        emitModel(
            eventTimeUs = AndroidInputEventClock.motionEventTimeUs(event),
            event = input,
        ) { it.copy(pointerAbsoluteEvents = it.pointerAbsoluteEvents + 1) }
        return consumedIfConfigured()
    }

    private fun handleScrollEvent(event: MotionEvent): Boolean {
        if (!isEnabled(InputDeviceKind.Mouse)) return false
        val horizontal = AndroidPointerMapper.scrollQ88(event.getAxisValue(MotionEvent.AXIS_HSCROLL))
        val vertical = AndroidPointerMapper.scrollQ88(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        if (horizontal == null || vertical == null || (horizontal == 0 && vertical == 0)) {
            return false
        }
        val slot = slotFor(event.deviceId, InputDeviceKind.Mouse) ?: return consumedIfConfigured()
        val input = InputScroll(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = slot,
            horizontalQ8_8 = horizontal,
            verticalQ8_8 = vertical,
            buttonMask = AndroidPointerMapper.buttonMask(event.buttonState),
        )
        emitModel(
            eventTimeUs = AndroidInputEventClock.motionEventTimeUs(event),
            event = input,
        ) { it.copy(scrollEvents = it.scrollEvents + 1) }
        return consumedIfConfigured()
    }

    private fun emitGamepadAxes(event: MotionEvent, slot: Int, historyIndex: Int?, historical: Boolean) {
        val leftX = stickAxis(event, MotionEvent.AXIS_X, historyIndex)
        val leftY = stickAxis(event, MotionEvent.AXIS_Y, historyIndex)
        val rightX = stickAxis(event, MotionEvent.AXIS_Z, historyIndex)
        val rightY = stickAxis(event, MotionEvent.AXIS_RZ, historyIndex)
        val leftTrigger = triggerAxis(event, MotionEvent.AXIS_LTRIGGER, historyIndex)
        val rightTrigger = triggerAxis(event, MotionEvent.AXIS_RTRIGGER, historyIndex)
        val hatX = event.axisValue(MotionEvent.AXIS_HAT_X, historyIndex)
        val hatY = event.axisValue(MotionEvent.AXIS_HAT_Y, historyIndex)
        val state = gamepads.updateAxes(
            deviceSlot = slot,
            leftX = leftX,
            leftY = leftY,
            rightX = rightX,
            rightY = rightY,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            hatButtonMask = AndroidGamepadMapper.hatMask(hatX, hatY),
        )
        val eventTimeUs = if (historyIndex == null) {
            AndroidInputEventClock.motionEventTimeUs(event)
        } else {
            AndroidInputEventClock.historicalMotionEventTimeUs(event, historyIndex)
        }
        emitGamepadState(eventTimeUs, state, historical)
    }

    private fun emitTouchFrame(
        view: View,
        event: MotionEvent,
        deviceSlot: Int,
        action: InputTouchAction,
        actionPointerId: Int,
        historyIndex: Int?,
        eventTimeUs: Long,
        historical: Boolean,
    ) {
        if (view.width <= 0 || view.height <= 0) {
            recordError(InputCaptureError.InvalidSurfaceDimensions)
            return
        }
        if (event.pointerCount > INPUT_MAX_TOUCH_CONTACTS) {
            recordError(InputCaptureError.TooManyTouchPointers)
            return
        }
        val contacts = ArrayList<InputTouchContact>(event.pointerCount)
        val pressureRange = event.axisRange(MotionEvent.AXIS_PRESSURE)
        val sizeRange = event.axisRange(MotionEvent.AXIS_SIZE)
        for (pointerIndex in 0 until event.pointerCount) {
            val pointerId = pointerIdOrNull(event, pointerIndex)
            if (pointerId == null) {
                consumedAfterInvalidPointer()
                return
            }
            val x = if (historyIndex == null) {
                event.getX(pointerIndex)
            } else {
                event.getHistoricalX(pointerIndex, historyIndex)
            }
            val y = if (historyIndex == null) {
                event.getY(pointerIndex)
            } else {
                event.getHistoricalY(pointerIndex, historyIndex)
            }
            val xNormalized = AndroidPointerMapper.normalizedCoordinate(x, view.width)
            val yNormalized = AndroidPointerMapper.normalizedCoordinate(y, view.height)
            if (xNormalized == null || yNormalized == null) {
                recordError(InputCaptureError.InvalidSurfaceDimensions)
                return
            }
            val pressure = if (historyIndex == null) {
                event.getPressure(pointerIndex)
            } else {
                event.getHistoricalPressure(pointerIndex, historyIndex)
            }
            val size = if (historyIndex == null) {
                event.getSize(pointerIndex)
            } else {
                event.getHistoricalSize(pointerIndex, historyIndex)
            }
            val pressureNormalized = if (pressureRange != null) {
                AndroidTouchMapper.normalizedAxis(pressure, pressureRange)
            } else {
                null
            }
            val sizeNormalized = if (sizeRange != null) {
                AndroidTouchMapper.normalizedAxis(size, sizeRange)
            } else {
                null
            }
            contacts += InputTouchContact(
                pointerId = pointerId,
                toolType = AndroidTouchMapper.toolType(event.getToolType(pointerIndex)),
                pointerFlags = (if (pressureNormalized != null) INPUT_TOUCH_PRESSURE_VALID else 0) or
                    (if (sizeNormalized != null) INPUT_TOUCH_SIZE_VALID else 0),
                xNormalized = xNormalized,
                yNormalized = yNormalized,
                pressure = pressureNormalized ?: 0,
                size = sizeNormalized ?: 0,
            )
        }
        val frame = InputTouchFrame(
            deviceKind = touchDeviceKind(event.source),
            deviceSlot = deviceSlot,
            action = action,
            actionPointerId = actionPointerId,
            contacts = contacts,
        )
        emitModel(eventTimeUs, frame) {
            it.copy(
                touchFramesCaptured = it.touchFramesCaptured + 1,
                touchHistoricalFramesCaptured = it.touchHistoricalFramesCaptured + if (historical) 1 else 0,
            )
        }
    }

    private fun emitRelativePointerSample(
        view: View,
        event: MotionEvent,
        slot: Int,
        historyIndex: Int?,
        historical: Boolean,
    ): Boolean {
        val dx = event.axisValue(MotionEvent.AXIS_RELATIVE_X, historyIndex)
        val dy = event.axisValue(MotionEvent.AXIS_RELATIVE_Y, historyIndex)
        val qx = AndroidPointerMapper.relativeQ1616(dx, view.width)
        val qy = AndroidPointerMapper.relativeQ1616(dy, view.height)
        if (qx == null || qy == null) {
            recordError(InputCaptureError.InvalidSurfaceDimensions)
            return false
        }
        val buttonMask = AndroidPointerMapper.buttonMask(event.buttonState)
        if (qx == 0 && qy == 0 && buttonMask == (mouseButtonMasks[slot] ?: 0)) {
            return false
        }
        val eventTimeUs = if (historyIndex == null) {
            AndroidInputEventClock.motionEventTimeUs(event)
        } else {
            AndroidInputEventClock.historicalMotionEventTimeUs(event, historyIndex)
        }
        emitPointerRelative(eventTimeUs, slot, qx, qy, buttonMask, historical)
        return true
    }

    private fun emitPointerRelative(
        eventTimeUs: Long,
        deviceSlot: Int,
        deltaX: Int,
        deltaY: Int,
        buttonMask: Int,
        historical: Boolean,
    ) {
        val input = InputPointerRelative(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = deviceSlot,
            deltaXQ16_16 = deltaX,
            deltaYQ16_16 = deltaY,
            buttonMask = buttonMask,
        )
        emitModel(eventTimeUs, input) {
            it.copy(pointerRelativeEvents = it.pointerRelativeEvents + 1)
        }
        if (!historical) {
            mouseButtonMasks[deviceSlot] = buttonMask
        }
    }

    private fun emitGamepadState(eventTimeUs: Long, state: InputGamepadState, historical: Boolean) {
        emitModel(eventTimeUs, state) {
            it.copy(
                gamepadStatesCaptured = it.gamepadStatesCaptured + 1,
                gamepadHistoricalStatesCaptured = it.gamepadHistoricalStatesCaptured +
                    if (historical) 1 else 0,
            )
        }
    }

    private fun emitReset(
        eventTimeUs: Long,
        deviceKind: InputDeviceKind,
        deviceSlot: Int,
        scope: InputResetScope,
        reason: InputResetReason,
    ) {
        val reset = InputResetState(
            deviceKind = deviceKind,
            deviceSlot = deviceSlot,
            scope = scope,
            reason = reason,
        )
        emitModel(eventTimeUs, reset) { it.copy(resetEvents = it.resetEvents + 1) }
    }

    private fun emitMouseResetIfPresent(reason: InputResetReason) {
        val slot = capturedMouseSlot ?: registry
            .activeAssignments()
            .firstOrNull { it.kind == InputDeviceKind.Mouse }
            ?.slot ?: return
        emitReset(
            eventTimeUs = AndroidInputEventClock.callbackUptimeUs(),
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = slot,
            scope = InputResetScope.ThisDevice,
            reason = reason,
        )
        mouseButtonMasks.remove(slot)
        capturedMouseSlot = null
    }

    private fun emitModel(
        eventTimeUs: Long,
        event: WarpnectInputEvent,
        counter: (InputCaptureSnapshot) -> InputCaptureSnapshot,
    ) {
        if (event.validationError() != InputModelError.None) {
            recordError(InputCaptureError.InternalStateError)
            return
        }
        val callbackDelayUs = max(0L, AndroidInputEventClock.callbackUptimeUs() - eventTimeUs)
        val result = sink?.onInputEvent(eventTimeUs, event) ?: InputSinkResult.Rejected("No input sink")
        if (result !is InputSinkResult.Rejected) {
            telemetry?.capturedEvents?.increment()
            telemetry?.recordCaptureToSender(eventTimeUs, AndroidInputEventClock.callbackUptimeUs())
        }
        updateSnapshot {
            val counted = counter(it)
            counted.copy(
                lastEventTimeUs = eventTimeUs,
                lastCallbackDelayUs = callbackDelayUs,
                sinkFailures = counted.sinkFailures +
                    if (result is InputSinkResult.Rejected) 1 else 0,
                lastError = if (result is InputSinkResult.Rejected) {
                    InputCaptureError.SinkFailure
                } else {
                    InputCaptureError.None
                },
            )
        }
    }

    private fun slotFor(androidDeviceId: Int, kind: InputDeviceKind): Int? {
        val slot = registry.slotFor(androidDeviceId, kind)
        if (slot == null) {
            updateSnapshot {
                it.copy(
                    deviceRegistryFull = it.deviceRegistryFull + 1,
                    lastError = InputCaptureError.DeviceRegistryFull,
                )
            }
            return null
        }
        updateDeviceSnapshot()
        return slot
    }

    private fun updateDeviceSnapshot() {
        updateSnapshot {
            it.copy(
                trackedLogicalDevices = registry.trackedLogicalDevices(),
                highestAssignedSlot = registry.highestAssignedSlot(),
            )
        }
    }

    private fun registerInputListener() {
        if (!listenerRegistered) {
            inputManager.registerInputDeviceListener(this, mainHandler)
            listenerRegistered = true
        }
    }

    private fun unregisterInputListener() {
        if (listenerRegistered) {
            runCatching { inputManager.unregisterInputDeviceListener(this) }
            listenerRegistered = false
        }
    }

    private fun clearLocalInputState() {
        modifiers.clear()
        gamepads.clear()
        mouseButtonMasks.clear()
        capturedMouseSlot = null
    }

    private fun incrementUnsupportedKey() {
        updateSnapshot {
            it.copy(
                unsupportedKeyEvents = it.unsupportedKeyEvents + 1,
                lastError = InputCaptureError.UnsupportedKeyboardKey,
            )
        }
    }

    private fun incrementUnsupportedGamepadButton() {
        updateSnapshot {
            it.copy(
                unsupportedGamepadButtons = it.unsupportedGamepadButtons + 1,
                lastError = InputCaptureError.UnsupportedGamepadButton,
            )
        }
    }

    private fun consumedAfterInvalidPointer(): Boolean {
        updateSnapshot {
            it.copy(
                invalidPointerIds = it.invalidPointerIds + 1,
                lastError = InputCaptureError.InvalidPointerId,
            )
        }
        return consumedIfConfigured()
    }

    private fun pointerIdOrNull(event: MotionEvent, pointerIndex: Int): Int? {
        if (pointerIndex !in 0 until event.pointerCount) return null
        val pointerId = event.getPointerId(pointerIndex)
        return if (pointerId in 0 until INPUT_MAX_TOUCH_CONTACTS) pointerId else null
    }

    private fun stickAxis(event: MotionEvent, axis: Int, historyIndex: Int?): Int? {
        val range = event.axisRange(axis) ?: AndroidAxisRange(-1.0f, 1.0f)
        return AndroidGamepadMapper.normalizeStick(event.axisValue(axis, historyIndex), range)
    }

    private fun triggerAxis(event: MotionEvent, axis: Int, historyIndex: Int?): Int? {
        val range = event.axisRange(axis) ?: AndroidAxisRange(0.0f, 1.0f)
        return AndroidGamepadMapper.normalizeTrigger(event.axisValue(axis, historyIndex), range)
    }

    private fun isRunning(): Boolean = snapshot.state == InputCaptureState.Running

    private fun isEnabled(kind: InputDeviceKind): Boolean = config?.enabledKinds?.contains(kind) == true

    private fun consumedIfConfigured(): Boolean = config?.consumeCapturedEvents == true

    private fun result(error: InputCaptureError): InputCaptureResult =
        InputCaptureResult(error = error, snapshot = snapshot)

    private fun recordError(error: InputCaptureError, state: InputCaptureState? = null) {
        updateSnapshot { it.copy(state = state ?: it.state, lastError = error) }
    }

    private fun updateSnapshot(block: (InputCaptureSnapshot) -> InputCaptureSnapshot) {
        snapshot = block(snapshot)
    }

    private fun isGamepadSource(source: Int): Boolean = source.containsInputSource(InputDevice.SOURCE_GAMEPAD) ||
        source.containsInputSource(InputDevice.SOURCE_JOYSTICK)

    private fun isPointerSource(source: Int): Boolean = source.containsInputSource(InputDevice.SOURCE_MOUSE) ||
        source.containsInputSource(InputDevice.SOURCE_TOUCHPAD) ||
        source.containsInputSource(InputDevice.SOURCE_STYLUS)

    private fun touchDeviceKind(source: Int): InputDeviceKind = when {
        source.containsInputSource(InputDevice.SOURCE_TOUCHPAD) -> InputDeviceKind.Touchpad
        source.containsInputSource(InputDevice.SOURCE_STYLUS) -> InputDeviceKind.Stylus
        else -> InputDeviceKind.Touchscreen
    }

    private fun InputTouchAction.usesTransitionPointer(): Boolean = when (this) {
        InputTouchAction.Down,
        InputTouchAction.Up,
        InputTouchAction.PointerDown,
        InputTouchAction.PointerUp,
        -> true
        else -> false
    }

    private fun WarpnectInputEvent.validationError(): InputModelError = when (this) {
        is InputKeyEvent -> validate()
        is InputTouchFrame -> validate()
        is InputPointerAbsolute -> validate()
        is InputPointerRelative -> validate()
        is InputScroll -> validate()
        is InputGamepadState -> validate()
        is InputResetState -> validate()
    }

    private companion object {
        const val DEFAULT_MAX_TRACKED_DEVICES = 32
    }
}
