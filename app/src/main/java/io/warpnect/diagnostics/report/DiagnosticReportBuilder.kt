@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.report

import io.warpnect.diagnostics.DiagnosticEventBatch
import io.warpnect.diagnostics.DiagnosticEventCursor
import io.warpnect.diagnostics.DiagnosticEventDescriptorCatalog
import io.warpnect.diagnostics.DiagnosticEventHub
import io.warpnect.diagnostics.DiagnosticEventProviderStatus
import io.warpnect.diagnostics.DiagnosticEventRecord
import io.warpnect.diagnostics.DiagnosticEventSnapshot
import io.warpnect.diagnostics.DiagnosticPayloadFieldDescriptor
import io.warpnect.diagnostics.DiagnosticScalarKind
import io.warpnect.diagnostics.DiagnosticScopeSnapshot
import io.warpnect.diagnostics.NativeDiagnosticEventBatch
import io.warpnect.telemetry.TelemetryDescriptorCatalog
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshot
import io.warpnect.telemetry.TelemetrySnapshotRecord
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

interface DiagnosticReportReader {
    fun telemetrySnapshot(): TelemetrySnapshot
    fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot
}

class HubDiagnosticReportReader(
    private val telemetryHub: TelemetryHub,
    private val eventHub: DiagnosticEventHub,
) : DiagnosticReportReader {
    override fun telemetrySnapshot(): TelemetrySnapshot = telemetryHub.snapshot()
    override fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot =
        eventHub.snapshotSince(cursor, limit)
}

data class DiagnosticReportEnvironment(
    val runtime: ReportRuntimeMetadata,
    val platform: ReportPlatformMetadata,
    val clock: Clock = Clock.systemUTC(),
)

data class BenchmarkBaseline(
    val selection: ReportSessionSelection,
    val startSnapshot: TelemetrySnapshot,
    val eventCursor: DiagnosticEventCursor,
    val startCapture: ReportCapture,
)

