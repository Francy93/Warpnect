@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.ui

import io.warpnect.diagnostics.DiagnosticEventProviderStatus
import io.warpnect.diagnostics.DiagnosticSeverity
import io.warpnect.telemetry.TelemetryMetricKind
import io.warpnect.telemetry.TelemetrySnapshotStatus
import io.warpnect.telemetry.TelemetryUnit

enum class DiagnosticsSection(
    val title: String,
) {
    Overview("Overview"),
    Media("Media"),
    Network("Network"),
    Latency("Latency"),
    Events("Events"),
    Raw("Raw"),
}

enum class DiagnosticsRefreshInterval(
    val millis: Long,
    val label: String,
) {
    Fast(500L, "500 ms"),
    Default(1_000L, "1 s"),
    Slow(2_000L, "2 s"),
    Slowest(5_000L, "5 s"),
}

enum class DiagnosticsUiPhase {
    Loading,
    Ready,
    Failed,
    Disabled,
}

enum class DiagnosticsMetricCategory(
    val title: String,
) {
    Framework("Framework"),
    Session("Session"),
    Network("Network"),
    Video("Video"),
    Audio("Audio"),
    Input("Input"),
    Clock("Clock"),
    Security("Security"),
}

enum class DiagnosticsEventProvider(
    val title: String,
) {
    Application("Application"),
    Native("Native"),
}

enum class DiagnosticsEventCategory(
    val title: String,
) {
    Diagnostic("Diagnostic"),
    Session("Session"),
    Network("Network"),
    Video("Video"),
    Audio("Audio"),
    Input("Input"),
    Clock("Clock"),
    Security("Security"),
    Platform("Platform"),
}

data class DiagnosticsSessionKey(
    val sessionIdHigh: ULong,
    val sessionIdLow: ULong,
    val generation: UInt,
)

data class DiagnosticsSessionUi(
    val key: DiagnosticsSessionKey,
    val label: String,
    val generation: UInt,
    val activePath: String?,
)

data class DiagnosticsScopeUi(
    val kind: String,
    val summary: String,
    val session: DiagnosticsSessionKey? = null,
    val pathId: UInt? = null,
    val channelId: UInt? = null,
)

data class DiagnosticsRateUi(
    val valuePerSecond: Double,
    val label: String,
)

data class DiagnosticsHistogramUi(
    val sampleCount: ULong,
    val average: String?,
    val minimum: String?,
    val maximum: String?,
    val p50: String?,
    val p95: String?,
    val p99: String?,
)

data class DiagnosticsMetricUi(
    val sourceId: UInt,
    val metricId: Int,
    val canonicalName: String,
    val displayName: String,
    val category: DiagnosticsMetricCategory,
    val kind: TelemetryMetricKind,
    val unit: TelemetryUnit,
    val value: String,
    val rate: DiagnosticsRateUi? = null,
    val histogram: DiagnosticsHistogramUi? = null,
    val scope: DiagnosticsScopeUi,
)

data class DiagnosticsEventFieldUi(
    val label: String,
    val value: String,
)

data class DiagnosticsEventUi(
    val provider: DiagnosticsEventProvider,
    val sequence: ULong,
    val severity: DiagnosticSeverity,
    val title: String,
    val category: DiagnosticsEventCategory,
    val scope: DiagnosticsScopeUi,
    val clockDomain: String,
    val fields: List<DiagnosticsEventFieldUi>,
)

data class DiagnosticsEventFilterUi(
    val minimumSeverity: DiagnosticSeverity = DiagnosticSeverity.Debug,
    val provider: DiagnosticsEventProvider? = null,
    val category: DiagnosticsEventCategory? = null,
    val selectedSessionOnly: Boolean = false,
)

data class DiagnosticsEventsUi(
    val application: List<DiagnosticsEventUi> = emptyList(),
    val native: List<DiagnosticsEventUi> = emptyList(),
    val applicationStatus: String = "Unavailable",
    val nativeStatus: DiagnosticEventProviderStatus = DiagnosticEventProviderStatus.Disabled,
    val applicationGap: Boolean = false,
    val nativeGap: Boolean = false,
    val filter: DiagnosticsEventFilterUi = DiagnosticsEventFilterUi(),
)

data class DiagnosticsOverviewUi(
    val sourceCount: Int = 0,
    val sessionCount: Int = 0,
    val selectedSession: DiagnosticsSessionUi? = null,
    val role: String? = null,
    val lifecycleState: String? = null,
    val clockSyncQualified: String = "Unavailable",
    val warningEventCount: Int = 0,
    val historyGap: Boolean = false,
)

data class DiagnosticsLatencyUi(
    val metrics: List<DiagnosticsMetricUi> = emptyList(),
    val unavailable: List<String> = emptyList(),
)

data class DiagnosticsUiState(
    val phase: DiagnosticsUiPhase = DiagnosticsUiPhase.Loading,
    val section: DiagnosticsSection = DiagnosticsSection.Overview,
    val samplingActive: Boolean = false,
    val paused: Boolean = false,
    val refreshInterval: DiagnosticsRefreshInterval = DiagnosticsRefreshInterval.Default,
    val snapshotStatus: TelemetrySnapshotStatus? = null,
    val snapshotAgeNs: ULong? = null,
    val refreshFailed: Boolean = false,
    val sessions: List<DiagnosticsSessionUi> = emptyList(),
    val selectedSession: DiagnosticsSessionKey? = null,
    val overview: DiagnosticsOverviewUi = DiagnosticsOverviewUi(),
    val media: List<DiagnosticsMetricUi> = emptyList(),
    val network: List<DiagnosticsMetricUi> = emptyList(),
    val latency: DiagnosticsLatencyUi = DiagnosticsLatencyUi(),
    val events: DiagnosticsEventsUi = DiagnosticsEventsUi(),
    val raw: List<DiagnosticsMetricUi> = emptyList(),
)
