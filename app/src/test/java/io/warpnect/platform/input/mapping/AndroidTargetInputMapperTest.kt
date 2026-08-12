package io.warpnect.platform.input.mapping

import android.view.InputDevice
import android.view.KeyEvent
import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionResult
import io.warpnect.input.injection.InputInjectionServiceResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason as InjectionResetReason
import io.warpnect.input.injection.InputResetScope as InjectionResetScope
import io.warpnect.input.mapping.RemoteVideoViewportInputMapper
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_A
import io.warpnect.input.model.INPUT_GAMEPAD_BUTTON_DPAD_UP
import io.warpnect.input.model.INPUT_MODIFIER_LEFT_SHIFT
import io.warpnect.input.model.INPUT_MODIFIER_RIGHT_CONTROL
import io.warpnect.input.model.INPUT_POINTER_BUTTON_PRIMARY
import io.warpnect.input.model.INPUT_TOUCH_PRESSURE_VALID
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputGamepadState
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.model.InputPointerAbsolute
import io.warpnect.input.model.InputPointerRelative
import io.warpnect.input.model.InputResetReason
import io.warpnect.input.model.InputResetScope
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.InputTouchAction
import io.warpnect.input.model.InputTouchContact
import io.warpnect.input.model.InputTouchFrame
import io.warpnect.input.model.InputTouchToolType
import io.warpnect.video.render.VideoRenderGeometry
import io.warpnect.video.render.VideoViewportGeometryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTargetInputMapperTest {
    @Test
    fun hidKeyboardUsesSharedTableAndSideSpecificMetaState() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection)

        val result = mapper.mapAndInject(
            10,
            InputKeyEvent(
                deviceSlot = 4,
                usagePage = AndroidHidKeyboardMappingTable.KEYBOARD_USAGE_PAGE,
                usageId = 0x04,
                action = InputKeyAction.Down,
                repeatCount = 2,
                modifierMask = INPUT_MODIFIER_LEFT_SHIFT or INPUT_MODIFIER_RIGHT_CONTROL,
            ),
        )

        assertTrue(result.isSuccess)
        val key = injection.keys.single()
        assertEquals(KeyEvent.KEYCODE_A, key.keyCode)
        assertEquals(2, key.repeatCount)
        assertTrue(key.metaState and KeyEvent.META_SHIFT_LEFT_ON != 0)
        assertTrue(key.metaState and KeyEvent.META_SHIFT_ON != 0)
        assertTrue(key.metaState and KeyEvent.META_CTRL_RIGHT_ON != 0)
        assertTrue(key.metaState and KeyEvent.META_CTRL_ON != 0)
        assertEquals(InputDevice.SOURCE_KEYBOARD, key.source)

        val unsupported = mapper.mapAndInject(
            11,
            InputKeyEvent(4, 0x0007, 0x0068, InputKeyAction.Down, 0, 0),
        )
        assertEquals(AndroidTargetInputMappingOutcome.UnsupportedHidUsage, unsupported.outcome)
        assertEquals(1, injection.keys.size)
    }

    @Test
    fun touchUsesPointerIdForActionIndexAndTargetLogicalCoordinates() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection, width = 1920, height = 1080)
        val event = InputTouchFrame(
            deviceKind = InputDeviceKind.Touchscreen,
            deviceSlot = 6,
            action = InputTouchAction.PointerDown,
            actionPointerId = 2,
            contacts = listOf(
                touchContact(7, 0, 0),
                touchContact(2, 32768, 32768, pressure = 32768, flags = INPUT_TOUCH_PRESSURE_VALID),
                touchContact(19, 65535, 65535),
            ),
        )

        assertTrue(mapper.mapAndInject(12, event).isSuccess)
        val touch = injection.touches.single()
        assertEquals(5, touch.actionMasked)
        assertEquals(1, touch.actionIndex)
        assertEquals(7, touch.pointers[0].pointerId)
        assertEquals(2, touch.pointers[1].pointerId)
        assertEquals(960f, touch.pointers[1].xPx)
        assertEquals(540f, touch.pointers[1].yPx)
        assertEquals(1f, touch.pointers[0].pressure)
        assertEquals(0.5000076f, touch.pointers[1].pressure, 0.00001f)
        assertEquals(0, touch.androidDeviceId)
        assertEquals(0, touch.displayId)
    }

    @Test
    fun sourceViewportAndTargetMappingUseIndependentReceiverAndTargetDimensions() {
        val injection = RecordingInjectionController()
        val target = mapper(injection, width = 2560, height = 1440)
        val viewport = VideoViewportGeometryStore(
            VideoRenderGeometry.viewportGeometry(
                sourceWidth = 1920,
                sourceHeight = 1080,
                containerWidth = 1920,
                containerHeight = 1200,
                surfaceGeneration = 1,
                videoConfigGeneration = 1,
            ),
        )
        val targetSink = object : InputEventSink {
            override fun onInputEvent(
                eventTimeUs: Long,
                event: io.warpnect.input.model.WarpnectInputEvent,
            ): InputSinkResult {
                val result = target.mapAndInject(eventTimeUs, event)
                return if (result.isSuccess) InputSinkResult.Accepted else InputSinkResult.Rejected(result.outcome.name)
            }
        }
        val source = RemoteVideoViewportInputMapper(viewport, targetSink)

        val result = source.onInputEvent(
            1,
            InputTouchFrame(
                deviceKind = InputDeviceKind.Touchscreen,
                deviceSlot = 4,
                action = InputTouchAction.Down,
                actionPointerId = 0,
                contacts = listOf(touchContact(0, 32768, 32768)),
            ),
        )

        assertTrue(result is InputSinkResult.Accepted)
        val injected = injection.touches.single().pointers.single()
        assertEquals(1280f, injected.xPx)
        assertEquals(720f, injected.yPx)
    }

    @Test
    fun targetCoordinatesUseLogicalDisplayEdgesAndHandleOnePixelDimensions() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection, width = 1920, height = 1080)
        val edges = InputTouchFrame(
            InputDeviceKind.Touchscreen,
            6,
            InputTouchAction.Down,
            0,
            listOf(touchContact(0, 0, 65535)),
        )

        assertTrue(mapper.mapAndInject(1, edges).isSuccess)
        assertEquals(0f, injection.touches.single().pointers.single().xPx)
        assertEquals(1079f, injection.touches.single().pointers.single().yPx)

        val onePixelInjection = RecordingInjectionController()
        val onePixelMapper = mapper(onePixelInjection, width = 1, height = 1)
        assertTrue(onePixelMapper.mapAndInject(2, edges.copy(deviceSlot = 7)).isSuccess)
        assertEquals(0f, onePixelInjection.touches.single().pointers.single().xPx)
        assertEquals(0f, onePixelInjection.touches.single().pointers.single().yPx)
    }

    @Test
    fun relativePointerMapsNormalizedContentDeltaToTargetPixels() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection, width = 1920, height = 1080)

        assertTrue(
            mapper.mapAndInject(
                1,
                InputPointerRelative(
                    deviceKind = InputDeviceKind.Mouse,
                    deviceSlot = 3,
                    deltaXQ16_16 = 32768,
                    deltaYQ16_16 = -16384,
                    buttonMask = 0,
                ),
            ).isSuccess,
        )

        val pointer = injection.pointers.single()
        assertEquals(InputDevice.SOURCE_MOUSE_RELATIVE, pointer.source)
        assertEquals(960f, pointer.relativeXPx)
        assertEquals(-270f, pointer.relativeYPx)
    }

    @Test
    fun absoluteMouseSnapshotExpandsIntoBoundedButtonAndMotionSequence() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection)

        assertTrue(mapper.mapAndInject(1, pointer(0)).isSuccess)
        assertTrue(mapper.mapAndInject(2, pointer(INPUT_POINTER_BUTTON_PRIMARY)).isSuccess)
        assertTrue(mapper.mapAndInject(3, pointer(INPUT_POINTER_BUTTON_PRIMARY)).isSuccess)
        assertTrue(mapper.mapAndInject(4, pointer(0)).isSuccess)

        assertEquals(
            listOf(7, 0, 11, 2, 12, 1, 7),
            injection.pointers.map { it.action },
        )
        assertEquals(
            1,
            injection.pointers.count { it.action == 11 && it.actionButton != 0 },
        )
        assertEquals(
            1,
            injection.pointers.count { it.action == 12 && it.actionButton != 0 },
        )
    }

    @Test
    fun gamepadUsesHatAxesByDefaultAndIdenticalSnapshotIsNoOp() {
        val injection = RecordingInjectionController()
        val mapper = mapper(injection)
        val state = InputGamepadState(
            deviceSlot = 8,
            buttonMask = INPUT_GAMEPAD_BUTTON_A or INPUT_GAMEPAD_BUTTON_DPAD_UP,
            leftX = -32767,
            leftY = 32767,
            rightX = 0,
            rightY = 0,
            leftTrigger = 32768,
            rightTrigger = 65535,
        )

        assertTrue(mapper.mapAndInject(1, state).isSuccess)
        assertEquals(1, injection.joysticks.size)
        assertEquals(-1f, injection.joysticks.single().leftX)
        assertEquals(-1f, injection.joysticks.single().hatY)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, injection.keys.single().keyCode)
        assertEquals(0, injection.keys.single().action)

        val duplicate = mapper.mapAndInject(2, state)
        assertEquals(AndroidTargetInputMappingOutcome.NoOp, duplicate.outcome)
        assertEquals(1, injection.joysticks.size)
        assertEquals(1, injection.keys.size)
    }

    @Test
    fun dpadKeyModeDoesNotAlsoPopulateHatAxes() {
        val injection = RecordingInjectionController()
        val mapper = AndroidTargetInputMapper(
            injectionController = injection,
            displayGeometryProvider = MutableGeometryProvider(TargetDisplayGeometry(0, 1920, 1080, 0, 1, true)),
            deviceResolver = FakeDeviceResolver(),
            config = AndroidTargetInputMappingConfig(dpadInjectionMode = DpadInjectionMode.KeyEvents),
        )
        val state = InputGamepadState(9, INPUT_GAMEPAD_BUTTON_DPAD_UP, 0, 0, 0, 0, 0, 0)

        assertTrue(mapper.mapAndInject(1, state).isSuccess)
        assertEquals(0f, injection.joysticks.single().hatX)
        assertEquals(0f, injection.joysticks.single().hatY)
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, injection.keys.single().keyCode)
    }

    @Test
    fun requireCompatibleGamepadFailsWithoutTargetDeviceInsteadOfFallingBackToZero() {
        val injection = RecordingInjectionController()
        val resolver = RecordingResolver(AndroidTargetDeviceResolution())
        val mapper = AndroidTargetInputMapper(
            injectionController = injection,
            displayGeometryProvider = MutableGeometryProvider(TargetDisplayGeometry(0, 1920, 1080, 0, 1, true)),
            deviceResolver = resolver,
            config = AndroidTargetInputMappingConfig(
                gamepadDeviceResolutionPolicy = AndroidTargetDeviceResolutionPolicy.RequireSourceCompatible,
            ),
        )

        val result = mapper.mapAndInject(1, InputGamepadState(9, 0, 0, 0, 0, 0, 0, 0))

        assertEquals(AndroidTargetInputMappingOutcome.TargetDeviceUnavailable, result.outcome)
        assertEquals(InputDeviceKind.Gamepad, resolver.kinds.single())
        assertEquals(AndroidTargetDeviceResolutionPolicy.RequireSourceCompatible, resolver.policies.single())
        assertTrue(injection.keys.isEmpty())
        assertTrue(injection.joysticks.isEmpty())
    }

    @Test
    fun geometryChangeResetsActiveTouchBeforeMappingWithNewGeometry() {
        val geometry = MutableGeometryProvider(TargetDisplayGeometry(0, 1920, 1080, 0, 1, true))
        val injection = RecordingInjectionController()
        val mapper = mapper(injection, geometry = geometry)
        val down = InputTouchFrame(
            InputDeviceKind.Touchscreen,
            2,
            InputTouchAction.Down,
            0,
            listOf(touchContact(0, 32768, 32768)),
        )
        assertTrue(mapper.mapAndInject(1, down).isSuccess)

        geometry.current = TargetDisplayGeometry(0, 1080, 1920, 1, 2, true)
        val move = down.copy(action = InputTouchAction.Move, actionPointerId = 255)
        val moveResult = mapper.mapAndInject(2, move)

        assertEquals(1, injection.resets.size)
        assertEquals(InjectionResetScope.ThisSlot, injection.resets.single().scope)
        assertEquals(2, injection.resets.single().slot)
        assertEquals(AndroidTargetInputMappingOutcome.Suppressed, moveResult.outcome)
        assertEquals(1, injection.touches.size)
        assertEquals(1L, mapper.snapshot().geometryResets)
    }

    @Test
    fun resetOnlyClearsCacheWhenPrivilegedResetSucceeds() {
        val injection = RecordingInjectionController(resetError = InputInjectionError.InjectionRejected)
        val mapper = mapper(injection)
        val reset = InputResetState(
            deviceKind = InputDeviceKind.Mouse,
            deviceSlot = 3,
            scope = InputResetScope.ThisDevice,
            reason = InputResetReason.ErrorRecovery,
        )

        val result = mapper.mapAndInject(1, reset)

        assertEquals(AndroidTargetInputMappingOutcome.ResetInjectionFailure, result.outcome)
        assertEquals(AndroidTargetInputMappingState.Degraded, mapper.snapshot().state)
        assertFalse(result.isSuccess)
    }

    private fun mapper(
        injection: RecordingInjectionController,
        width: Int = 1920,
        height: Int = 1080,
        geometry: MutableGeometryProvider =
            MutableGeometryProvider(TargetDisplayGeometry(0, width, height, 0, 1, true)),
    ): AndroidTargetInputMapper = AndroidTargetInputMapper(
        injectionController = injection,
        displayGeometryProvider = geometry,
        deviceResolver = FakeDeviceResolver(),
    )

    private fun pointer(buttonMask: Int) = InputPointerAbsolute(
        deviceKind = InputDeviceKind.Mouse,
        deviceSlot = 3,
        xNormalized = 32768,
        yNormalized = 32768,
        buttonMask = buttonMask,
        pointerFlags = 0,
    )

    private fun touchContact(id: Int, x: Int, y: Int, pressure: Int = 0, flags: Int = 0) = InputTouchContact(
        pointerId = id,
        toolType = InputTouchToolType.Finger,
        pointerFlags = flags,
        xNormalized = x,
        yNormalized = y,
        pressure = pressure,
    )

    private class MutableGeometryProvider(
        var current: TargetDisplayGeometry,
    ) : TargetDisplayGeometryProvider {
        override fun geometryFor(displayId: Int): TargetDisplayGeometry? = current.takeIf { it.displayId == displayId }
    }

    private class FakeDeviceResolver : TargetInputDeviceResolver {
        override fun resolve(
            deviceKind: InputDeviceKind,
            policy: AndroidTargetDeviceResolutionPolicy,
        ): AndroidTargetDeviceResolution = AndroidTargetDeviceResolution(deviceId = 0)

        override fun invalidate() = Unit

        override fun close() = Unit
    }

    private class RecordingResolver(
        private val resolution: AndroidTargetDeviceResolution,
    ) : TargetInputDeviceResolver {
        val kinds = mutableListOf<InputDeviceKind>()
        val policies = mutableListOf<AndroidTargetDeviceResolutionPolicy>()

        override fun resolve(
            deviceKind: InputDeviceKind,
            policy: AndroidTargetDeviceResolutionPolicy,
        ): AndroidTargetDeviceResolution {
            kinds += deviceKind
            policies += policy
            return resolution
        }

        override fun invalidate() = Unit

        override fun close() = Unit
    }

    private class RecordingInjectionController(
        private val resetError: InputInjectionError = InputInjectionError.None,
    ) : InputInjectionController {
        val keys = mutableListOf<AndroidKeyInjectionEvent>()
        val touches = mutableListOf<AndroidTouchInjectionEvent>()
        val pointers = mutableListOf<AndroidPointerInjectionEvent>()
        val joysticks = mutableListOf<AndroidJoystickInjectionEvent>()
        val resets = mutableListOf<ResetCall>()

        override suspend fun queryCapabilities(): InputInjectionCapabilities = InputInjectionCapabilities()

        override suspend fun requestPermission(): InputInjectionPermissionResult =
            InputInjectionPermissionResult(InputInjectionError.None)

        override suspend fun prepare(config: InputInjectionConfig): InputInjectionResult = success(
            InputInjectionServiceResult.Prepared,
        )

        override fun start(): InputInjectionResult = success()

        override fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionResult {
            keys += event
            return success()
        }

        override fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionResult {
            touches += event
            return success()
        }

        override fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionResult {
            pointers += event
            return success()
        }

        override fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionResult {
            joysticks += event
            return success()
        }

        override fun resetState(
            scope: InjectionResetScope,
            stateSlot: Int,
            reason: InjectionResetReason,
        ): InputInjectionResult {
            resets += ResetCall(scope, stateSlot, reason)
            return if (resetError == InputInjectionError.None) {
                success(InputInjectionServiceResult.ResetComplete)
            } else {
                InputInjectionResult(InputInjectionServiceResult.InjectionRejected, InputInjectionSnapshot())
            }
        }

        override fun stop(): InputInjectionResult = success(InputInjectionServiceResult.ResetComplete)

        override fun snapshot(): InputInjectionSnapshot = InputInjectionSnapshot()

        override fun close() = Unit

        private fun success(result: InputInjectionServiceResult = InputInjectionServiceResult.SubmittedAsync) =
            InputInjectionResult(result, InputInjectionSnapshot())
    }

    private data class ResetCall(
        val scope: InjectionResetScope,
        val slot: Int,
        val reason: InjectionResetReason,
    )
}
