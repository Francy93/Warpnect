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
