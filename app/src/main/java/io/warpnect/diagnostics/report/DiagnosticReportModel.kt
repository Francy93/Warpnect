@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.report

import io.warpnect.telemetry.TelemetryMetricKind
import io.warpnect.telemetry.TelemetryUnit

/** RFC-006G local file format. It is deliberately unrelated to the Warpnect network protocol. */
const val DIAGNOSTIC_REPORT_SCHEMA = "warpnect.diagnostics.report"
const val DIAGNOSTIC_REPORT_SCHEMA_VERSION = 1
const val MAX_REPORT_METRICS = 4096
const val MAX_REPORT_EVENTS_PER_PROVIDER = 1024
const val MAX_REPORT_BYTES = 8 * 1024 * 1024

enum class DiagnosticReportType(val wireName: String) {
    DiagnosticsSnapshot("diagnostics_snapshot"),
    BenchmarkWindow("benchmark_window"),
}

data class ReportRuntimeMetadata(
    val appVersionName: String,
    val appVersionCode: String,
    val architectureVersion: String = "1.0",
    val sclProtocolVersion: Int = 1,
    val nativeBridgeAbiVersion: Int = 1,
    val runtimeTelemetryModelVersion: Int = 1,
    val wntmVersion: Int = 1,
    val diagnosticEventModelVersion: Int = 1,
    val wndeVersion: Int = 1,
)

data class ReportPlatformMetadata(
    val androidSdk: Int?,
    val supportedAbi: String?,
    val manufacturer: String?,
    val model: String?,
    val realDeviceValidation: String = "not_run",
)

/** The actual SessionId is filtering-only and never enters this sanitized representation. */
data class ReportSessionSelection(
    val sessionIdHigh: ULong,
    val sessionIdLow: ULong,
    val generation: UInt,
    val role: String? = null,
)

data class ReportScope(
    val kind: String,
    val sessionAlias: Int? = null,
    val generation: UInt? = null,
    val pathId: UInt? = null,
    val pathKind: String? = null,
    val channelId: UInt? = null,
    val channelKind: String? = null,
    val channelDirection: String? = null,
    val component: String? = null,
    val role: String? = null,
)

sealed interface ReportMetricValue {
    data class Counter(val value: String) : ReportMetricValue
    data class Gauge(val valid: Boolean, val value: String? = null) : ReportMetricValue
    data class Histogram(
        val count: String,
        val sum: String,
        val min: String?,
        val max: String?,
        val finiteBoundaries: List<String>,
        val bucketCounts: List<String>,
    ) : ReportMetricValue
}

data class ReportMetric(
    val sourceId: UInt,
    val scope: ReportScope,
    val metricId: Int,
    val name: String,
    val kind: TelemetryMetricKind,
    val unit: TelemetryUnit,
    val value: ReportMetricValue,
)

data class ReportEventField(val key: String, val value: String)

data class ReportEvent(
    val sequence: String,
    val timestampNs: String,
    val clockDomain: String,
    val severity: String,
    val eventTypeId: Int,
    val canonicalName: String,
    val scope: ReportScope,
    val payload: List<ReportEventField>,
)

data class ReportEventProvider(
    val status: String,
    val gapDetected: Boolean,
    val oldestAvailableSequence: String,
    val requestedAfterSequence: String,
    val overwrittenCount: String,
    val truncated: Boolean,
    val events: List<ReportEvent>,
)

data class ReportCapture(
    val telemetryStatus: String,
    val telemetrySequence: String,
    val capturedAtBootTimeNs: String,
    val telemetryErrors: List<String>,
    val consistency: String = "weak",
    val partial: Boolean,
)

sealed interface ReportBenchmarkMetric {
    val sourceId: UInt
    val scope: ReportScope
    val metricId: Int
    val name: String

    data class Counter(
        override val sourceId: UInt,
        override val scope: ReportScope,
        override val metricId: Int,
        override val name: String,
        val start: String,
        val end: String,
        val delta: String?,
        val ratePerSecond: Double?,
        val unavailableReason: String? = null,
    ) : ReportBenchmarkMetric

    data class Gauge(
        override val sourceId: UInt,
        override val scope: ReportScope,
        override val metricId: Int,
        override val name: String,
        val start: ReportMetricValue.Gauge,
        val end: ReportMetricValue.Gauge?,
        val unavailableReason: String? = null,
    ) : ReportBenchmarkMetric

    data class Histogram(
        override val sourceId: UInt,
        override val scope: ReportScope,
        override val metricId: Int,
        override val name: String,
        val start: ReportMetricValue.Histogram,
        val end: ReportMetricValue.Histogram?,
        val windowCount: String? = null,
        val windowSum: String? = null,
        val windowBucketCounts: List<String>? = null,
        val windowAverage: Double? = null,
        val p50UpperBound: String? = null,
        val p95UpperBound: String? = null,
        val p99UpperBound: String? = null,
        val windowMin: Nothing? = null,
        val windowMax: Nothing? = null,
        val unavailableReason: String? = null,
    ) : ReportBenchmarkMetric
}

data class ReportBenchmark(
    val durationNs: String?,
    val durationSeconds: Double?,
    val status: String,
    val interruptionReason: String? = null,
    val startCapture: ReportCapture,
    val endCapture: ReportCapture,
    val metrics: List<ReportBenchmarkMetric>,
)

data class DiagnosticReport(
    val type: DiagnosticReportType,
    val generatedAtUtc: String,
    val runtime: ReportRuntimeMetadata,
    val platform: ReportPlatformMetadata,
    val scope: ReportScope,
    val capture: ReportCapture,
    val telemetry: List<ReportMetric>,
    val applicationEvents: ReportEventProvider,
    val nativeEvents: ReportEventProvider,
    val benchmark: ReportBenchmark? = null,
    val limitations: List<String>,
)

sealed class DiagnosticReportFailure(message: String) : Exception(message) {
    data object ReportTooLarge : DiagnosticReportFailure("Report exceeds RFC-006G bound")
    data object MetricLimitExceeded : DiagnosticReportFailure("Report metric bound exceeded")
    data object EventLimitExceeded : DiagnosticReportFailure("Report event bound exceeded")
    data object TemporaryStorageFailure : DiagnosticReportFailure("Temporary report storage failed")
    data object DestinationWriteFailure : DiagnosticReportFailure("Destination write failed")
    data object ExportInProgress : DiagnosticReportFailure("Report export already in progress")
}
