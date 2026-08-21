@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import io.warpnect.session.ChannelId
import io.warpnect.session.NetworkPathKind
import io.warpnect.session.PathId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId

/** Local-only schema version. It is not an SCL or network protocol version. */
const val RUNTIME_TELEMETRY_MODEL_VERSION = 1

const val MAX_TELEMETRY_DESCRIPTORS = 512
const val MAX_TELEMETRY_SOURCES = 512
const val MAX_TELEMETRY_METRICS_PER_SOURCE = 32
const val MAX_TELEMETRY_HISTOGRAMS_PER_SOURCE = 8
const val MAX_TELEMETRY_HISTOGRAM_BOUNDARIES = 16
const val MAX_TELEMETRY_SNAPSHOT_PROVIDERS = 32
const val MAX_TELEMETRY_SNAPSHOT_RECORDS = 16_384
const val MAX_NATIVE_TELEMETRY_SNAPSHOT_BYTES = 256 * 1024

@JvmInline
value class TelemetryMetricId(
    val value: Int,
) {
    init {
        require(value in 1..0xffff) { "TelemetryMetricId must be a non-zero u16" }
    }
}

@JvmInline
value class TelemetrySourceId(
    val value: UInt,
) {
    init {
        require(value != 0u) { "TelemetrySourceId must be non-zero" }
    }
}

enum class TelemetryMetricKind(
    val bridgeId: Int,
) {
    CounterU64(1),
    GaugeI64(2),
    HistogramU64(3),
    ;

    companion object {
        fun fromBridgeId(value: Int): TelemetryMetricKind? = entries.firstOrNull { it.bridgeId == value }
    }
}

enum class TelemetryUnit {
    Count,
    Bytes,
    BitsPerSecond,
    Frames,
    Packets,
    Events,
    Nanoseconds,
    Microseconds,
    Milliseconds,
    Samples,
    PercentPermille,
    Boolean,
}

enum class TelemetryScopeKind {
    Process,
    Session,
    Path,
    Channel,
    Component,
}

/** A finite, typed scope set avoids arbitrary runtime label cardinality. */
sealed interface TelemetryScope {
    val kind: TelemetryScopeKind

    data object Process : TelemetryScope {
        override val kind: TelemetryScopeKind = TelemetryScopeKind.Process
    }

    data class Session(
        val sessionId: SessionId,
        val generation: SessionGeneration,
    ) : TelemetryScope {
        override val kind: TelemetryScopeKind = TelemetryScopeKind.Session
    }

    data class Path(
        val sessionId: SessionId,
        val generation: SessionGeneration,
        val pathId: PathId,
        val pathKind: NetworkPathKind,
    ) : TelemetryScope {
        override val kind: TelemetryScopeKind = TelemetryScopeKind.Path
    }

    data class Channel(
        val sessionId: SessionId,
        val generation: SessionGeneration,
        val channelId: ChannelId,
        val channelKind: SessionChannelKind,
        val direction: SessionChannelDirection,
    ) : TelemetryScope {
        override val kind: TelemetryScopeKind = TelemetryScopeKind.Channel
    }

    data class Component(
        val component: TelemetryComponent,
    ) : TelemetryScope {
        override val kind: TelemetryScopeKind = TelemetryScopeKind.Component
    }
}

/** Bounded component identities; user-provided component strings are deliberately unsupported. */
enum class TelemetryComponent {
    VideoEncoder,
    VideoDecoder,
    SystemAudioEncoder,
    SystemAudioDecoder,
    MicrophoneAudioEncoder,
    MicrophoneAudioDecoder,
    AudioPlayback,
    AudioCapture,
    InputCapture,
    InputReceiver,
    InputInjection,
    SessionControl,
    Protection,
    Discovery,
    Pairing,
    Handshake,
    CapabilityNegotiation,
    SessionSetup,
    SessionLifecycle,
}

data class TelemetryMetricDescriptor(
    val id: TelemetryMetricId,
    val canonicalName: String,
    val kind: TelemetryMetricKind,
    val unit: TelemetryUnit,
    val allowedScopes: Set<TelemetryScopeKind>,
    val description: String,
    val histogramBoundaries: ULongArray = ulongArrayOf(),
)

object TelemetryMetricIds {
    val SourceActive = TelemetryMetricId(0x0001)
    val SourceRegistrationRejected = TelemetryMetricId(0x0002)
    val SnapshotCount = TelemetryMetricId(0x0003)
    val SnapshotPartial = TelemetryMetricId(0x0004)
    val SnapshotProviderFailure = TelemetryMetricId(0x0005)
    val UpdateOverflow = TelemetryMetricId(0x0006)
    val SnapshotDuration = TelemetryMetricId(0x0007)

