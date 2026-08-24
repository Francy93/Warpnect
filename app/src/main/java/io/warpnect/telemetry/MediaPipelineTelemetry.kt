package io.warpnect.telemetry

import io.warpnect.NativeBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Pre-bound media/input telemetry handles. These are created while a negotiated pipeline is
 * constructed, never from a codec, audio, or input callback.
 */
class VideoEncoderTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val accessUnits = source.counter(TelemetryMetricIds.VideoEncoderAccessUnitOutput)
    val bytes = source.counter(TelemetryMetricIds.VideoEncoderByteOutput)
    val keyframes = source.counter(TelemetryMetricIds.VideoEncoderKeyframeOutput)
    val accessUnitSize = source.histogram(TelemetryMetricIds.VideoEncoderAccessUnitSize)
    val outputFormatChanges = source.counter(TelemetryMetricIds.VideoEncoderOutputFormatChange)
    val errors = source.counter(TelemetryMetricIds.VideoEncoderError)
    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            VideoEncoderTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.VideoEncoderAccessUnitOutput,
                TelemetryMetricIds.VideoEncoderByteOutput,
                TelemetryMetricIds.VideoEncoderKeyframeOutput,
                TelemetryMetricIds.VideoEncoderAccessUnitSize,
                TelemetryMetricIds.VideoEncoderOutputFormatChange,
                TelemetryMetricIds.VideoEncoderError,
            )
    }
}

class VideoDecoderTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val accessUnits = source.counter(TelemetryMetricIds.VideoDecoderAccessUnitInput)
    val outputFrames = source.counter(TelemetryMetricIds.VideoDecoderFrameOutput)
    val releasedToSurface = source.counter(TelemetryMetricIds.VideoDecoderFrameReleasedToSurface)
    val droppedByPolicy = source.counter(TelemetryMetricIds.VideoDecoderFrameDroppedByPolicy)
    val renderNotifications = source.counter(TelemetryMetricIds.VideoDecoderRenderNotification)
    val outputFormatChanges = source.counter(TelemetryMetricIds.VideoDecoderOutputFormatChange)
    val errors = source.counter(TelemetryMetricIds.VideoDecoderError)
    val resyncRequested = source.counter(TelemetryMetricIds.VideoResyncRequested)
    private val decoderInputToOutput = source.histogram(TelemetryMetricIds.VideoDecoderInputToOutput)
    private val decoderOutputToRelease = source.histogram(TelemetryMetricIds.VideoDecoderOutputToRelease)
    private val releaseToRender = source.histogram(TelemetryMetricIds.VideoReleaseToRender)
    private val correlationUnmatched = source.counter(TelemetryMetricIds.VideoCorrelationUnmatched)
    private val correlationExpired = source.counter(TelemetryMetricIds.VideoCorrelationExpired)
    private val traceStarted = source.counter(TelemetryMetricIds.LatencyTraceStarted)
    private val traceCompleted = source.counter(TelemetryMetricIds.LatencyTraceCompleted)
    private val traceExpired = source.counter(TelemetryMetricIds.LatencyTraceExpired)
    private val traceCapacityRejected = source.counter(TelemetryMetricIds.LatencyTraceCapacityRejected)
    private val traceUnmatched = source.counter(TelemetryMetricIds.LatencyTraceUnmatched)
    private val traceInvalidDuration = source.counter(TelemetryMetricIds.LatencyTraceInvalidDuration)
    private val trace = LatencyCorrelationTable()

    /** All calls use Android MONOTONIC; render uses MediaCodec's supplied render timestamp. */
    fun decoderInput(presentationTimeUs: Long, nowNs: Long) {
        recordOutcome(trace.start(presentationTimeUs, nowNs))
    }

    fun decoderOutput(presentationTimeUs: Long, nowNs: Long) {
        val outcome = trace.markSecond(presentationTimeUs, nowNs)
        recordOutcome(outcome)
        if (outcome == LatencyCorrelationOutcome.Matched) {
            trace.first(presentationTimeUs)?.let { recordDuration(it, nowNs, decoderInputToOutput) }
        }
    }

    fun surfaceReleased(presentationTimeUs: Long, nowNs: Long) {
        val outcome = trace.markThird(presentationTimeUs, nowNs)
        recordOutcome(outcome)
        if (outcome == LatencyCorrelationOutcome.Matched) {
            trace.second(presentationTimeUs)?.let { recordDuration(it, nowNs, decoderOutputToRelease) }
        }
    }

    fun frameRendered(presentationTimeUs: Long, renderNs: Long) {
        val releaseNs = trace.thirdAt(presentationTimeUs, renderNs)
        val outcome = trace.complete(presentationTimeUs, renderNs)
        if (outcome == LatencyCorrelationOutcome.Completed && releaseNs != null) {
            releaseToRender.record(((renderNs - releaseNs) / 1_000L).toULong())
        }
        recordOutcome(outcome)
    }

    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            VideoDecoderTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.VideoDecoderAccessUnitInput,
                TelemetryMetricIds.VideoDecoderFrameOutput,
                TelemetryMetricIds.VideoDecoderFrameReleasedToSurface,
                TelemetryMetricIds.VideoDecoderFrameDroppedByPolicy,
                TelemetryMetricIds.VideoDecoderRenderNotification,
                TelemetryMetricIds.VideoDecoderOutputFormatChange,
                TelemetryMetricIds.VideoDecoderError,
                TelemetryMetricIds.VideoResyncRequested,
                TelemetryMetricIds.VideoDecoderInputToOutput,
                TelemetryMetricIds.VideoDecoderOutputToRelease,
                TelemetryMetricIds.VideoReleaseToRender,
                TelemetryMetricIds.VideoCorrelationUnmatched,
                TelemetryMetricIds.VideoCorrelationExpired,
                TelemetryMetricIds.LatencyTraceStarted,
                TelemetryMetricIds.LatencyTraceCompleted,
                TelemetryMetricIds.LatencyTraceExpired,
                TelemetryMetricIds.LatencyTraceCapacityRejected,
                TelemetryMetricIds.LatencyTraceUnmatched,
                TelemetryMetricIds.LatencyTraceInvalidDuration,
            )
    }

    private fun recordOutcome(outcome: LatencyCorrelationOutcome) {
        var expired = trace.drainExpiredCount()
        while (expired-- > 0) {
            correlationExpired.increment()
            traceExpired.increment()
        }
        when (outcome) {
            LatencyCorrelationOutcome.Started -> traceStarted.increment()
            LatencyCorrelationOutcome.Completed -> traceCompleted.increment()
            LatencyCorrelationOutcome.Unmatched -> {
                correlationUnmatched.increment()
                traceUnmatched.increment()
            }
            LatencyCorrelationOutcome.CapacityRejected -> traceCapacityRejected.increment()
            LatencyCorrelationOutcome.InvalidDuration -> traceInvalidDuration.increment()
            LatencyCorrelationOutcome.Matched -> Unit
        }
    }

    private fun recordDuration(startNs: Long, endNs: Long, target: TelemetryHistogramHandle) {
        if (endNs < startNs) {
            traceInvalidDuration.increment()
            return
        }
        target.record(((endNs - startNs) / 1_000L).toULong())
    }
}

class AudioSenderTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val capturedSamples = source.counter(TelemetryMetricIds.AudioCaptureSample)
    val captureOverruns = source.counter(TelemetryMetricIds.AudioCaptureRingOverrun)
    val encodedFrames = source.counter(TelemetryMetricIds.AudioEncoderFrameOutput)
    val encodedBytes = source.counter(TelemetryMetricIds.AudioEncoderByteOutput)
    val encodedFrameSize = source.histogram(TelemetryMetricIds.AudioEncoderFrameSize)
    val encoderErrors = source.counter(TelemetryMetricIds.AudioEncoderError)
    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            AudioSenderTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.AudioCaptureSample,
                TelemetryMetricIds.AudioCaptureRingOverrun,
                TelemetryMetricIds.AudioEncoderFrameOutput,
                TelemetryMetricIds.AudioEncoderByteOutput,
                TelemetryMetricIds.AudioEncoderFrameSize,
                TelemetryMetricIds.AudioEncoderError,
            )
    }
}

class AudioReceiverTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val decodedFrames = source.counter(TelemetryMetricIds.AudioDecoderFrameInput)
    val decodedSamples = source.counter(TelemetryMetricIds.AudioDecoderSampleOutput)
    val plcFrames = source.counter(TelemetryMetricIds.AudioDecoderPlcFrame)
    val decoderErrors = source.counter(TelemetryMetricIds.AudioDecoderError)
    private val decoderInputToOutput = source.histogram(TelemetryMetricIds.AudioDecoderInputToOutput)

    fun recordDecoderInputToOutput(firstFramePosition: Long, startNs: Long, endNs: Long) {
        if ((firstFramePosition and 7L) != 0L || startNs < 0L || endNs < startNs) return
        decoderInputToOutput.record(((endNs - startNs) / 1_000L).toULong())
    }
    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            AudioReceiverTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.AudioDecoderFrameInput,
                TelemetryMetricIds.AudioDecoderSampleOutput,
                TelemetryMetricIds.AudioDecoderPlcFrame,
                TelemetryMetricIds.AudioDecoderError,
                TelemetryMetricIds.AudioDecoderInputToOutput,
            )
    }
}

/** Native Oboe callback metrics are collected through the existing batched WNTM provider. */
class NativeAudioPlaybackTelemetry private constructor(
    private val source: TelemetrySource,
    val sourceId: TelemetrySourceId?,
) : AutoCloseable {
    override fun close() {
        sourceId?.let {
            NativeTelemetrySourceScopes.remove(it)
            runCatching { NativeBridge.runtimeTelemetryUnregisterSource(it.value.toLong()) }
        }
        source.close()
    }

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope): NativeAudioPlaybackTelemetry {
            val source = hub.registerSource(TelemetrySourceDefinition(scope, emptyList())).source
            val id = source.sourceId ?: return NativeAudioPlaybackTelemetry(source, null)
            val registered = runCatching {
                NativeBridge.runtimeTelemetryRegisterSource(
                    sourceId = id.value.toLong(),
                    metricIds = shortArrayOf(0x0420, 0x0421, 0x0422, 0x0423, 0x0424, 0x0643, 0x0644),
                    metricKinds = byteArrayOf(1, 1, 1, 1, 2, 2, 1),
                )
            }.getOrDefault(false)
            return if (registered) {
                NativeTelemetrySourceScopes.put(id, scope)
                NativeAudioPlaybackTelemetry(source, id)
            } else {
                source.close()
                NativeAudioPlaybackTelemetry(DisabledNativeTelemetrySource, null)
            }
        }
    }
}

/** Native receiver ClockSync observations are batched with the existing WNTM provider. */
class NativeClockSyncTelemetry private constructor(
    private val source: TelemetrySource,
    val sourceId: TelemetrySourceId?,
) : AutoCloseable {
    override fun close() {
        sourceId?.let {
            NativeTelemetrySourceScopes.remove(it)
            runCatching { NativeBridge.runtimeTelemetryUnregisterSource(it.value.toLong()) }
        }
        source.close()
    }

    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope.Channel): NativeClockSyncTelemetry {
            val source = hub.registerSource(TelemetrySourceDefinition(scope, emptyList())).source
            val id = source.sourceId ?: return NativeClockSyncTelemetry(source, null)
            val registered = runCatching {
                NativeBridge.runtimeTelemetryRegisterSource(
                    sourceId = id.value.toLong(),
                    metricIds = shortArrayOf(0x0601, 0x0602, 0x0603, 0x0604, 0x0606),
                    metricKinds = byteArrayOf(1, 1, 2, 2, 3),
                )
            }.getOrDefault(false)
            return if (registered) {
                NativeTelemetrySourceScopes.put(id, scope)
                NativeClockSyncTelemetry(source, id)
            } else {
                source.close()
                NativeClockSyncTelemetry(DisabledNativeTelemetrySource, null)
            }
        }
    }
}

