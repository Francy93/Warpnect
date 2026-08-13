package io.warpnect.input.session

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
import io.warpnect.input.injection.InputResetReason
import io.warpnect.input.injection.InputResetScope
import io.warpnect.input.model.InputDeviceKind
import io.warpnect.input.model.InputKeyAction
import io.warpnect.input.model.InputKeyEvent
import io.warpnect.input.transport.InputMessageTypeMetadata
import io.warpnect.input.transport.InputReceivedEvent
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputReceiverError
import io.warpnect.input.transport.InputReceiverResult
import io.warpnect.input.transport.InputReceiverRuntime
import io.warpnect.input.transport.InputReceiverSnapshot
import io.warpnect.input.transport.InputReceiverWaitResult
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingOutcome
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingResult
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingSnapshot
import io.warpnect.platform.input.mapping.TargetInputMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseInputReceiverSessionControllerTest {
    @Test
    fun startsInjectionBeforeReceiverAndResetsOnlyAfterReceiveStops() = runBlocking {
        val trace = mutableListOf<String>()
        val runtime = FakeRuntime(trace)
        val mapper = FakeMapper(trace)
        val injection = FakeInjection(trace)
        val session = ReverseInputReceiverSessionController(runtime, mapper, injection)

        assertTrue(session.start(config()).isSuccess)
        assertEquals(listOf("injection.prepare", "injection.start", "receiver.prepare", "receiver.start"), trace)

        runtime.events.offer(event())
        assertTrue(mapper.mapped.await(1, TimeUnit.SECONDS))
        assertEquals(1, mapper.mapCalls)

        assertTrue(session.stop().isSuccess)
        assertTrue(trace.indexOf("receiver.interrupt") < trace.indexOf("mapper.reset"))
        assertTrue(trace.indexOf("mapper.reset") < trace.indexOf("injection.stop"))
        assertEquals(1, mapper.resetCalls)
    }

    @Test
    fun emergencyResetIsSerializedThroughTheReceiverContext() = runBlocking {
        val runtime = FakeRuntime(mutableListOf())
        val mapper = FakeMapper(mutableListOf())
        val session = ReverseInputReceiverSessionController(runtime, mapper, FakeInjection(mutableListOf()))

        assertTrue(session.start(config()).isSuccess)
        assertTrue(session.requestEmergencyReset().isSuccess)
        assertTrue(mapper.resetObserved.await(1, TimeUnit.SECONDS))
        assertEquals(1, mapper.resetCalls)
        assertTrue(session.stop().isSuccess)
    }

    private fun config(): ReverseInputReceiverSessionConfig = ReverseInputReceiverSessionConfig(
        receiverConfig = InputReceiverConfig("0.0.0.0", 43_001, "127.0.0.1", 43_002),
        injectionConfig = InputInjectionConfig(),
        receiverWaitTimeoutUs = 50_000L,
    )

    private fun event(): InputReceiverWaitResult.EventReady = InputReceiverWaitResult.EventReady(
        InputReceivedEvent(
            messageType = InputMessageTypeMetadata.Key,
            deviceKind = InputDeviceKind.Keyboard,
            deviceSlot = 1,
            sequenceNumber = 7,
            sourceEventTimeUs = 123L,
            event = InputKeyEvent(1, 7, 4, InputKeyAction.Down, 0, 0),
        ),
    )

    private class FakeRuntime(
        private val trace: MutableList<String>,
    ) : InputReceiverRuntime {
        val events = LinkedBlockingQueue<InputReceiverWaitResult>()
        private var running = false

        override fun prepare(config: InputReceiverConfig): InputReceiverResult {
            trace += "receiver.prepare"
            return InputReceiverResult(InputReceiverError.None, snapshot())
        }

        override fun start(): InputReceiverResult {
            trace += "receiver.start"
            running = true
            return InputReceiverResult(InputReceiverError.None, snapshot())
        }

        override fun waitForInputEvent(timeoutUs: Long): InputReceiverWaitResult =
            events.poll(timeoutUs, TimeUnit.MICROSECONDS) ?: InputReceiverWaitResult.Timeout

        override fun interrupt(): InputReceiverResult {
            trace += "receiver.interrupt"
            events.offer(InputReceiverWaitResult.Interrupted)
            return InputReceiverResult(InputReceiverError.None, snapshot())
        }

        override fun wake(): InputReceiverResult {
            events.offer(
                InputReceiverWaitResult.Dropped(
                    io.warpnect.input.transport.InputReceiverWaitStatus.UnexpectedEndpointDropped,
                ),
            )
            return InputReceiverResult(InputReceiverError.None, snapshot())
        }

        override fun stop(): InputReceiverResult {
            trace += "receiver.stop"
            running = false
            return InputReceiverResult(InputReceiverError.None, snapshot())
        }

        override fun snapshot(): InputReceiverSnapshot = InputReceiverSnapshot()

        override fun close() = Unit
    }

    private class FakeMapper(
        private val trace: MutableList<String>,
    ) : TargetInputMapper {
        val mapped = CountDownLatch(1)
        val resetObserved = CountDownLatch(1)
        var mapCalls = 0
        var resetCalls = 0

        override fun mapAndInject(
            sourceEventTimeUs: Long,
            event: io.warpnect.input.model.WarpnectInputEvent,
        ): AndroidTargetInputMappingResult {
            mapCalls += 1
            mapped.countDown()
            return AndroidTargetInputMappingResult(AndroidTargetInputMappingOutcome.Mapped, 1)
        }

        override fun reset(): AndroidTargetInputMappingResult {
            trace += "mapper.reset"
            resetCalls += 1
            resetObserved.countDown()
            return AndroidTargetInputMappingResult(AndroidTargetInputMappingOutcome.Mapped, 1)
        }

        override fun snapshot(): AndroidTargetInputMappingSnapshot = AndroidTargetInputMappingSnapshot()

        override fun close() = Unit
    }

    private class FakeInjection(
        private val trace: MutableList<String>,
    ) : InputInjectionController {
        override suspend fun queryCapabilities(): InputInjectionCapabilities = InputInjectionCapabilities()

        override suspend fun requestPermission(): InputInjectionPermissionResult =
            InputInjectionPermissionResult(InputInjectionError.None)

        override suspend fun prepare(config: InputInjectionConfig): InputInjectionResult {
            trace += "injection.prepare"
            return result(InputInjectionServiceResult.Prepared)
        }

        override fun start(): InputInjectionResult {
            trace += "injection.start"
            return result(InputInjectionServiceResult.SubmittedAsync)
        }

        override fun injectKey(event: AndroidKeyInjectionEvent): InputInjectionResult = result()

        override fun injectTouch(event: AndroidTouchInjectionEvent): InputInjectionResult = result()

        override fun injectPointer(event: AndroidPointerInjectionEvent): InputInjectionResult = result()

        override fun injectJoystick(event: AndroidJoystickInjectionEvent): InputInjectionResult = result()

        override fun resetState(
            scope: InputResetScope,
            stateSlot: Int,
            reason: InputResetReason,
        ): InputInjectionResult = result(InputInjectionServiceResult.ResetComplete)

        override fun stop(): InputInjectionResult {
            trace += "injection.stop"
            return result(InputInjectionServiceResult.ResetComplete)
        }

        override fun snapshot(): InputInjectionSnapshot = InputInjectionSnapshot()

        override fun close() = Unit

        private fun result(
            serviceResult: InputInjectionServiceResult = InputInjectionServiceResult.SubmittedAsync,
        ): InputInjectionResult {
            return InputInjectionResult(serviceResult, InputInjectionSnapshot())
        }
    }
}
