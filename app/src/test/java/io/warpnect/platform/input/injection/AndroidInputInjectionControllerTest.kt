package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionPermissionResult
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputInjectionState
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInputInjectionControllerTest {
    @Test
    fun lifecycleIsExplicitAndHotPathUsesOneSynchronousGatewayCall() = runBlocking {
        val gateway = FakeGateway()
        val controller = AndroidInputInjectionController.forTesting(gateway)

        assertEquals(InputInjectionError.NotPrepared, controller.start().error)
        assertTrue(controller.prepare(InputInjectionConfig(targetUid = 123)).isSuccess)
        assertEquals(123, gateway.preparedConfig?.targetUid)
        assertTrue(controller.start().isSuccess)
        assertTrue(controller.injectKey(key()).isSuccess)
        assertEquals(1, gateway.keyCalls)
        assertEquals(
            InputInjectionError.None,
            controller.resetState(InputResetScope.AllSlots, 0, InputResetReason.SessionStop).error,
        )
        assertTrue(controller.stop().isSuccess)
        assertEquals(InputInjectionState.Stopped, controller.snapshot().state)

        controller.close()
        assertTrue(gateway.closed)
        assertEquals(InputInjectionError.Closed, controller.start().error)
    }

    @Test
    fun targetUidUnsupportedFailsPrepareWithoutUntargetedEventSubmission() = runBlocking {
        val gateway = FakeGateway(prepareError = InputInjectionError.TargetUidUnsupported)
        val controller = AndroidInputInjectionController.forTesting(gateway)

        assertEquals(
            InputInjectionError.TargetUidUnsupported,
            controller.prepare(InputInjectionConfig(targetUid = 99)).error,
        )
        assertEquals(0, gateway.keyCalls)
        assertEquals(InputInjectionError.NotRunning, controller.injectKey(key()).error)
    }

    private fun key(): AndroidKeyInjectionEvent = AndroidKeyInjectionEvent(
        stateSlot = 0,
        sourceEventTimeUs = 10L,
        action = AndroidInjectionConstants.KEY_ACTION_DOWN,
        keyCode = 42,
        source = AndroidInjectionConstants.SOURCE_KEYBOARD,
        displayId = 0,
    )

    private class FakeGateway(
        private val prepareError: InputInjectionError = InputInjectionError.None,
    ) : PrivilegedInputInjectionGateway {
        var preparedConfig: InputInjectionConfig? = null
        var keyCalls = 0
        var closed = false
        private var state = InputInjectionState.Stopped

        override suspend fun queryCapabilities(): InputInjectionCapabilities = InputInjectionCapabilities(
            serviceAvailable = true,
        )

        override suspend fun requestPermission(): InputInjectionPermissionResult =
            InputInjectionPermissionResult(InputInjectionError.None)

        override suspend fun prepare(config: InputInjectionConfig): InputInjectionError {
            preparedConfig = config
            if (prepareError == InputInjectionError.None) state = InputInjectionState.Prepared
            return prepareError
        }

        override fun start(): InputInjectionError {
            state = InputInjectionState.Running
            return InputInjectionError.None
        }

        override fun injectKey(event: AndroidKeyInjectionEvent): Int {
            keyCalls += 1
            return 0
        }

        override fun injectTouch(event: io.warpnect.input.injection.AndroidTouchInjectionEvent): Int = 0

        override fun injectPointer(event: io.warpnect.input.injection.AndroidPointerInjectionEvent): Int = 0

        override fun injectJoystick(event: io.warpnect.input.injection.AndroidJoystickInjectionEvent): Int = 0

        override fun reset(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int = 2

        override fun stop(resetAll: Boolean): Int {
            state = InputInjectionState.Stopped
            return 2
        }

        override fun snapshot(): InputInjectionSnapshot = InputInjectionSnapshot(state = state)

        override fun close() {
            closed = true
        }
    }
}