/** Scope lookup is cold-path only, invoked by the one native WNTM batch parser. */
object NativeTelemetrySourceScopes {
    private val scopes = ConcurrentHashMap<UInt, TelemetryScope>()
    fun put(id: TelemetrySourceId, scope: TelemetryScope) {
        scopes[id.value] = scope
    }
    fun remove(id: TelemetrySourceId) {
        scopes.remove(id.value)
    }
    fun scopeFor(sourceId: UInt): TelemetryScope? = scopes[sourceId]
}

internal object DisabledNativeTelemetrySource : TelemetrySource {
    override val sourceId: TelemetrySourceId? = null
    override val enabled = false
    override fun counter(id: TelemetryMetricId) = DisabledTelemetryCounter
    override fun gauge(id: TelemetryMetricId) = DisabledTelemetryGauge
    override fun histogram(id: TelemetryMetricId) = DisabledTelemetryHistogram
    override fun close() = Unit
}

class InputSenderTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val capturedEvents = source.counter(TelemetryMetricIds.InputCaptureEvent)
    val deviceAdded = source.counter(TelemetryMetricIds.InputCaptureDeviceAdded)
    val deviceRemoved = source.counter(TelemetryMetricIds.InputCaptureDeviceRemoved)
    val mappingDropped = source.counter(TelemetryMetricIds.InputMappingDropped)
    val acceptedEvents = source.counter(TelemetryMetricIds.InputSenderEventAccepted)
    val resetsEmitted = source.counter(TelemetryMetricIds.InputSenderResetEmitted)
    private val captureToSender = source.histogram(TelemetryMetricIds.InputCaptureToSender)

    fun recordCaptureToSender(eventTimeUs: Long, acceptedAtUs: Long) {
        if ((eventTimeUs and 3L) != 0L || acceptedAtUs < eventTimeUs) return
        captureToSender.record((acceptedAtUs - eventTimeUs).toULong())
    }
    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            InputSenderTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.InputCaptureEvent,
                TelemetryMetricIds.InputCaptureDeviceAdded,
                TelemetryMetricIds.InputCaptureDeviceRemoved,
                TelemetryMetricIds.InputMappingDropped,
                TelemetryMetricIds.InputSenderEventAccepted,
                TelemetryMetricIds.InputSenderResetEmitted,
                TelemetryMetricIds.InputCaptureToSender,
            )
    }
}

class InputReceiverTelemetry private constructor(private val source: TelemetrySource) : AutoCloseable {
    val mappingDropped = source.counter(TelemetryMetricIds.InputMappingDropped)
    val acceptedEvents = source.counter(TelemetryMetricIds.InputReceiverEventAccepted)
    val semanticDuplicates = source.counter(TelemetryMetricIds.InputReceiverSemanticDuplicate)
    val resetsApplied = source.counter(TelemetryMetricIds.InputReceiverResetApplied)
    val injectionAttempted = source.counter(TelemetryMetricIds.InputInjectionEventAttempted)
    val injectionFailed = source.counter(TelemetryMetricIds.InputInjectionEventFailed)
    override fun close() = source.close()
    companion object {
        fun register(hub: TelemetryHub, scope: TelemetryScope) =
            InputReceiverTelemetry(hub.registerSource(TelemetrySourceDefinition(scope, ids)).source)
        private val ids =
            listOf(
                TelemetryMetricIds.InputMappingDropped,
                TelemetryMetricIds.InputReceiverEventAccepted,
                TelemetryMetricIds.InputReceiverSemanticDuplicate,
                TelemetryMetricIds.InputReceiverResetApplied,
                TelemetryMetricIds.InputInjectionEventAttempted,
                TelemetryMetricIds.InputInjectionEventFailed,
            )
    }
}