    val VideoEncoderAccessUnitOutput = TelemetryMetricId(0x0301)
    val VideoEncoderByteOutput = TelemetryMetricId(0x0302)
    val VideoEncoderKeyframeOutput = TelemetryMetricId(0x0303)
    val VideoEncoderAccessUnitSize = TelemetryMetricId(0x0304)
    val VideoEncoderOutputFormatChange = TelemetryMetricId(0x0305)
    val VideoEncoderError = TelemetryMetricId(0x0306)
    val VideoDecoderAccessUnitInput = TelemetryMetricId(0x0310)
    val VideoDecoderFrameOutput = TelemetryMetricId(0x0311)
    val VideoDecoderFrameReleasedToSurface = TelemetryMetricId(0x0312)
    val VideoDecoderFrameDroppedByPolicy = TelemetryMetricId(0x0313)
    val VideoDecoderRenderNotification = TelemetryMetricId(0x0314)
    val VideoDecoderOutputFormatChange = TelemetryMetricId(0x0315)
    val VideoDecoderError = TelemetryMetricId(0x0316)
    val VideoResyncRequested = TelemetryMetricId(0x0320)

    val AudioCaptureSample = TelemetryMetricId(0x0401)
    val AudioCaptureRingOverrun = TelemetryMetricId(0x0402)
    val AudioEncoderFrameOutput = TelemetryMetricId(0x0403)
    val AudioEncoderByteOutput = TelemetryMetricId(0x0404)
    val AudioEncoderFrameSize = TelemetryMetricId(0x0405)
    val AudioEncoderError = TelemetryMetricId(0x0406)
    val AudioDecoderFrameInput = TelemetryMetricId(0x0410)
    val AudioDecoderSampleOutput = TelemetryMetricId(0x0411)
    val AudioDecoderPlcFrame = TelemetryMetricId(0x0412)
    val AudioDecoderError = TelemetryMetricId(0x0413)
    val AudioPlaybackCallback = TelemetryMetricId(0x0420)
    val AudioPlaybackSampleRequested = TelemetryMetricId(0x0421)
    val AudioPlaybackSampleDelivered = TelemetryMetricId(0x0422)
    val AudioPlaybackUnderrun = TelemetryMetricId(0x0423)
    val AudioPlaybackRingFill = TelemetryMetricId(0x0424)

    val InputCaptureEvent = TelemetryMetricId(0x0501)
    val InputCaptureDeviceAdded = TelemetryMetricId(0x0502)
    val InputCaptureDeviceRemoved = TelemetryMetricId(0x0503)
    val InputMappingDropped = TelemetryMetricId(0x0504)
    val InputSenderEventAccepted = TelemetryMetricId(0x0510)
    val InputSenderResetEmitted = TelemetryMetricId(0x0511)
    val InputReceiverEventAccepted = TelemetryMetricId(0x0520)
    val InputReceiverSemanticDuplicate = TelemetryMetricId(0x0521)
    val InputReceiverResetApplied = TelemetryMetricId(0x0522)
    val InputInjectionEventAttempted = TelemetryMetricId(0x0530)
    val InputInjectionEventFailed = TelemetryMetricId(0x0531)
}

/** The only concrete descriptors introduced by RFC-006A are telemetry self diagnostics. */
object TelemetryDescriptorCatalog {
    private val processScope = setOf(TelemetryScopeKind.Process)

