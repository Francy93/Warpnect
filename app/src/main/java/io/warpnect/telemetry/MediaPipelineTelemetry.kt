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
            )
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
                    metricIds = shortArrayOf(0x0420, 0x0421, 0x0422, 0x0423, 0x0424),
                    metricKinds = byteArrayOf(1, 1, 1, 1, 2),
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

private object DisabledNativeTelemetrySource : TelemetrySource {
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
