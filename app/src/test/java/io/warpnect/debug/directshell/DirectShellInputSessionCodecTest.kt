package io.warpnect.debug.directshell

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
import io.warpnect.input.injection.InputInjectionMode
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.injection.PrivilegedUidKind
import io.warpnect.input.injection.UhidCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellInputSessionCodecTest {
    @Test
    fun roundTripsPrepareAndKeyForTheSameGeneration() {
        val config = InputInjectionConfig(
            targetUid = 1234,
            injectionMode = InputInjectionMode.WaitForResultDiagnostics,
            maxTrackedInjectionSlots = 4,
            maxPressedKeysPerSlot = 8,
            resetAllOnStop = false,
        )
        val encoded = DirectShellInputSessionCodec.encodePrepare(GENERATION, config)

        assertEquals(config, DirectShellInputSessionCodec.decodePrepare(encoded, GENERATION).value)
        assertNull(DirectShellInputSessionCodec.decodePrepare(encoded, GENERATION + 1).value)

        val event = AndroidKeyInjectionEvent(
            stateSlot = 2,
            sourceEventTimeUs = 55L,
            action = AndroidInjectionConstants.KEY_ACTION_DOWN,
            keyCode = 42,
            repeatCount = 1,
            metaState = 3,
            scanCode = 4,
            flags = 5,
            source = AndroidInjectionConstants.SOURCE_KEYBOARD,
            androidDeviceId = 6,
            displayId = 7,
        )
        assertEquals(
            event,
            DirectShellInputSessionCodec.decodeKey(
                DirectShellInputSessionCodec.encodeKey(GENERATION, event),
                GENERATION,
            ).value,
        )
    }

    @Test
    fun roundTripsTouchAndRejectsTrailingPayload() {
        val touch = AndroidTouchInjectionEvent(
            stateSlot = 1,
            sourceEventTimeUs = 10L,
            actionMasked = AndroidInjectionConstants.MOTION_ACTION_DOWN,
            actionIndex = 0,
            pointers = arrayOf(AndroidTouchPointer(0, 1, 10f, 20f)),
            source = AndroidInjectionConstants.SOURCE_TOUCHSCREEN,
            displayId = 0,
        )
        val encoded = DirectShellInputSessionCodec.encodeTouch(GENERATION, touch)
        val decoded = requireNotNull(DirectShellInputSessionCodec.decodeTouch(encoded, GENERATION).value)
        assertEquals(touch.stateSlot, decoded.stateSlot)
        assertEquals(touch.sourceEventTimeUs, decoded.sourceEventTimeUs)
        assertEquals(touch.actionMasked, decoded.actionMasked)
        assertEquals(touch.actionIndex, decoded.actionIndex)
        assertEquals(touch.pointers.toList(), decoded.pointers.toList())
        assertEquals(touch.source, decoded.source)
        assertEquals(touch.displayId, decoded.displayId)
        assertFalse(DirectShellInputSessionCodec.decodeTouch(encoded + byteArrayOf(1), GENERATION).isSuccess)
    }

    @Test
    fun roundTripsCapabilitiesAndSnapshotWithoutChangingDirectShellIdentity() {
        val capabilities = InputInjectionCapabilities(
            serviceAvailable = true,
            backend = InputInjectionBackend.DirectShell,
            privilegedUid = 2000,
            privilegedUidKind = PrivilegedUidKind.Shell,
            inputManagerApiResolved = true,
            asyncInjectionSupported = true,
            waitForResultSupported = true,
            targetUidInjectionSupported = true,
            displayTargetingSupported = true,
            keyInjectionSupported = true,
            touchInjectionSupported = true,
            pointerInjectionSupported = true,
            joystickInjectionSupported = true,
            uhidCapability = UhidCapability.PermissionDenied,
            uhidErrno = 13,
            maxPointers = 32,
            maxTrackedStateSlots = 32,
            lastError = InputInjectionError.None,
        )
        assertEquals(
            capabilities,
            DirectShellInputSessionCodec.decodeCapabilities(
                DirectShellInputSessionCodec.encodeCapabilities(GENERATION, capabilities),
                GENERATION,
            ).value,
        )

        val snapshot = InputInjectionSnapshot(
            state = InputInjectionState.Running,
            backend = InputInjectionBackend.DirectShell,
            privilegedUid = 2000,
            privilegedUidKind = PrivilegedUidKind.Shell,
            injectionMode = InputInjectionMode.WaitForResultDiagnostics,
            targetUid = 123,
            apiResolved = true,
            targetUidSupported = true,
            displayTargetingSupported = true,
            keyAttempts = 1,
            keySubmitted = 2,
            touchAttempts = 3,
            touchSubmitted = 4,
            pointerAttempts = 5,
            pointerSubmitted = 6,
            joystickAttempts = 7,
            joystickSubmitted = 8,
            resetRequests = 9,
            resetComplete = 10,
            syntheticKeyUps = 11,
            syntheticTouchCancels = 12,
            syntheticPointerReleases = 13,
            syntheticJoystickNeutralEvents = 14,
            orphanKeyUps = 15,
            invalidTouchSequences = 16,
            trackedStateSlots = 2,
            activePressedKeys = 3,
            activeTouchStreams = 4,
            activePointerButtonStates = 5,
            activeJoystickStates = 6,
            lastSourceEventTimeUs = 17L,
            lastLocalInjectionTimeMs = 18L,
            permissionFailures = 19,
            serviceFailures = 20,
            stateMayRemainInjected = false,
            lastError = InputInjectionError.None,
        )
        val decoded = DirectShellInputSessionCodec.decodeSnapshot(
            DirectShellInputSessionCodec.encodeSnapshot(GENERATION, snapshot),
            GENERATION,
        )
        assertTrue(decoded.isSuccess)
        assertEquals(snapshot, decoded.value)
    }

    @Test
    fun roundTripsPointerJoystickAndResetControlsForTheBoundGeneration() {
        val pointer = AndroidPointerInjectionEvent(
            stateSlot = 3,
            sourceEventTimeUs = 101L,
            action = AndroidInjectionConstants.MOTION_ACTION_SCROLL,
            actionButton = 0,
            xPx = 44f,
            yPx = 55f,
            horizontalScroll = 1f,
            verticalScroll = -1f,
            source = AndroidInjectionConstants.SOURCE_MOUSE,
            displayId = 2,
        )
        assertEquals(
            pointer,
            DirectShellInputSessionCodec.decodePointer(
                DirectShellInputSessionCodec.encodePointer(GENERATION, pointer),
                GENERATION,
            ).value,
        )

        val joystick = AndroidJoystickInjectionEvent(
            stateSlot = 4,
            sourceEventTimeUs = 102L,
            leftX = 0.5f,
            leftY = -0.5f,
            rightX = 0.25f,
            rightY = -0.25f,
            leftTrigger = 1f,
            rightTrigger = 0f,
            hatX = 1f,
            hatY = 0f,
            source = AndroidInjectionConstants.SOURCE_JOYSTICK,
            displayId = 2,
        )
        assertEquals(
            joystick,
            DirectShellInputSessionCodec.decodeJoystick(
                DirectShellInputSessionCodec.encodeJoystick(GENERATION, joystick),
                GENERATION,
            ).value,
        )

        val reset = DirectShellInputSessionCodec.decodeReset(
            DirectShellInputSessionCodec.encodeReset(
                GENERATION,
                InputResetScope.AllSlots,
                stateSlot = 0,
                reason = InputResetReason.SessionStop,
            ),
            GENERATION,
        )
        assertTrue(reset.isSuccess)
        assertEquals(InputResetScope.AllSlots, reset.value?.scope)
        assertEquals(InputResetReason.SessionStop, reset.value?.reason)
        assertTrue(
            DirectShellInputSessionCodec.decodeStop(
                DirectShellInputSessionCodec.encodeStop(GENERATION, true),
                GENERATION,
            ).value == true,
        )
    }

    private companion object {
        const val GENERATION = 7_010L
    }
}