    val descriptors: List<TelemetryMetricDescriptor> = listOf(
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SourceActive,
            "warpnect.telemetry.source.active",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Count,
            processScope,
            "Currently registered non-framework telemetry sources.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SourceRegistrationRejected,
            "warpnect.telemetry.source.registration_rejected",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            processScope,
            "Source registrations rejected by the bounded registry.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SnapshotCount,
            "warpnect.telemetry.snapshot.count",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            processScope,
            "Snapshot attempts completed by this hub.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SnapshotPartial,
            "warpnect.telemetry.snapshot.partial",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            processScope,
            "Snapshots that omitted a bounded provider or record set.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SnapshotProviderFailure,
            "warpnect.telemetry.snapshot.provider_failure",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            processScope,
            "Cold-path provider collection failures.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.UpdateOverflow,
            "warpnect.telemetry.update.overflow",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            processScope,
            "Counter or histogram sums that saturated at unsigned 64-bit maximum.",
        ),
        TelemetryMetricDescriptor(
            TelemetryMetricIds.SnapshotDuration,
            "warpnect.telemetry.snapshot.duration",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            processScope,
            "Cold-path snapshot collection duration in the hub clock domain.",
            ulongArrayOf(100u, 500u, 1_000u, 5_000u, 10_000u, 50_000u),
        ),
    ) + mediaAndInputDescriptors()

    private fun mediaAndInputDescriptors(): List<TelemetryMetricDescriptor> = listOf(
        descriptor(
            TelemetryMetricIds.VideoEncoderAccessUnitOutput,
            "warpnect.video.encoder.access_unit.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Encoded Video access units handed to the downstream sink.",
        ),
        descriptor(
            TelemetryMetricIds.VideoEncoderByteOutput,
            "warpnect.video.encoder.byte.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Bytes,
            "Encoded Video access-unit payload bytes.",
        ),
        descriptor(
            TelemetryMetricIds.VideoEncoderKeyframeOutput,
            "warpnect.video.encoder.keyframe.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Encoded Video keyframes handed to the downstream sink.",
        ),
        descriptor(
            TelemetryMetricIds.VideoEncoderAccessUnitSize,
            "warpnect.video.encoder.access_unit.size",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Bytes,
            "Encoded Video access-unit payload size.",
            ulongArrayOf(
                512u, 1_024u, 2_048u, 4_096u, 8_192u, 16_384u,
                32_768u, 65_536u, 131_072u, 262_144u, 524_288u, 1_048_576u,
            ),
        ),
        descriptor(
            TelemetryMetricIds.VideoEncoderOutputFormatChange,
            "warpnect.video.encoder.output_format.change",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "MediaCodec encoder output format changes.",
        ),
        descriptor(
            TelemetryMetricIds.VideoEncoderError,
            "warpnect.video.encoder.error",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Aggregate Video encoder failures.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderAccessUnitInput,
            "warpnect.video.decoder.access_unit.input",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Encoded Video access units accepted by the decoder.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderFrameOutput,
            "warpnect.video.decoder.frame.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Video decoder output frames.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderFrameReleasedToSurface,
            "warpnect.video.decoder.frame.released_to_surface",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Decoded frames explicitly released for Surface rendering.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderFrameDroppedByPolicy,
            "warpnect.video.decoder.frame.dropped_by_policy",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Decoded frames intentionally dropped by the render policy.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderRenderNotification,
            "warpnect.video.decoder.render.notification",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Observational Android rendered-frame notifications.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderOutputFormatChange,
            "warpnect.video.decoder.output_format.change",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "MediaCodec decoder output format changes.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderError,
            "warpnect.video.decoder.error",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Aggregate Video decoder failures.",
        ),
        descriptor(
            TelemetryMetricIds.VideoResyncRequested,
            "warpnect.video.resync.requested",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Video resynchronization requests emitted by the receiver.",
        ),
        descriptor(
            TelemetryMetricIds.AudioCaptureSample,
            "warpnect.audio.capture.sample",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Samples,
            "PCM sample frames accepted into the audio pipeline.",
        ),
        descriptor(
            TelemetryMetricIds.AudioCaptureRingOverrun,
            "warpnect.audio.capture.ring.overrun",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Existing PCM capture-ring overrun events.",
        ),
        descriptor(
            TelemetryMetricIds.AudioEncoderFrameOutput,
            "warpnect.audio.encoder.frame.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Encoded Opus frames handed to the downstream sink.",
        ),
        descriptor(
            TelemetryMetricIds.AudioEncoderByteOutput,
            "warpnect.audio.encoder.byte.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Bytes,
            "Encoded Opus payload bytes.",
        ),
        descriptor(
            TelemetryMetricIds.AudioEncoderFrameSize,
            "warpnect.audio.encoder.frame.size",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Bytes,
            "Encoded Opus payload size.",
            ulongArrayOf(32u, 64u, 96u, 128u, 192u, 256u, 384u, 512u, 768u, 1_024u, 1_536u, 2_048u),
        ),
        descriptor(
            TelemetryMetricIds.AudioEncoderError,
            "warpnect.audio.encoder.error",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Aggregate Opus encoder failures.",
        ),
        descriptor(
            TelemetryMetricIds.AudioDecoderFrameInput,
            "warpnect.audio.decoder.frame.input",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Opus frames accepted by the decoder.",
        ),
        descriptor(
            TelemetryMetricIds.AudioDecoderSampleOutput,
            "warpnect.audio.decoder.sample.output",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Samples,
            "Decoded PCM sample frames handed to playback.",
        ),
        descriptor(
            TelemetryMetricIds.AudioDecoderPlcFrame,
            "warpnect.audio.decoder.plc.frame",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Frames,
            "Opus PLC frames generated by the decoder.",
        ),
        descriptor(
            TelemetryMetricIds.AudioDecoderError,
            "warpnect.audio.decoder.error",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Aggregate Opus decoder failures.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackCallback,
            "warpnect.audio.playback.callback",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Audio playback callbacks.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackSampleRequested,
            "warpnect.audio.playback.sample.requested",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Samples,
            "PCM sample frames requested by playback.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackSampleDelivered,
            "warpnect.audio.playback.sample.delivered",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Samples,
            "Fresh PCM sample frames delivered to playback.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackUnderrun,
            "warpnect.audio.playback.underrun",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Playback callbacks using the established underrun behavior.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackRingFill,
            "warpnect.audio.playback.ring.fill",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Samples,
            "Latest playback-ring occupancy in PCM sample frames.",
        ),
        descriptor(
            TelemetryMetricIds.InputCaptureEvent,
            "warpnect.input.capture.event",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Portable input observations accepted by capture.",
        ),
        descriptor(
            TelemetryMetricIds.InputCaptureDeviceAdded,
            "warpnect.input.capture.device.added",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Physical input-device add events.",
        ),
        descriptor(
            TelemetryMetricIds.InputCaptureDeviceRemoved,
            "warpnect.input.capture.device.removed",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Physical input-device removal events.",
        ),
        descriptor(
            TelemetryMetricIds.InputMappingDropped,
            "warpnect.input.mapping.dropped",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Input events intentionally rejected by mapping.",
        ),
        descriptor(
            TelemetryMetricIds.InputSenderEventAccepted,
            "warpnect.input.sender.event.accepted",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Semantic input events accepted by the sender.",
        ),
        descriptor(
            TelemetryMetricIds.InputSenderResetEmitted,
            "warpnect.input.sender.reset.emitted",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Semantic ResetState operations emitted by the sender.",
        ),
        descriptor(
            TelemetryMetricIds.InputReceiverEventAccepted,
            "warpnect.input.receiver.event.accepted",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Semantic input events accepted by the target convergence layer.",
        ),
        descriptor(
            TelemetryMetricIds.InputReceiverSemanticDuplicate,
            "warpnect.input.receiver.semantic_duplicate",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Semantic input duplicates rejected by convergence.",
        ),
        descriptor(
            TelemetryMetricIds.InputReceiverResetApplied,
            "warpnect.input.receiver.reset.applied",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Semantic ResetState applications at the target.",
        ),
        descriptor(
            TelemetryMetricIds.InputInjectionEventAttempted,
            "warpnect.input.injection.event.attempted",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Mapped events reaching privileged injection.",
        ),
        descriptor(
            TelemetryMetricIds.InputInjectionEventFailed,
            "warpnect.input.injection.event.failed",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Events,
            "Failed privileged input-injection attempts.",
        ),
    )

    private fun descriptor(
        id: TelemetryMetricId,
        name: String,
        kind: TelemetryMetricKind,
        unit: TelemetryUnit,
        description: String,
        boundaries: ULongArray = ulongArrayOf(),
    ) = TelemetryMetricDescriptor(
        id = id,
        canonicalName = name,
        kind = kind,
        unit = unit,
        allowedScopes = setOf(TelemetryScopeKind.Channel),
        description = description,
        histogramBoundaries = boundaries,
    )

    private val descriptorsById: Map<TelemetryMetricId, TelemetryMetricDescriptor> = descriptors.associateBy { it.id }

    init {
        validate(descriptors)
    }

    fun descriptor(id: TelemetryMetricId): TelemetryMetricDescriptor? = descriptorsById[id]

    fun validate(candidate: List<TelemetryMetricDescriptor>) {
        require(candidate.size <= MAX_TELEMETRY_DESCRIPTORS) { "Telemetry descriptor bound exceeded" }
        require(candidate.map { it.id }.toSet().size == candidate.size) { "Telemetry MetricIds must be unique" }
        require(candidate.map { it.canonicalName }.toSet().size == candidate.size) {
            "Telemetry canonical names must be unique"
        }
        candidate.forEach { descriptor ->
            require(descriptor.canonicalName.matches(Regex("warpnect(\\.[a-z0-9_]+)+"))) {
                "Invalid telemetry metric name: ${descriptor.canonicalName}"
            }
            require(descriptor.allowedScopes.isNotEmpty()) { "Telemetry descriptor needs an allowed scope" }
            if (descriptor.kind == TelemetryMetricKind.HistogramU64) {
                require(descriptor.histogramBoundaries.size <= MAX_TELEMETRY_HISTOGRAM_BOUNDARIES) {
                    "Histogram boundary bound exceeded"
                }
                descriptor.histogramBoundaries.zipWithNext().forEach { (left, right) ->
                    require(left < right) { "Histogram boundaries must be strictly increasing" }
                }
            } else {
                require(descriptor.histogramBoundaries.isEmpty()) {
                    "Only histogram descriptors may define bucket boundaries"
                }
            }
        }
    }
}
