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

    val SessionHeartbeatSent = TelemetryMetricId(0x0101)
    val SessionHeartbeatAckReceived = TelemetryMetricId(0x0102)
    val SessionHeartbeatMiss = TelemetryMetricId(0x0103)
    val SessionSuspended = TelemetryMetricId(0x0104)
    val SessionPathMigrationStarted = TelemetryMetricId(0x0105)
    val SessionPathMigrationSucceeded = TelemetryMetricId(0x0106)
    val SessionPathMigrationFailed = TelemetryMetricId(0x0107)
    val SessionReconnectAttempt = TelemetryMetricId(0x0108)
    val SessionReconnectSucceeded = TelemetryMetricId(0x0109)
    val SessionReconnectAttemptFailed = TelemetryMetricId(0x010A)
    val SessionReconnectExpired = TelemetryMetricId(0x010B)
    val SessionReconnectCancelled = TelemetryMetricId(0x010C)
    val SessionDisconnectLocal = TelemetryMetricId(0x010D)
    val SessionDisconnectRemote = TelemetryMetricId(0x010E)

    val UdpDatagramSent = TelemetryMetricId(0x0201)
    val UdpByteSent = TelemetryMetricId(0x0202)
    val UdpDatagramReceived = TelemetryMetricId(0x0203)
    val UdpByteReceived = TelemetryMetricId(0x0204)
    val UdpSendWouldBlock = TelemetryMetricId(0x0205)
    val UdpSendError = TelemetryMetricId(0x0206)
    val UdpReceiveError = TelemetryMetricId(0x0207)
    val PathUnavailableDrop = TelemetryMetricId(0x0208)
    val SocketRebind = TelemetryMetricId(0x0209)
    val FecDataShardEmitted = TelemetryMetricId(0x0221)
    val FecParityShardEmitted = TelemetryMetricId(0x0222)
    val FecRecoveryAttempt = TelemetryMetricId(0x0223)
    val FecShardRecovered = TelemetryMetricId(0x0224)
    val FecRecoveryCompleted = TelemetryMetricId(0x0225)
    val FecRecoveryFailed = TelemetryMetricId(0x0226)
    val NackGenerated = TelemetryMetricId(0x0231)
    val NackReceived = TelemetryMetricId(0x0232)
    val RetransmissionSent = TelemetryMetricId(0x0233)
    val RetransmissionCacheMiss = TelemetryMetricId(0x0234)
    val ReassemblyFragmentAccepted = TelemetryMetricId(0x0241)
    val ReassemblyCompleted = TelemetryMetricId(0x0242)
    val ReassemblyTimeout = TelemetryMetricId(0x0243)
    val ReassemblyEvicted = TelemetryMetricId(0x0244)
    val PathActive = TelemetryMetricId(0x0251)
    val PathValidated = TelemetryMetricId(0x0252)
    val PathPlatformAvailable = TelemetryMetricId(0x0253)
    val PathPlatformLosing = TelemetryMetricId(0x0254)
    val PathPlatformLost = TelemetryMetricId(0x0255)
    val PathValidationStarted = TelemetryMetricId(0x0256)
    val PathValidationSucceeded = TelemetryMetricId(0x0257)
    val PathValidationFailed = TelemetryMetricId(0x0258)

    val ProtectionRecordProduced = TelemetryMetricId(0x0701)
    val ProtectionRecordAccepted = TelemetryMetricId(0x0702)
    val ProtectionProtectError = TelemetryMetricId(0x0703)
    val ProtectionAuthenticationFailed = TelemetryMetricId(0x0704)
    val ProtectionReplayDropped = TelemetryMetricId(0x0705)
    val ProtectionUnknownContext = TelemetryMetricId(0x0706)
    val ProtectionEndpointMismatch = TelemetryMetricId(0x0707)
    val ProtectionEpochRejected = TelemetryMetricId(0x0708)
    val ProtectionMalformed = TelemetryMetricId(0x0709)

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

    val ClockSyncSampleAccepted = TelemetryMetricId(0x0601)
    val ClockSyncSampleRejected = TelemetryMetricId(0x0602)
    val ClockSyncQualified = TelemetryMetricId(0x0603)
    val ClockSyncOffset = TelemetryMetricId(0x0604)
    val ClockSyncUncertainty = TelemetryMetricId(0x0605)
    val ClockSyncRoundTrip = TelemetryMetricId(0x0606)
    val ClockSyncMappingRejected = TelemetryMetricId(0x0607)
    val TransportOneWay = TelemetryMetricId(0x0620)
    val TransportSampled = TelemetryMetricId(0x0621)
    val TransportClockUnqualified = TelemetryMetricId(0x0622)
    val TransportInvalid = TelemetryMetricId(0x0623)
    val VideoDecoderInputToOutput = TelemetryMetricId(0x0630)
    val VideoDecoderOutputToRelease = TelemetryMetricId(0x0631)
    val VideoReleaseToRender = TelemetryMetricId(0x0632)
    val VideoSourceToDecoderInput = TelemetryMetricId(0x0633)
    val VideoSourceToRender = TelemetryMetricId(0x0634)
    val VideoCorrelationUnmatched = TelemetryMetricId(0x0635)
    val VideoCorrelationExpired = TelemetryMetricId(0x0636)
    val AudioSourceToDecoderInput = TelemetryMetricId(0x0640)
    val AudioDecoderInputToOutput = TelemetryMetricId(0x0641)
    val AudioOutputToPlaybackCallback = TelemetryMetricId(0x0642)
    val AudioPlaybackOutputEstimate = TelemetryMetricId(0x0643)
    val AudioPlaybackOutputEstimateFailed = TelemetryMetricId(0x0644)
    val AudioCorrelationUnmatched = TelemetryMetricId(0x0645)
    val AudioCorrelationExpired = TelemetryMetricId(0x0646)
    val InputCaptureToSender = TelemetryMetricId(0x0650)
    val InputSourceToReceiver = TelemetryMetricId(0x0651)
    val InputReceiverToInjection = TelemetryMetricId(0x0652)
    val InputSourceToInjection = TelemetryMetricId(0x0653)
    val InputCorrelationRejected = TelemetryMetricId(0x0654)
    val LatencyTraceStarted = TelemetryMetricId(0x0660)
    val LatencyTraceCompleted = TelemetryMetricId(0x0661)
    val LatencyTraceExpired = TelemetryMetricId(0x0662)
    val LatencyTraceCapacityRejected = TelemetryMetricId(0x0663)
    val LatencyTraceUnmatched = TelemetryMetricId(0x0664)
    val LatencyTraceClockUnqualified = TelemetryMetricId(0x0665)
    val LatencyTraceInvalidDuration = TelemetryMetricId(0x0666)
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
    ) + mediaAndInputDescriptors() + networkAndRecoveryDescriptors() + latencyDescriptors()

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

    private fun networkAndRecoveryDescriptors(): List<TelemetryMetricDescriptor> = listOf(
        sessionDescriptor(
            TelemetryMetricIds.SessionHeartbeatSent,
            "warpnect.session.heartbeat.sent",
            "Semantic RFC-005H Heartbeat messages emitted.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionHeartbeatAckReceived,
            "warpnect.session.heartbeat.ack_received",
            "Accepted authenticated RFC-005H Heartbeat acknowledgements.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionHeartbeatMiss,
            "warpnect.session.heartbeat.miss",
            "Expired RFC-005H heartbeat response intervals.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionSuspended,
            "warpnect.session.suspended",
            "Transitions into the RFC-005H Suspended state.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionPathMigrationStarted,
            "warpnect.session.path_migration.started",
            "Started RFC-005H path migration transactions.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionPathMigrationSucceeded,
            "warpnect.session.path_migration.succeeded",
            "Committed RFC-005H path migration transactions.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionPathMigrationFailed,
            "warpnect.session.path_migration.failed",
            "Terminal failed RFC-005H path migration transactions.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionReconnectAttempt,
            "warpnect.session.reconnect.attempt",
            "Fresh-generation reconnect attempts started under a recovery lease.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionReconnectSucceeded,
            "warpnect.session.reconnect.succeeded",
            "Fresh generations that entered lifecycle reconnected handoff.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionReconnectAttemptFailed,
            "warpnect.session.reconnect.attempt_failed",
            "Reconnect attempts that failed before recovery expiry.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionReconnectExpired,
            "warpnect.session.reconnect.expired",
            "Recovery windows that expired without a reconnect.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionReconnectCancelled,
            "warpnect.session.reconnect.cancelled",
            "Explicitly cancelled recovery leases.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionDisconnectLocal,
            "warpnect.session.disconnect.local",
            "Deliberate local lifecycle disconnects.",
        ),
        sessionDescriptor(
            TelemetryMetricIds.SessionDisconnectRemote,
            "warpnect.session.disconnect.remote",
            "Accepted authenticated remote DisconnectNotice messages.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpDatagramSent,
            "warpnect.network.udp.datagram.sent",
            TelemetryUnit.Packets,
            "Complete outer UDP datagrams successfully sent.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpByteSent,
            "warpnect.network.udp.byte.sent",
            TelemetryUnit.Bytes,
            "Outer UDP datagram bytes successfully sent.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpDatagramReceived,
            "warpnect.network.udp.datagram.received",
            TelemetryUnit.Packets,
            "Outer UDP datagrams received before filtering and protection.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpByteReceived,
            "warpnect.network.udp.byte.received",
            TelemetryUnit.Bytes,
            "Outer UDP datagram bytes received before filtering and protection.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpSendWouldBlock,
            "warpnect.network.udp.send.would_block",
            TelemetryUnit.Count,
            "Nonblocking UDP sends that returned WouldBlock.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpSendError,
            "warpnect.network.udp.send.error",
            TelemetryUnit.Count,
            "Non-WouldBlock UDP send failures.",
        ),
        networkDescriptor(
            TelemetryMetricIds.UdpReceiveError,
            "warpnect.network.udp.receive.error",
            TelemetryUnit.Count,
            "UDP receive syscall/runtime failures.",
        ),
        networkDescriptor(
            TelemetryMetricIds.PathUnavailableDrop,
            "warpnect.network.path_unavailable.drop",
            TelemetryUnit.Packets,
            "Real-time datagrams intentionally dropped with no active path.",
        ),
        networkDescriptor(
            TelemetryMetricIds.SocketRebind,
            "warpnect.network.socket.rebind",
            TelemetryUnit.Count,
            "Successful live socket endpoint rebinds during same-generation migration.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecDataShardEmitted,
            "warpnect.network.fec.data_shard.emitted",
            TelemetryUnit.Packets,
            "FEC data shards emitted by an FEC-enabled Channel.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecParityShardEmitted,
            "warpnect.network.fec.parity_shard.emitted",
            TelemetryUnit.Packets,
            "FEC parity shards emitted by an FEC-enabled Channel.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecRecoveryAttempt,
            "warpnect.network.fec.recovery.attempt",
            TelemetryUnit.Count,
            "Actual Reed-Solomon recovery attempts.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecShardRecovered,
            "warpnect.network.fec.shard.recovered",
            TelemetryUnit.Packets,
            "Useful data shards reconstructed by FEC.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecRecoveryCompleted,
            "warpnect.network.fec.recovery.completed",
            TelemetryUnit.Count,
            "Completed FEC recovery operations.",
        ),
        channelDescriptor(
            TelemetryMetricIds.FecRecoveryFailed,
            "warpnect.network.fec.recovery.failed",
            TelemetryUnit.Count,
            "Failed Reed-Solomon recovery operations.",
        ),
        channelDescriptor(
            TelemetryMetricIds.NackGenerated,
            "warpnect.network.nack.generated",
            TelemetryUnit.Count,
            "Semantic NACK messages generated by recovery logic.",
        ),
        channelDescriptor(
            TelemetryMetricIds.NackReceived,
            "warpnect.network.nack.received",
            TelemetryUnit.Count,
            "Authenticated NACK messages accepted by recovery logic.",
        ),
        channelDescriptor(
            TelemetryMetricIds.RetransmissionSent,
            "warpnect.network.retransmission.sent",
            TelemetryUnit.Packets,
            "Exact cached protected datagrams successfully retransmitted.",
        ),
        channelDescriptor(
            TelemetryMetricIds.RetransmissionCacheMiss,
            "warpnect.network.retransmission.cache_miss",
            TelemetryUnit.Count,
            "Valid retransmission requests missing from the bounded cache.",
        ),
        channelDescriptor(
            TelemetryMetricIds.ReassemblyFragmentAccepted,
            "warpnect.network.reassembly.fragment.accepted",
            TelemetryUnit.Packets,
            "Fragments accepted into bounded reassembly.",
        ),
        channelDescriptor(
            TelemetryMetricIds.ReassemblyCompleted,
            "warpnect.network.reassembly.completed",
            TelemetryUnit.Count,
            "Fragmented units emitted after reassembly completion.",
        ),
        channelDescriptor(
            TelemetryMetricIds.ReassemblyTimeout,
            "warpnect.network.reassembly.timeout",
            TelemetryUnit.Count,
            "Incomplete reassembly state expired by existing deadlines.",
        ),
        channelDescriptor(
            TelemetryMetricIds.ReassemblyEvicted,
            "warpnect.network.reassembly.evicted",
            TelemetryUnit.Count,
            "Incomplete reassembly state evicted by existing capacity policy.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathActive,
            "warpnect.network.path.active",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Boolean,
            "Whether this represented path is the active Session path.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathValidated,
            "warpnect.network.path.validated",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Boolean,
            "Authenticated Warpnect validation state for this path.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathPlatformAvailable,
            "warpnect.network.path.platform_available",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Local platform/backend path availability hints.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathPlatformLosing,
            "warpnect.network.path.platform_losing",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Advisory local platform path-losing hints.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathPlatformLost,
            "warpnect.network.path.platform_lost",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Local platform/backend hard path-loss hints.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathValidationStarted,
            "warpnect.network.path.validation.started",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Candidate path validation transactions started.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathValidationSucceeded,
            "warpnect.network.path.validation.succeeded",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Authenticated candidate path validations completed.",
        ),
        pathDescriptor(
            TelemetryMetricIds.PathValidationFailed,
            "warpnect.network.path.validation.failed",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Terminal candidate path validation failures.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionRecordProduced,
            "warpnect.security.protection.record.produced",
            "Fresh WNSD records produced with a new security packet number.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionRecordAccepted,
            "warpnect.security.protection.record.accepted",
            "WNSD records accepted after endpoint, epoch, AEAD and replay checks.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionProtectError,
            "warpnect.security.protection.protect_error",
            "Local WNSD protection operation failures.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionAuthenticationFailed,
            "warpnect.security.protection.authentication_failed",
            "WNSD AEAD authentication failures.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionReplayDropped,
            "warpnect.security.protection.replay_dropped",
            "WNSD anti-replay rejections.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionUnknownContext,
            "warpnect.security.protection.unknown_context",
            "WNSD records referencing no local protection context.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionEndpointMismatch,
            "warpnect.security.protection.endpoint_mismatch",
            "WNSD records rejected by expected-endpoint filtering.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionEpochRejected,
            "warpnect.security.protection.epoch_rejected",
            "WNSD records rejected for invalid or unsupported epochs.",
        ),
        securityDescriptor(
            TelemetryMetricIds.ProtectionMalformed,
            "warpnect.security.protection.malformed",
            "Structurally malformed WNSD records.",
        ),
    )

    private fun latencyDescriptors(): List<TelemetryMetricDescriptor> = listOf(
        clockDescriptor(
            TelemetryMetricIds.ClockSyncSampleAccepted,
            "warpnect.clock.sync.sample.accepted",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "ClockSync samples accepted by the existing RFC-001F model.",
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncSampleRejected,
            "warpnect.clock.sync.sample.rejected",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "ClockSync samples rejected by the existing RFC-001F model.",
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncQualified,
            "warpnect.clock.sync.qualified",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Boolean,
            "Whether the RFC-001F mapping is qualified for conversion.",
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncOffset,
            "warpnect.clock.sync.offset",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Microseconds,
            "ClockSync local-minus-remote offset estimate at the model reference point.",
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncUncertainty,
            "warpnect.clock.sync.uncertainty",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Microseconds,
            "ClockSync uncertainty when a concrete estimator supplies one.",
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncRoundTrip,
            "warpnect.clock.sync.round_trip",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "ClockSync round-trip observations.",
            clockSyncHistogramBoundaries(),
        ),
        clockDescriptor(
            TelemetryMetricIds.ClockSyncMappingRejected,
            "warpnect.clock.sync.mapping_rejected",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Cross-device samples rejected because the RFC-001F mapping was unusable.",
        ),
        descriptor(
            TelemetryMetricIds.TransportOneWay,
            "warpnect.latency.transport.one_way",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified authenticated transport one-way estimates.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.TransportSampled,
            "warpnect.latency.transport.sampled",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Deterministically sampled authenticated transport observations.",
        ),
        descriptor(
            TelemetryMetricIds.TransportClockUnqualified,
            "warpnect.latency.transport.clock_unqualified",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Transport samples rejected because ClockSync was unqualified.",
        ),
        descriptor(
            TelemetryMetricIds.TransportInvalid,
            "warpnect.latency.transport.invalid",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Transport samples rejected for an invalid calculated duration.",
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderInputToOutput,
            "warpnect.latency.video.decoder_input_to_output",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local Video decoder input-to-output duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.VideoDecoderOutputToRelease,
            "warpnect.latency.video.decoder_output_to_release",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local Video decoder output-to-Surface-release duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.VideoReleaseToRender,
            "warpnect.latency.video.release_to_render",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local Surface-release-to-Android-render-notification duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.VideoSourceToDecoderInput,
            "warpnect.latency.video.source_to_decoder_input",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified cross-device Video source-to-decoder-input estimate.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.VideoSourceToRender,
            "warpnect.latency.video.source_to_render",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified cross-device Video source-to-render estimate.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.VideoCorrelationUnmatched,
            "warpnect.latency.video.correlation_unmatched",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Video stage observations without an eligible in-flight correlation.",
        ),
        descriptor(
            TelemetryMetricIds.VideoCorrelationExpired,
            "warpnect.latency.video.correlation_expired",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Video in-flight correlations expired before completion.",
        ),
        descriptor(
            TelemetryMetricIds.AudioSourceToDecoderInput,
            "warpnect.latency.audio.source_to_decoder_input",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified cross-device Audio source-to-decoder-input estimate.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.AudioDecoderInputToOutput,
            "warpnect.latency.audio.decoder_input_to_output",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local sampled Opus decoder input-to-output duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.AudioOutputToPlaybackCallback,
            "warpnect.latency.audio.output_to_playback_callback",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Audio output-to-playback callback duration when exact ring correlation exists.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackOutputEstimate,
            "warpnect.latency.audio.playback_output_estimate",
            TelemetryMetricKind.GaugeI64,
            TelemetryUnit.Microseconds,
            "Best-effort cold-path Oboe output latency estimate.",
        ),
        descriptor(
            TelemetryMetricIds.AudioPlaybackOutputEstimateFailed,
            "warpnect.latency.audio.playback_output_estimate_failed",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Cold-path Oboe output latency estimate query failures.",
        ),
        descriptor(
            TelemetryMetricIds.AudioCorrelationUnmatched,
            "warpnect.latency.audio.correlation_unmatched",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Audio stage observations without an eligible in-flight correlation.",
        ),
        descriptor(
            TelemetryMetricIds.AudioCorrelationExpired,
            "warpnect.latency.audio.correlation_expired",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Audio in-flight correlations expired before completion.",
        ),
        descriptor(
            TelemetryMetricIds.InputCaptureToSender,
            "warpnect.latency.input.capture_to_sender",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local Input capture-to-sender acceptance duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.InputSourceToReceiver,
            "warpnect.latency.input.source_to_receiver",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified cross-device Input source-to-receiver estimate.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.InputReceiverToInjection,
            "warpnect.latency.input.receiver_to_injection",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Local Input receiver-to-injection-attempt duration.",
            localLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.InputSourceToInjection,
            "warpnect.latency.input.source_to_injection",
            TelemetryMetricKind.HistogramU64,
            TelemetryUnit.Microseconds,
            "Qualified cross-device Input source-to-injection estimate.",
            crossDeviceLatencyHistogramBoundaries(),
        ),
        descriptor(
            TelemetryMetricIds.InputCorrelationRejected,
            "warpnect.latency.input.correlation_rejected",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Input latency samples rejected because identity or time provenance was insufficient.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceStarted,
            "warpnect.latency.trace.started",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Bounded latency traces started.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceCompleted,
            "warpnect.latency.trace.completed",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Bounded latency traces completed.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceExpired,
            "warpnect.latency.trace.expired",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Bounded latency traces expired opportunistically.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceCapacityRejected,
            "warpnect.latency.trace.capacity_rejected",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Latency traces rejected by fixed table capacity.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceUnmatched,
            "warpnect.latency.trace.unmatched",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Latency trace stage completions without a matching entry.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceClockUnqualified,
            "warpnect.latency.trace.clock_unqualified",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Latency traces rejected because their clock mapping was unqualified.",
        ),
        latencyTraceDescriptor(
            TelemetryMetricIds.LatencyTraceInvalidDuration,
            "warpnect.latency.trace.invalid_duration",
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Count,
            "Latency traces rejected for a negative or invalid duration.",
        ),
    )

    private fun clockSyncHistogramBoundaries() = ulongArrayOf(
        100u, 250u, 500u, 1_000u, 2_000u, 5_000u, 10_000u, 20_000u, 50_000u, 100_000u,
        250_000u, 500_000u,
    )

    private fun localLatencyHistogramBoundaries() = ulongArrayOf(
        50u, 100u, 250u, 500u, 1_000u, 2_000u, 4_000u, 8_000u, 16_000u, 33_000u, 66_000u,
        125_000u, 250_000u, 500_000u, 1_000_000u,
    )

    private fun crossDeviceLatencyHistogramBoundaries() = ulongArrayOf(
        250u, 500u, 1_000u, 2_000u, 4_000u, 8_000u, 16_000u, 33_000u, 66_000u, 125_000u,
        250_000u, 500_000u, 1_000_000u,
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

    private fun sessionDescriptor(id: TelemetryMetricId, name: String, description: String) = TelemetryMetricDescriptor(
        id,
        name,
        TelemetryMetricKind.CounterU64,
        TelemetryUnit.Count,
        setOf(TelemetryScopeKind.Session),
        description,
    )

    private fun networkDescriptor(id: TelemetryMetricId, name: String, unit: TelemetryUnit, description: String) =
        TelemetryMetricDescriptor(
            id,
            name,
            TelemetryMetricKind.CounterU64,
            unit,
            setOf(TelemetryScopeKind.Session, TelemetryScopeKind.Channel),
            description,
        )

    private fun channelDescriptor(id: TelemetryMetricId, name: String, unit: TelemetryUnit, description: String) =
        TelemetryMetricDescriptor(
            id,
            name,
            TelemetryMetricKind.CounterU64,
            unit,
            setOf(TelemetryScopeKind.Channel),
            description,
        )

    private fun pathDescriptor(
        id: TelemetryMetricId,
        name: String,
        kind: TelemetryMetricKind,
        unit: TelemetryUnit,
        description: String,
    ) = TelemetryMetricDescriptor(id, name, kind, unit, setOf(TelemetryScopeKind.Path), description)

    private fun clockDescriptor(
        id: TelemetryMetricId,
        name: String,
        kind: TelemetryMetricKind,
        unit: TelemetryUnit,
        description: String,
        boundaries: ULongArray = ulongArrayOf(),
    ) = TelemetryMetricDescriptor(
        id,
        name,
        kind,
        unit,
        setOf(TelemetryScopeKind.Session, TelemetryScopeKind.Channel),
        description,
        boundaries,
    )

    private fun latencyTraceDescriptor(
        id: TelemetryMetricId,
        name: String,
        kind: TelemetryMetricKind,
        unit: TelemetryUnit,
        description: String,
    ) = TelemetryMetricDescriptor(
        id,
        name,
        kind,
        unit,
        setOf(TelemetryScopeKind.Channel, TelemetryScopeKind.Component),
        description,
    )

    private fun securityDescriptor(id: TelemetryMetricId, name: String, description: String) =
        TelemetryMetricDescriptor(
            id,
            name,
            TelemetryMetricKind.CounterU64,
            TelemetryUnit.Packets,
            setOf(TelemetryScopeKind.Session, TelemetryScopeKind.Channel),
            description,
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
