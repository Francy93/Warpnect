package io.warpnect.platform.input.injection

import io.warpnect.input.injection.AndroidJoystickInjectionEvent
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidPointerInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.InputInjectionCapabilities
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.DirectRootState
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import io.warpnect.platform.privileged.runtime.RootControlSession
import io.warpnect.platform.privileged.runtime.RootHelperIdentity
import io.warpnect.platform.privileged.runtime.RootRuntimeBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRootInputInjectionGatewayTest {
    @Test fun `ready root accepts typed input`() = runBlocking {
        val (runtime, control) = ready()
        val gateway = DirectRootInputInjectionGateway(runtime)
        assertEquals(InputInjectionError.None, gateway.prepare(InputInjectionConfig()))
        assertEquals(InputInjectionError.None, gateway.start())
        assertEquals(1, gateway.injectKey(key()))
        assertEquals(1, control.keys)
        gateway.close()
        runtime.close()
    }

    @Test fun `absent runtime does not issue an input request`() {
        val control = Control()
        val gateway = DirectRootInputInjectionGateway(runtime(control))
        assertEquals(InputInjectionError.ServiceDied.code, gateway.injectKey(key()))
        assertEquals(0, control.keys)
    }

    @Test fun `invalidated old generation cannot target the next runtime`() {
        val (first, control) = ready()
        val old = DirectRootInputInjectionGateway(first)
        first.close()
        val (second, fresh) = ready(11)
        assertEquals(InputInjectionError.ServiceDied.code, old.injectKey(key()))
        assertEquals(0, control.keys)
        assertEquals(0, fresh.keys)
        second.close()
    }

    @Test fun `release resets all state and makes gateway unusable`() {
        val (runtime, control) = ready()
        val gateway = DirectRootInputInjectionGateway(runtime)
        gateway.injectKey(key())
        gateway.close()
        gateway.close()
        assertEquals(1, control.stops)
        assertEquals(InputInjectionError.Closed.code, gateway.injectKey(key()))
        assertEquals(1, control.keys)
        runtime.close()
    }

    @Test fun `helper loss fails boundedly and invalidates runtime`() {
        val (runtime, control) = ready()
        val gateway = DirectRootInputInjectionGateway(runtime)
        control.lost = true
        assertEquals(InputInjectionError.ServiceDied.code, gateway.injectKey(key()))
        assertEquals(DirectRootState.HELPER_LOST, runtime.snapshot.state)
        assertTrue(gateway.snapshot().stateMayRemainInjected)
        runtime.close()
    }

    @Test fun `reset delegates canonical scope and stop never opts out of reset`() {
        val (runtime, control) = ready()
        val gateway = DirectRootInputInjectionGateway(runtime)
        assertEquals(2, gateway.reset(InputResetScope.AllSlots, 0, InputResetReason.SessionStop))
        assertEquals(2, gateway.stop(false))
        assertEquals(1, control.resets)
        assertEquals(1, control.stops)
        runtime.close()
    }

    @Test fun `malformed config never reaches helper`() = runBlocking {
        val (runtime, control) = ready()
        assertEquals(
            InputInjectionError.InvalidConfiguration,
            DirectRootInputInjectionGateway(runtime).prepare(InputInjectionConfig(maxTrackedInjectionSlots = 1000)),
        )
        assertEquals(0, control.prepares)
        runtime.close()
    }

    @Test fun `root control without input extension cannot inject`() {
        val control = object : RootControlSession {
            override val identity = identity(10)
            override fun ping() = Unit
            override fun shutdown() = Unit
            override fun close() = Unit
        }
        val runtime = runtime(control)
        assertTrue(runtime.acquireAuthority().ready)
        assertEquals(InputInjectionError.ServiceDied.code, DirectRootInputInjectionGateway(runtime).injectKey(key()))
        runtime.close()
    }

    @Test fun `rpc bounds reject unreasonable and nonfinite data`() {
        assertTrue(RootInputRequestBounds.valid(key()))
        assertTrue(RootInputRequestBounds.valid(key().copy(stateSlot = 65535)))
        assertFalse(RootInputRequestBounds.valid(key().copy(keyCode = Int.MAX_VALUE)))
        assertFalse(RootInputRequestBounds.valid(key().copy(sourceEventTimeUs = Long.MAX_VALUE)))
        assertFalse(RootInputRequestBounds.valid(key().copy(displayId = -1)))
        val pointer = AndroidPointerInjectionEvent(1, 1000, 7, xPx = 100f, yPx = 100f, source = 0x2002, displayId = 0)
        assertTrue(RootInputRequestBounds.valid(pointer))
        assertFalse(RootInputRequestBounds.valid(pointer.copy(xPx = Float.NaN)))
        assertFalse(RootInputRequestBounds.valid(pointer.copy(pressure = 2f)))
        assertFalse(RootInputRequestBounds.valid(pointer.copy(relativeXPx = Float.MAX_VALUE)))
        val joystick = AndroidJoystickInjectionEvent(
            1, 1000, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            source = 0x1000010, displayId = 0,
        )
        assertTrue(RootInputRequestBounds.valid(joystick))
        assertFalse(RootInputRequestBounds.valid(joystick.copy(leftX = 2f)))
    }

    private fun ready(generation: Long = 10): Pair<DirectRootRuntime, Control> {
        val control = Control(generation)
        return runtime(control).also { assertTrue(it.acquireAuthority().ready) } to control
    }

    private fun runtime(control: RootControlSession) = DirectRootRuntime(object : RootRuntimeBootstrap {
        override val generation = control.identity.generation
        override val provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT
        override val expectedArtifactSha256 = "a".repeat(64)
        override fun acquire() = control
        override fun cancel() = Unit
    })

    private class Control(generation: Long = 10) : RootControlSession, RootInputControl {
        override val identity = identity(generation)
        var keys = 0
        var prepares = 0
        var stops = 0
        var resets = 0
        var lost = false
        override fun ping() = check(!lost)
        override fun shutdown() = Unit
        override fun close() = Unit
        override fun capabilities() = InputInjectionCapabilities(serviceAvailable = true)
        override fun prepareInput(config: InputInjectionConfig): Int {
            prepares++
            return 4
        }
        override fun startInput() = 1
        override fun key(event: AndroidKeyInjectionEvent): Int {
            check(!lost)
            keys++
            return 1
        }
        override fun touch(event: AndroidTouchInjectionEvent) = 1
        override fun pointer(event: AndroidPointerInjectionEvent) = 1
        override fun joystick(event: AndroidJoystickInjectionEvent) = 1
        override fun resetInput(scope: InputResetScope, stateSlot: Int, reason: InputResetReason): Int {
            resets++
            return 2
        }
        override fun stopInput(): Int {
            stops++
            return 2
        }
        override fun inputSnapshot() = InputInjectionSnapshot()
    }

    companion object {
        private fun identity(generation: Long) = RootHelperIdentity(
            100, 0, 0, "u:r:su:s0", 26, generation,
            DirectRootRuntime.PROTOCOL_VERSION, DirectRootRuntime.IMPLEMENTATION, "a".repeat(64),
        )
        private fun key() = AndroidKeyInjectionEvent(0, 1000, 0, 29, source = 0x101, displayId = 0)
    }
}