/** Cold-path only report projection. It intentionally has no Android Storage or Compose dependency. */
class DiagnosticReportBuilder(
    private val reader: DiagnosticReportReader,
    private val environment: DiagnosticReportEnvironment,
) {
    fun captureSnapshot(selection: ReportSessionSelection?): DiagnosticReport {
        val snapshot = reader.telemetrySnapshot()
        val events = readAllEvents(DiagnosticEventCursor())
        return report(
            type = DiagnosticReportType.DiagnosticsSnapshot,
            selection = selection,
            telemetrySnapshot = snapshot,
            eventSnapshot = events,
            benchmark = null,
        )
    }

    fun startBenchmark(selection: ReportSessionSelection): BenchmarkBaseline {
        val snapshot = reader.telemetrySnapshot()
        // This bounded cursor read is the sole start-time event operation; its records are discarded.
        val cursors = reader.diagnosticEvents(DiagnosticEventCursor(), 1)
        return BenchmarkBaseline(
            selection = selection,
            startSnapshot = snapshot,
            eventCursor = DiagnosticEventCursor(
                cursors.kotlin.newestAvailableSequence,
                cursors.native.batch.newestAvailableSequence,
            ),
            startCapture = capture(snapshot, false),
        )
    }

    fun stopBenchmark(baseline: BenchmarkBaseline): DiagnosticReport {
        val end = reader.telemetrySnapshot()
        val eventSnapshot = readAllEvents(baseline.eventCursor)
        val interruption = interruption(baseline.selection, end)
        val duration = end.capturedAtMonotonicNs.takeIf { it > baseline.startSnapshot.capturedAtMonotonicNs }
            ?.minus(baseline.startSnapshot.capturedAtMonotonicNs)
        val benchmark = ReportBenchmark(
            durationNs = duration?.toString(),
            durationSeconds = duration?.toDouble()?.div(1_000_000_000.0),
            status = if (interruption == null) "completed" else "interrupted",
            interruptionReason = interruption,
            startCapture = baseline.startCapture,
            endCapture = capture(end, eventSnapshot.kotlin.gap || eventSnapshot.native.batch.gap),
            metrics = benchmarkMetrics(baseline.startSnapshot, end, baseline.selection, duration, interruption),
        )
        return report(
            DiagnosticReportType.BenchmarkWindow,
            baseline.selection,
            end,
            eventSnapshot,
            benchmark,
            baseline.eventCursor,
        )
    }

    private fun report(
        type: DiagnosticReportType,
        selection: ReportSessionSelection?,
        telemetrySnapshot: TelemetrySnapshot,
        eventSnapshot: DiagnosticEventSnapshot,
        benchmark: ReportBenchmark?,
        requestedAfter: DiagnosticEventCursor = DiagnosticEventCursor(),
    ): DiagnosticReport {
        val telemetry = telemetrySnapshot.records.asSequence()
            .filter { relevant(it.scope, selection) }
            .mapNotNull { metric(it, selection) }
            .sortedWith(compareBy<ReportMetric> { it.scope.kind }.thenBy { it.sourceId }.thenBy { it.metricId })
            .toList()
        if (telemetry.size > MAX_REPORT_METRICS) throw DiagnosticReportFailure.MetricLimitExceeded
        val application =
            events(
                eventSnapshot.kotlin,
                DiagnosticEventProviderStatus.Available,
                selection,
                requestedAfter.kotlinSequence,
            )
        val native =
            events(eventSnapshot.native.batch, eventSnapshot.native.status, selection, requestedAfter.nativeSequence)
        if (
            application.events.size > MAX_REPORT_EVENTS_PER_PROVIDER ||
            native.events.size > MAX_REPORT_EVENTS_PER_PROVIDER
        ) {
            throw DiagnosticReportFailure.EventLimitExceeded
        }
        val partial = telemetrySnapshot.status.name != "Complete" || application.gapDetected || native.gapDetected ||
            eventSnapshot.native.status != DiagnosticEventProviderStatus.Available
        return DiagnosticReport(
            type = type,
            generatedAtUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now(environment.clock)),
            runtime = environment.runtime,
            platform = environment.platform,
            scope = if (selection == null) {
                ReportScope("process")
            } else {
                ReportScope("session", 1, selection.generation, role = selection.role)
            },
            capture = capture(telemetrySnapshot, partial),
            telemetry = telemetry,
            applicationEvents = application,
            nativeEvents = native,
            benchmark = benchmark,
            limitations = limitations(),
        )
    }

    private fun readAllEvents(cursor: DiagnosticEventCursor): DiagnosticEventSnapshot {
        val first = reader.diagnosticEvents(cursor, 512)
        if (!first.kotlin.truncated && !first.native.batch.truncated) return first
        val second = reader.diagnosticEvents(
            DiagnosticEventCursor(first.kotlin.nextCursor, first.native.batch.nextCursor),
            512,
        )
        return DiagnosticEventSnapshot(
            merge(first.kotlin, second.kotlin),
            NativeDiagnosticEventBatch(second.native.status, merge(first.native.batch, second.native.batch)),
        )
    }

    private fun merge(first: DiagnosticEventBatch, second: DiagnosticEventBatch): DiagnosticEventBatch =
        DiagnosticEventBatch(
            events = (first.events + second.events).sortedBy { it.sequence }.take(MAX_REPORT_EVENTS_PER_PROVIDER),
            oldestAvailableSequence = first.oldestAvailableSequence,
            newestAvailableSequence = second.newestAvailableSequence,
            nextCursor = second.nextCursor,
            gap = first.gap || second.gap,
            overwritten = second.overwritten,
            truncated = second.truncated,
        )

    private fun capture(snapshot: TelemetrySnapshot, eventGap: Boolean): ReportCapture = ReportCapture(
        telemetryStatus = snapshot.status.name.lowercase(),
        telemetrySequence = snapshot.sequence.toString(),
        capturedAtBootTimeNs = snapshot.capturedAtMonotonicNs.toString(),
        telemetryErrors = snapshot.errors.map { it.name }.sorted(),
        partial = snapshot.status.name != "Complete" || eventGap,
    )

    private fun metric(record: TelemetrySnapshotRecord, selection: ReportSessionSelection?): ReportMetric? {
        val descriptor = TelemetryDescriptorCatalog.descriptor(record.metricId) ?: return null
        return ReportMetric(
            record.sourceId.value,
            scope(record.scope, selection),
            record.metricId.value,
            descriptor.canonicalName,
            descriptor.kind,
            descriptor.unit,
            metricValue(record.value, descriptor.histogramBoundaries.toList()),
        )
    }

    private fun metricValue(value: TelemetryMetricValue, boundaries: List<ULong>): ReportMetricValue = when (value) {
        is TelemetryMetricValue.Counter -> ReportMetricValue.Counter(value.value.toString())
        is TelemetryMetricValue.Gauge -> ReportMetricValue.Gauge(
            value.valid,
            value.value.takeIf { value.valid }?.toString(),
        )
        is TelemetryMetricValue.Histogram -> ReportMetricValue.Histogram(
            value.count.toString(),
            value.sum.toString(),
            value.min?.toString(),
            value.max?.toString(),
            boundaries.map { it.toString() },
            value.bucketCounts.map { it.toString() },
        )
    }

    private fun events(
        batch: DiagnosticEventBatch,
        status: DiagnosticEventProviderStatus,
        selection: ReportSessionSelection?,
        requestedAfter: ULong,
    ): ReportEventProvider = ReportEventProvider(
        status = status.name.lowercase(),
        gapDetected = batch.gap,
        oldestAvailableSequence = batch.oldestAvailableSequence.toString(),
        requestedAfterSequence = requestedAfter.toString(),
        overwrittenCount = batch.overwritten.toString(),
        truncated = batch.truncated,
        events = batch.events.filter {
            relevant(it.scope, selection)
        }.sortedBy { it.sequence }.mapNotNull { event(it, selection) },
    )

    private fun event(event: DiagnosticEventRecord, selection: ReportSessionSelection?): ReportEvent? {
        val descriptor = DiagnosticEventDescriptorCatalog.descriptorFor(event.typeId) ?: return null
        val payload = descriptor.payload.mapIndexed { index, field ->
            ReportEventField(field.key.name.lowercase(), eventPayload(field, event.payload.getOrElse(index) { 0u }))
        }
        return ReportEvent(
            event.sequence.toString(),
            event.timestampNs.toString(),
            event.clockDomain.name,
            event.severity.name.lowercase(),
            event.typeId.value,
            descriptor.canonicalName,
            scope(event.scope, selection),
            payload,
        )
    }

    private fun eventPayload(field: DiagnosticPayloadFieldDescriptor, value: ULong): String = when (field.kind) {
        DiagnosticScalarKind.Unsigned -> value.toString()
        DiagnosticScalarKind.Signed -> value.toLong().toString()
        DiagnosticScalarKind.Boolean -> (value != 0uL).toString()
        DiagnosticScalarKind.Enum -> enumName(field.key.name, value) ?: value.toString()
    }

    private fun enumName(key: String, value: ULong): String? = when (key) {
        "Reason" -> io.warpnect.diagnostics.DiagnosticReason.entries.firstOrNull { it.code == value }?.name
        "FromState", "ToState" ->
            io.warpnect.diagnostics.DiagnosticSessionState.entries.firstOrNull { it.code == value }?.name
        else -> null
    }

    private fun scope(scope: TelemetryScope, selection: ReportSessionSelection?): ReportScope = when (scope) {
        TelemetryScope.Process -> ReportScope("process")
        is TelemetryScope.Session -> ReportScope("session", 1, scope.generation.value)
        is TelemetryScope.Path -> ReportScope(
            "path",
            1,
            scope.generation.value,
            scope.pathId.value,
            scope.pathKind.name,
        )
        is TelemetryScope.Channel -> ReportScope(
            "channel",
            1,
            scope.generation.value,
            channelId = scope.channelId.value,
            channelKind = scope.channelKind.name,
            channelDirection = scope.direction.name,
        )
        is TelemetryScope.Component -> ReportScope("component", component = scope.component.name)
    }

    private fun scope(scope: DiagnosticScopeSnapshot, selection: ReportSessionSelection?): ReportScope =
        when (scope.kind.name) {
            "Process" -> ReportScope("process")
            "Session" -> ReportScope("session", 1, scope.sessionGeneration)
            "Path" -> ReportScope("path", 1, scope.sessionGeneration, scope.pathId, scope.pathKind.toString())
            "Channel" -> ReportScope(
                "channel",
                1,
                scope.sessionGeneration,
                channelId = scope.channelId,
                channelKind = scope.channelKind.toString(),
                channelDirection = scope.channelDirection.toString(),
            )
            else -> ReportScope("component", component = scope.componentKind.toString())
        }

    private fun relevant(scope: TelemetryScope, selection: ReportSessionSelection?): Boolean = when (scope) {
        TelemetryScope.Process, is TelemetryScope.Component -> true
        is TelemetryScope.Session -> matches(
            scope.sessionId.high,
            scope.sessionId.low,
            scope.generation.value,
            selection,
        )
        is TelemetryScope.Path -> matches(scope.sessionId.high, scope.sessionId.low, scope.generation.value, selection)
        is TelemetryScope.Channel -> matches(
            scope.sessionId.high,
            scope.sessionId.low,
            scope.generation.value,
            selection,
        )
    }

    private fun relevant(scope: DiagnosticScopeSnapshot, selection: ReportSessionSelection?): Boolean =
        scope.kind.name == "Process" || scope.kind.name == "Component" ||
            matches(scope.sessionIdHigh, scope.sessionIdLow, scope.sessionGeneration, selection)

    private fun matches(high: ULong, low: ULong, generation: UInt, selection: ReportSessionSelection?): Boolean =
        selection == null ||
            (high == selection.sessionIdHigh && low == selection.sessionIdLow && generation == selection.generation)

    private fun interruption(selection: ReportSessionSelection, end: TelemetrySnapshot): String? {
        val matchingSession = end.records.mapNotNull { record -> sessionIdentity(record.scope) }
            .filter { it.first == selection.sessionIdHigh && it.second == selection.sessionIdLow }
        return when {
            matchingSession.any { it.third != selection.generation } -> "interrupted_by_generation_change"
            matchingSession.none { it.third == selection.generation } -> "interrupted_by_session_close"
            else -> null
        }
    }

    private fun sessionIdentity(scope: TelemetryScope): Triple<ULong, ULong, UInt>? = when (scope) {
        is TelemetryScope.Session -> Triple(scope.sessionId.high, scope.sessionId.low, scope.generation.value)
        is TelemetryScope.Path -> Triple(scope.sessionId.high, scope.sessionId.low, scope.generation.value)
        is TelemetryScope.Channel -> Triple(scope.sessionId.high, scope.sessionId.low, scope.generation.value)
        else -> null
    }

    private fun benchmarkMetrics(
        start: TelemetrySnapshot,
        end: TelemetrySnapshot,
        selection: ReportSessionSelection,
        duration: ULong?,
        interrupted: String?,
    ): List<ReportBenchmarkMetric> = start.records.asSequence().filter { relevant(it.scope, selection) }
        .mapNotNull { before ->
            val descriptor = TelemetryDescriptorCatalog.descriptor(before.metricId) ?: return@mapNotNull null
            val reportScope = scope(before.scope, selection)
            val after = end.records.firstOrNull {
                it.sourceId == before.sourceId && it.metricId == before.metricId && it.scope == before.scope
            }
            val reason = when {
                interrupted != null -> interrupted
                after == null -> "source_replaced"
                else -> null
            }
            when (val beforeValue = metricValue(before.value, descriptor.histogramBoundaries.toList())) {
                is ReportMetricValue.Counter -> {
                    val afterValue = after?.value as? TelemetryMetricValue.Counter
                    val startValue = (before.value as TelemetryMetricValue.Counter).value
                    val valid = reason == null &&
                        afterValue != null &&
                        afterValue.value >= startValue &&
                        duration != null
                    val delta = if (valid) afterValue!!.value - startValue else null
                    ReportBenchmarkMetric.Counter(
                        sourceId = before.sourceId.value,
                        scope = reportScope,
                        metricId = before.metricId.value,
                        name = descriptor.canonicalName,
                        start = beforeValue.value,
                        end = afterValue?.value?.toString() ?: "",
                        delta = delta?.toString(),
                        ratePerSecond = delta?.toDouble()?.div(duration!!.toDouble() / 1_000_000_000.0),
                        unavailableReason = if (valid) null else reason ?: "counter_decreased_or_invalid_duration",
                    )
                }
                is ReportMetricValue.Gauge -> ReportBenchmarkMetric.Gauge(
                    before.sourceId.value,
                    reportScope,
                    before.metricId.value,
                    descriptor.canonicalName,
                    beforeValue,
                    after?.let {
                        metricValue(it.value, descriptor.histogramBoundaries.toList()) as? ReportMetricValue.Gauge
                    },
                    reason,
                )
                is ReportMetricValue.Histogram -> histogramBenchmark(
                    before,
                    after,
                    descriptor.histogramBoundaries.toList(),
                    reportScope,
                    descriptor.canonicalName,
                    reason,
                )
            }
        }.sortedWith(
            compareBy<ReportBenchmarkMetric> { it.scope.kind }.thenBy { it.sourceId }.thenBy { it.metricId },
        ).toList()

    private fun histogramBenchmark(
        before: TelemetrySnapshotRecord,
        after: TelemetrySnapshotRecord?,
        boundaries: List<ULong>,
        scope: ReportScope,
        name: String,
        unavailable: String?,
    ): ReportBenchmarkMetric.Histogram {
        val start = metricValue(before.value, boundaries) as ReportMetricValue.Histogram
        val end = after?.let { metricValue(it.value, boundaries) as? ReportMetricValue.Histogram }
        val a = before.value as TelemetryMetricValue.Histogram
        val b = after?.value as? TelemetryMetricValue.Histogram
        val valid = unavailable == null &&
            end != null &&
            b != null &&
            a.bucketCounts.size == b.bucketCounts.size &&
            b.count >= a.count &&
            b.sum >= a.sum &&
            b.bucketCounts.indices.all { b.bucketCounts[it] >= a.bucketCounts[it] }
        if (!valid) {
            return ReportBenchmarkMetric.Histogram(
                before.sourceId.value,
                scope,
                before.metricId.value,
                name,
                start,
                end,
                unavailableReason = unavailable ?: "histogram_decreased_or_incompatible",
            )
        }
        val buckets = b!!.bucketCounts.indices.map { (b.bucketCounts[it] - a.bucketCounts[it]).toString() }
        val count = b.count - a.count
        val sum = b.sum - a.sum
        return ReportBenchmarkMetric.Histogram(
            sourceId = before.sourceId.value,
            scope = scope,
            metricId = before.metricId.value,
            name = name,
            start = start,
            end = end,
            windowCount = count.toString(),
            windowSum = sum.toString(),
            windowBucketCounts = buckets,
            windowAverage = if (count == 0uL) null else sum.toDouble() / count.toDouble(),
            p50UpperBound = percentile(boundaries, buckets, count, .50),
            p95UpperBound = percentile(boundaries, buckets, count, .95),
            p99UpperBound = percentile(boundaries, buckets, count, .99),
        )
    }

    private fun percentile(boundaries: List<ULong>, buckets: List<String>, count: ULong, percentile: Double): String? {
        if (count == 0uL) return null
        val target = kotlin.math.ceil(count.toDouble() * percentile).toULong()
        var total = 0uL
        buckets.forEachIndexed { index, value ->
            total += value.toULong()
            if (total >= target) return boundaries.getOrNull(index)?.toString() ?: "> ${boundaries.lastOrNull() ?: 0u}"
        }
        return null
    }

    private fun limitations(): List<String> = listOf(
        "telemetry_snapshots_are_weakly_consistent",
        "application_and_native_events_use_separate_clock_domains",
        "unsupported_cross_device_latency_remains_unavailable",
        "render_notifications_are_not_physical_display_counts",
        "oboe_output_latency_is_an_estimate_when_available",
        "real_device_validation_not_run",
    )
}
