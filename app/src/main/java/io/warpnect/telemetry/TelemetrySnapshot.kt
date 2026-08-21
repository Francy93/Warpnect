@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

enum class TelemetrySnapshotStatus {
    Complete,
    Partial,
    Disabled,
    Closed,
}

enum class TelemetrySnapshotError {
    Disabled,
    SourceCapacityExceeded,
    ProviderCapacityExceeded,
    DescriptorViolation,
    BufferTooSmall,
    MalformedNativeSnapshot,
    ProviderFailure,
    SnapshotRecordLimitExceeded,
    Closed,
}

sealed interface TelemetryMetricValue {
    data class Counter(
        val value: ULong,
    ) : TelemetryMetricValue

    data class Gauge(
        val valid: Boolean,
        val value: Long,
    ) : TelemetryMetricValue

    data class Histogram(
        val count: ULong,
        val sum: ULong,
        val min: ULong?,
        val max: ULong?,
        val bucketCounts: ULongArray,
    ) : TelemetryMetricValue
}

data class TelemetrySnapshotRecord(
    val sourceId: TelemetrySourceId,
    val scope: TelemetryScope,
    val metricId: TelemetryMetricId,
    val kind: TelemetryMetricKind,
    val value: TelemetryMetricValue,
)

data class TelemetrySnapshot(
    val status: TelemetrySnapshotStatus,
    val sequence: ULong,
    val capturedAtMonotonicNs: ULong,
    val records: List<TelemetrySnapshotRecord>,
    val errors: Set<TelemetrySnapshotError>,
)

data class TelemetryProviderSnapshot(
    val records: List<TelemetrySnapshotRecord>,
    val errors: Set<TelemetrySnapshotError> = emptySet(),
)

fun interface TelemetrySnapshotProvider : AutoCloseable {
    fun collect(): TelemetryProviderSnapshot

    override fun close() = Unit
}

fun interface TelemetryMonotonicClock {
    fun nowNs(): ULong
}

object SystemTelemetryMonotonicClock : TelemetryMonotonicClock {
    override fun nowNs(): ULong = System.nanoTime().coerceAtLeast(0L).toULong()
}
