package io.warpnect.input.session

import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.performance.BoundedInputTimingHistogram
import io.warpnect.input.reliability.InputConvergenceDispatchResult
import io.warpnect.input.reliability.InputConvergenceSink
import io.warpnect.input.reliability.InputEventEnvelope
import io.warpnect.input.reliability.InputStateConvergenceController
import io.warpnect.input.transport.InputReceiverError
import io.warpnect.input.transport.InputReceiverRuntime
import io.warpnect.input.transport.InputReceiverWaitResult
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingOutcome
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingResult
import io.warpnect.platform.input.mapping.TargetInputMapper
import java.util.concurrent.atomic.AtomicBoolean

/** One persistent receive context owns native waiting, mapping, and privileged injection. */
class ReverseInputReceiverSessionController(
    private val receiverRuntime: InputReceiverRuntime,
    private val targetMapper: TargetInputMapper,
    private val injectionController: InputInjectionController,
) : AutoCloseable {
    private val acceptingEvents = AtomicBoolean(false)
    private val emergencyResetRequested = AtomicBoolean(false)

    @Volatile
    private var receiverThread: Thread? = null

    @Volatile
    private var snapshot = ReverseInputReceiverSessionSnapshot()
    private var convergence: InputStateConvergenceController? = null
    private val convergenceAndDispatchTiming = BoundedInputTimingHistogram()
    private val mapperAndInjectionTiming = BoundedInputTimingHistogram()
    private var closed = false

    suspend fun start(config: ReverseInputReceiverSessionConfig): ReverseInputReceiverSessionResult {
        if (closed) return result(ReverseInputSessionError.Closed)
        if (!config.isValid()) return fail(ReverseInputSessionError.InvalidConfiguration)
        if (snapshot.state == ReverseInputSessionState.Running) return result(ReverseInputSessionError.None)
        snapshot = snapshot.copy(state = ReverseInputSessionState.Starting, lastError = ReverseInputSessionError.None)

        val preparedInjection = injectionController.prepare(config.injectionConfig.copy(resetAllOnStop = false))
        if (!preparedInjection.isSuccess) return fail(ReverseInputSessionError.InjectionPrepareFailed)
        val startedInjection = injectionController.start()
        if (!startedInjection.isSuccess) {
            injectionController.stop()
            return fail(ReverseInputSessionError.InjectionStartFailed)
        }
        val preparedReceiver = receiverRuntime.prepare(config.receiverConfig)
        if (!preparedReceiver.isSuccess) {
            targetMapper.reset()
            injectionController.stop()
            return fail(ReverseInputSessionError.ReceiverPrepareFailed)
        }
        val startedReceiver = receiverRuntime.start()
        if (!startedReceiver.isSuccess) {
            receiverRuntime.stop()
            targetMapper.reset()
            injectionController.stop()
            return fail(ReverseInputSessionError.ReceiverStartFailed)
        }
        convergence = InputStateConvergenceController(config.reliabilityConfig)
        convergenceAndDispatchTiming.clear()
        mapperAndInjectionTiming.clear()
        acceptingEvents.set(true)
        receiverThread = Thread(
            { receiveLoop(config.receiverWaitTimeoutUs) },
            "WarpnectInputReceiver",
        ).also { it.start() }
        snapshot = snapshot.copy(state = ReverseInputSessionState.Running, lastError = ReverseInputSessionError.None)
        return result(ReverseInputSessionError.None)
    }

    fun requestEmergencyReset(): ReverseInputReceiverSessionResult {
        if (snapshot.state != ReverseInputSessionState.Running) {
            return result(ReverseInputSessionError.ReceiverFailure)
        }
        emergencyResetRequested.set(true)
        receiverRuntime.wake()
        snapshot = snapshot.copy(emergencyResetRequests = snapshot.emergencyResetRequests + 1L)
        return result(ReverseInputSessionError.None)
    }

    fun stop(): ReverseInputReceiverSessionResult {
        if (closed) return result(ReverseInputSessionError.Closed)
        if (snapshot.state == ReverseInputSessionState.Stopped) return result(ReverseInputSessionError.None)
        snapshot = snapshot.copy(state = ReverseInputSessionState.Stopping)
        acceptingEvents.set(false)
        receiverRuntime.interrupt()
        receiverThread?.join()
        receiverThread = null
        val reset = targetMapper.reset()
        val resetSucceeded = reset.isSuccess
        if (resetSucceeded) convergence?.onLocalResetSucceeded()
        val convergenceSnapshot = convergence?.snapshot() ?: snapshot.convergence
        convergence?.close()
        convergence = null
        injectionController.stop()
        receiverRuntime.stop()
        snapshot = snapshot.copy(
            state = ReverseInputSessionState.Stopped,
            finalResetAttempted = true,
            finalResetSucceeded = resetSucceeded,
            convergence = convergenceSnapshot,
            lastError = if (resetSucceeded) {
                ReverseInputSessionError.None
            } else {
                ReverseInputSessionError.ResetInjectionFailure
            },
        )
        return result(snapshot.lastError)
    }

    fun snapshot(): ReverseInputReceiverSessionSnapshot = snapshot.copy(
        receiver = receiverRuntime.snapshot(),
        mapper = targetMapper.snapshot(),
        injection = injectionController.snapshot(),
        convergence = convergence?.snapshot() ?: snapshot.convergence,
        convergenceAndDispatchTiming = convergenceAndDispatchTiming.snapshot(),
        mapperAndInjectionTiming = mapperAndInjectionTiming.snapshot(),
    )

    override fun close() {
        if (closed) return
        stop()
        convergence?.close()
        convergence = null
        receiverRuntime.close()
        targetMapper.close()
        injectionController.close()
        closed = true
        snapshot = snapshot.copy(state = ReverseInputSessionState.Closed, lastError = ReverseInputSessionError.Closed)
    }

    private fun receiveLoop(timeoutUs: Long) {
        while (acceptingEvents.get()) {
            val received = receiverRuntime.waitForInputEvent(timeoutUs)
            if (emergencyResetRequested.getAndSet(false)) {
                val reset = targetMapper.reset()
                if (reset.isSuccess) convergence?.onLocalResetSucceeded()
                snapshot = snapshot.copy(
                    emergencyResetsCompleted = snapshot.emergencyResetsCompleted +
                        if (reset.isSuccess) 1L else 0L,
                    mappingFailures = snapshot.mappingFailures + if (reset.isSuccess) 0L else 1L,
                    lastError = if (reset.isSuccess) {
                        ReverseInputSessionError.None
                    } else {
                        ReverseInputSessionError.ResetInjectionFailure
                    },
                )
                continue
            }
            when (received) {
                is InputReceiverWaitResult.EventReady -> {
                    var mapped: AndroidTargetInputMappingResult? = null
                    val startedAtNs = System.nanoTime()
                    val convergenceResult = try {
                        convergence?.process(
                            InputEventEnvelope(
                                sequenceNumber = received.event.sequenceNumber,
                                sourceEventTimeUs = received.event.sourceEventTimeUs,
                                event = received.event.event,
                            ),
                            InputConvergenceSink { candidate ->
                                val mappingStartedAtNs = System.nanoTime()
                                try {
                                    targetMapper.mapAndInject(candidate.sourceEventTimeUs, candidate.event).also {
                                        mapped = it
                                    }.let { InputConvergenceDispatchResult(it.isSuccess) }
                                } finally {
                                    mapperAndInjectionTiming.recordElapsedNs(
                                        System.nanoTime() - mappingStartedAtNs,
                                    )
                                }
                            },
                        )
                    } finally {
                        convergenceAndDispatchTiming.recordElapsedNs(System.nanoTime() - startedAtNs)
                    }
                    if (convergenceResult == null || !convergenceResult.isSuccess) {
                        val mappingResult = mapped
                        val mappingOutcome = mappingResult?.outcome
                        val sessionError = if (
                            mappingOutcome == AndroidTargetInputMappingOutcome.InjectionFailure ||
                            mappingOutcome == AndroidTargetInputMappingOutcome.ResetInjectionFailure
                        ) {
                            ReverseInputSessionError.InjectionFailure
                        } else {
                            ReverseInputSessionError.MappingFailure
                        }
                        snapshot = snapshot.copy(
                            mappingFailures = snapshot.mappingFailures + 1L,
                            injectionFailures = snapshot.injectionFailures +
                                if (mappingResult?.injectionError?.let {
                                        it != InputInjectionError.None
                                    } == true
                                ) {
                                    1L
                                } else {
                                    0L
                                },
                            lastError = sessionError,
                        )
                        if (mappingResult?.injectionError?.isFatalInputSessionFailure() == true) {
                            val reset = targetMapper.reset()
                            if (reset.isSuccess) convergence?.onLocalResetSucceeded()
                            acceptingEvents.set(false)
                            snapshot = snapshot.copy(state = ReverseInputSessionState.Error)
                        }
                    } else {
                        snapshot = snapshot.copy(
                            receivedEvents = snapshot.receivedEvents + 1L,
                            lastError = ReverseInputSessionError.None,
                        )
                    }
                }
                is InputReceiverWaitResult.Dropped -> {
                    snapshot = snapshot.copy(droppedDatagrams = snapshot.droppedDatagrams + 1L)
                }
                is InputReceiverWaitResult.Failure -> {
                    if (received.error != InputReceiverError.None) {
                        val reset = targetMapper.reset()
                        if (reset.isSuccess) convergence?.onLocalResetSucceeded()
                        acceptingEvents.set(false)
                        snapshot = snapshot.copy(
                            state = ReverseInputSessionState.Error,
                            lastError = ReverseInputSessionError.ReceiverFailure,
                        )
                    }
                }
                InputReceiverWaitResult.Interrupted -> if (!acceptingEvents.get()) break
                InputReceiverWaitResult.Timeout -> Unit
            }
        }
    }

    private fun InputInjectionError.isFatalInputSessionFailure(): Boolean = when (this) {
        InputInjectionError.InputApiUnavailable,
        InputInjectionError.InjectEventsPermissionDenied,
        InputInjectionError.ServiceUnavailable,
        InputInjectionError.ServiceDied,
        InputInjectionError.RemoteFailure,
        InputInjectionError.Closed,
        InputInjectionError.ShizukuUnavailable,
        InputInjectionError.ShizukuPermissionRequired,
        InputInjectionError.ShizukuPermissionDenied,
        InputInjectionError.UserServiceBindFailed,
        -> true
        else -> false
    }

    private fun fail(error: ReverseInputSessionError): ReverseInputReceiverSessionResult {
        snapshot = snapshot.copy(state = ReverseInputSessionState.Error, lastError = error)
        return result(error)
    }

    private fun result(error: ReverseInputSessionError): ReverseInputReceiverSessionResult =
        ReverseInputReceiverSessionResult(error, snapshot())
}
