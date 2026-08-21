package io.warpnect.input.transport

import io.warpnect.input.capture.InputEventSink
import io.warpnect.input.capture.InputSinkResult
import io.warpnect.input.model.InputResetState
import io.warpnect.input.model.WarpnectInputEvent
import io.warpnect.input.performance.BoundedInputTimingHistogram
import io.warpnect.input.performance.InputTimingDistribution
import io.warpnect.input.reliability.InputPerformanceProfile
import io.warpnect.input.reliability.InputReliabilityClass
import io.warpnect.input.reliability.InputReliabilityClassifier
import io.warpnect.input.reliability.InputReliabilityClassifierSnapshot
import io.warpnect.input.reliability.InputReliabilityConfig
import io.warpnect.telemetry.InputSenderTelemetry

data class InputSenderReliabilitySnapshot(
    val profile: InputPerformanceProfile = InputPerformanceProfile.BestEffortBaseline,
    val eventsReceived: Long = 0L,
    val datagramAttempts: Long = 0L,
    val datagramsSent: Long = 0L,
    val eventsWithPartialRedundancy: Long = 0L,
    val eventsRejected: Long = 0L,
    val freshSnapshotEvents: Long = 0L,
    val incrementalDeltaEvents: Long = 0L,
    val criticalTransitionEvents: Long = 0L,
    val resetEvents: Long = 0L,
    val classifier: InputReliabilityClassifierSnapshot = InputReliabilityClassifierSnapshot(),
    val submissionTiming: InputTimingDistribution = InputTimingDistribution(),
    val lastTransportError: InputTransportError = InputTransportError.None,
)

class SclInputEventSink(
    private val transport: InputTransportController,
    private val reliabilityConfig: InputReliabilityConfig = InputReliabilityConfig.bestEffortBaseline(),
    private val telemetry: InputSenderTelemetry? = null,
) : InputEventSink {
    private val classifier = InputReliabilityClassifier(reliabilityConfig)
    private val submissionTiming = BoundedInputTimingHistogram()
    private var snapshot = InputSenderReliabilitySnapshot(profile = reliabilityConfig.profile)

    override fun onInputEvent(eventTimeUs: Long, event: WarpnectInputEvent): InputSinkResult {
        val startedAtNs = System.nanoTime()
        try {
            val classification = classifier.classify(event)
            if (!classification.capacityAvailable) {
                snapshot = snapshot.copy(
                    eventsReceived = snapshot.eventsReceived + 1L,
                    eventsRejected = snapshot.eventsRejected + 1L,
                    classifier = classifier.snapshot(),
                    submissionTiming = submissionTiming.snapshot(),
                    lastTransportError = InputTransportError.InvalidConfiguration,
                )
                return InputSinkResult.Rejected("Input reliability state capacity exhausted")
            }

            val copies = reliabilityConfig.copyCountFor(classification.reliabilityClass)
            var successes = 0
            var lastError = InputTransportError.None
            repeat(copies) {
                val result = transport.submit(eventTimeUs, event)
                if (result.isSuccess) {
                    successes += 1
                } else {
                    lastError = result.error
                }
            }
            classifier.completeSubmission(successes > 0)
            snapshot = snapshot.copy(
                eventsReceived = snapshot.eventsReceived + 1L,
                datagramAttempts = snapshot.datagramAttempts + copies.toLong(),
                datagramsSent = snapshot.datagramsSent + successes.toLong(),
                eventsWithPartialRedundancy = snapshot.eventsWithPartialRedundancy +
                    if (successes in 1 until copies) 1L else 0L,
                eventsRejected = snapshot.eventsRejected + if (successes == 0) 1L else 0L,
                freshSnapshotEvents = snapshot.freshSnapshotEvents +
                    if (classification.reliabilityClass == InputReliabilityClass.FreshSnapshot) 1L else 0L,
                incrementalDeltaEvents = snapshot.incrementalDeltaEvents +
                    if (classification.reliabilityClass == InputReliabilityClass.IncrementalDelta) 1L else 0L,
                criticalTransitionEvents = snapshot.criticalTransitionEvents +
                    if (classification.reliabilityClass == InputReliabilityClass.CriticalTransition) 1L else 0L,
                resetEvents = snapshot.resetEvents +
                    if (classification.reliabilityClass == InputReliabilityClass.Reset) 1L else 0L,
                classifier = classifier.snapshot(),
                submissionTiming = submissionTiming.snapshot(),
                lastTransportError = if (successes == 0) lastError else InputTransportError.None,
            )
            return if (successes > 0) {
                telemetry?.acceptedEvents?.increment()
                if (event is InputResetState) telemetry?.resetsEmitted?.increment()
                InputSinkResult.Accepted
            } else {
                InputSinkResult.Rejected("Input transport $lastError")
            }
        } finally {
            submissionTiming.recordElapsedNs(System.nanoTime() - startedAtNs)
        }
    }

    fun snapshot(): InputSenderReliabilitySnapshot = snapshot.copy(submissionTiming = submissionTiming.snapshot())
}
