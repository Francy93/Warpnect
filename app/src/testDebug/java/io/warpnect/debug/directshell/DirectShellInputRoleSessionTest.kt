package io.warpnect.debug.directshell

import io.warpnect.debug.directshell.DirectShellInputRoleSession.Phase
import io.warpnect.debug.directshell.DirectShellRoleCommands.Input
import io.warpnect.input.injection.AndroidInjectionConstants
import io.warpnect.input.injection.AndroidKeyInjectionEvent
import io.warpnect.input.injection.AndroidTouchInjectionEvent
import io.warpnect.input.injection.AndroidTouchPointer
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellInputRoleSessionTest {
    private val owner = DirectShellInputRoleSession.Owner()
    private val other = DirectShellInputRoleSession.Owner()
    private val role = DirectShellInputRoleSession(owner, 9, targetUid = 12001)
    private val codec = DirectShellInputSessionCodec
    private val bare = codec.generationRequest(9)
    private val prepare = codec.encodePrepare(9, InputInjectionConfig(targetUid = 12001))

    @Test
    fun refusesDiagnosticsMalformedAndUnownedCommandsBeforeDispatch() {
        assertNull(role.begin(owner, 1, bare))
        assertNull(role.begin(owner, Input.START.wire, bare))
        assertNull(role.begin(owner, Input.CAPABILITIES.wire, codec.generationRequest(8)))
        assertNull(role.begin(owner, Input.PREPARE.wire, prepare + byteArrayOf(0)))
        assertNull(role.begin(other, Input.PREPARE.wire, prepare))
        val wrongTarget = codec.encodePrepare(9, InputInjectionConfig(targetUid = 12002))
        val wildcard = codec.encodePrepare(9, InputInjectionConfig())
        val diagnostic = codec.encodePrepare(
            9,
            InputInjectionConfig(targetUid = 12001, injectionMode = InputInjectionMode.WaitForResultDiagnostics),
        )
        assertNull(role.begin(owner, Input.PREPARE.wire, wrongTarget))
        assertNull(role.begin(owner, Input.PREPARE.wire, wildcard))
        assertNull(role.begin(owner, Input.PREPARE.wire, diagnostic))
        assertEquals(Phase.IDLE, role.phase())
    }

    @Test
    fun oneExactValidatedCallAtATimeAndStopDoesNotAttestDrain() {
        val mutable = prepare.copyOf()
        val reserved = checkNotNull(role.begin(owner, Input.PREPARE.wire, mutable))
        mutable.fill(0)
        reserved.copyPayload().fill(0)
        assertArrayEquals(prepare, reserved.copyPayload())
        assertFalse(role.complete(other, reserved, true))
        assertNull(role.begin(owner, Input.CAPABILITIES.wire, bare))
        assertTrue(role.complete(owner, reserved, true))
        assertTrue(role.complete(owner, checkNotNull(role.begin(owner, Input.START.wire, bare)), true))
        assertEquals(Phase.STARTED, role.phase())
        assertNull(role.begin(owner, Input.STOP.wire, bare))
        assertNull(role.begin(owner, Input.STOP.wire, codec.encodeStop(9, resetAll = false)))
        val resetAll = codec.encodeStop(9, resetAll = true)
        assertNull(role.begin(owner, Input.STOP.wire, resetAll.copyOf().apply { this[lastIndex] = 2 }))
        assertTrue(role.complete(owner, checkNotNull(role.begin(owner, Input.STOP.wire, resetAll)), true))
        assertEquals(Phase.DRAIN_PENDING, role.phase())
        assertFalse(role.observeDrain(owner, 9, workerExited = false, inputReleased = true))
        assertFalse(role.observeDrain(owner, 9, workerExited = true, inputReleased = false))
        assertFalse(role.observeDrain(other, 9, workerExited = true, inputReleased = true))
        assertTrue(role.observeDrain(owner, 9, workerExited = true, inputReleased = true))
        assertNull(role.begin(owner, Input.SNAPSHOT.wire, bare))
    }

    @Test
    fun cancelAndUncertainResultNeverPermitReplayOrLateTransition() {
        val request = checkNotNull(role.begin(owner, Input.PREPARE.wire, prepare))
        assertTrue(role.cancel(owner))
        assertTrue(role.complete(owner, request, true))
        assertEquals(Phase.DRAIN_PENDING, role.phase())
        assertFalse(role.complete(owner, request, true))
        assertFalse(role.observeDrain(owner, 8, workerExited = true, inputReleased = true))
        assertNull(role.begin(owner, Input.PREPARE.wire, prepare))

        val newer = DirectShellInputRoleSession(owner, 9, targetUid = 12001)
        val next = checkNotNull(newer.begin(owner, Input.PREPARE.wire, prepare))
        assertFalse(newer.complete(owner, request, true))
        assertTrue(newer.complete(owner, next, false))
        assertEquals(Phase.DRAIN_PENDING, newer.phase())
    }

    @Test
    fun eventFramesRequireOneCurrentOwnerAndRejectUnboundedInputWithoutInjection() {
        assertTrue(role.complete(owner, checkNotNull(role.begin(owner, Input.PREPARE.wire, prepare)), true))
        assertTrue(role.complete(owner, checkNotNull(role.begin(owner, Input.START.wire, bare)), true))
        val key = AndroidKeyInjectionEvent(
            0,
            1L,
            AndroidInjectionConstants.KEY_ACTION_DOWN,
            20,
            source = AndroidInjectionConstants.SOURCE_KEYBOARD,
            displayId = 0,
        )
        val touch = AndroidTouchInjectionEvent(
            0,
            2L,
            AndroidInjectionConstants.MOTION_ACTION_DOWN,
            0,
            arrayOf(AndroidTouchPointer(0, 1, Float.NaN, 40f)),
            source = AndroidInjectionConstants.SOURCE_TOUCHSCREEN,
            displayId = 0,
        )
        assertNull(role.begin(owner, Input.TOUCH.wire, codec.encodeTouch(9, touch)))
        assertNull(role.begin(owner, Input.KEY.wire, codec.encodeKey(9, key.copy(displayId = -1))))
        assertNull(role.begin(owner, Input.KEY.wire, codec.encodeKey(8, key)))
        val frame = codec.encodeKey(9, key)
        val release = CountDownLatch(1)
        val contenders = List(2) {
            FutureTask {
                check(release.await(2, TimeUnit.SECONDS))
                role.begin(owner, Input.KEY.wire, frame)
            }
        }
        val workers = contenders.map { Thread(it, "input-role-test").apply { start() } }
        try {
            release.countDown()
            val active = contenders.mapNotNull { it.get(2, TimeUnit.SECONDS) }.single()
            assertEquals(key, codec.decodeKey(active.copyPayload(), 9).value)
            assertTrue(role.cancel(owner))
            assertTrue(role.complete(owner, active, true))
            assertEquals(Phase.DRAIN_PENDING, role.phase())
            assertNull(role.begin(owner, Input.KEY.wire, frame))
        } finally {
            workers.forEach {
                it.join(2_000)
                assertFalse(it.isAlive)
            }
        }
    }
}
