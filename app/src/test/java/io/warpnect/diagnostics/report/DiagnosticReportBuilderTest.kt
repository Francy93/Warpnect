@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.report

import io.warpnect.diagnostics.DiagnosticEventBatch
import io.warpnect.diagnostics.DiagnosticEventCursor
import io.warpnect.diagnostics.DiagnosticEventProviderStatus
import io.warpnect.diagnostics.DiagnosticEventSnapshot
import io.warpnect.diagnostics.NativeDiagnosticEventBatch
import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricKind
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshot
import io.warpnect.telemetry.TelemetrySnapshotRecord
import io.warpnect.telemetry.TelemetrySnapshotStatus
import io.warpnect.telemetry.TelemetrySourceId
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportBuilderTest {
    @Test
    fun snapshotFiltersOtherSessionsAndWritesExact64BitValues() {
        val selected = selection(11u, 22u, 1u)
        val reader =
            FakeReader(
                snapshot(10u, counter(7u, (1uL shl 53) + 1u, selected), counter(8u, 99u, selection(33u, 44u, 1u))),
            )
        val report = builder(reader).captureSnapshot(selected)
        val json = StringWriter().also { DiagnosticReportJsonWriter().write(report, it) }.toString()

        assertEquals(1, report.telemetry.size)
        assertTrue(json.contains("9007199254740993"))
        assertFalse(json.contains("\"session_id"))
        assertFalse(json.contains("\"11\""))
        assertTrue(json.contains("\"role\":\"Host\""))
        assertEquals("1", report.scope.sessionAlias.toString())
    }

    @Test
    fun benchmarkUsesOnlyStartAndEndSnapshotsAndSourceSafeDeltas() {
        val selected = selection(11u, 22u, 1u)
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, 100u, selected)))
        val reportBuilder = builder(reader)
        val baseline = reportBuilder.startBenchmark(selected)
        assertEquals(1, reader.snapshots)
        reader.nextSnapshot = snapshot(3_000_000_000u, counter(7u, 160u, selected))
        val report = reportBuilder.stopBenchmark(baseline)
        val metric = report.benchmark!!.metrics.single() as ReportBenchmarkMetric.Counter

        assertEquals("60", metric.delta)
        assertEquals(30.0, metric.ratePerSecond!!, 0.00001)
        assertEquals(2, reader.snapshots)
        assertEquals("completed", report.benchmark.status)
    }

    @Test
    fun sourceReplacementAndHistogramWindowMinMaxStayUnavailable() {
        val selected = selection(11u, 22u, 1u)
        val start =
            snapshot(
                1_000_000_000u,
                counter(7u, 100u, selected),
                histogram(
                    9u,
                    selected,
                    ulongArrayOf(5u, 10u),
                    ulongArrayOf(2u, 3u),
                    5u,
                    25u,
                ),
            )
        val reader = FakeReader(start)
        val reportBuilder = builder(reader)
        val baseline = reportBuilder.startBenchmark(selected)
        reader.nextSnapshot = snapshot(
            2_000_000_000u,
            counter(8u, 160u, selected),
            histogram(
                9u,
                selected,
                ulongArrayOf(5u, 10u),
                ulongArrayOf(4u, 6u),
                10u,
                55u,
            ),
        )
        val report = reportBuilder.stopBenchmark(baseline)
        val counter = report.benchmark!!.metrics.filterIsInstance<ReportBenchmarkMetric.Counter>().single()
        val histogram = report.benchmark.metrics.filterIsInstance<ReportBenchmarkMetric.Histogram>().single()

        assertNull(counter.delta)
        assertEquals("source_replaced", counter.unavailableReason)
        assertEquals("5", histogram.windowCount)
        assertNull(histogram.windowMin)
        assertNull(histogram.windowMax)
    }

    @Test
    fun generationChangeInterruptsBenchmarkWithoutCrossGenerationDeltas() {
        val selected = selection(11u, 22u, 1u)
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, 100u, selected)))
        val reportBuilder = builder(reader)
        val baseline = reportBuilder.startBenchmark(selected)
        reader.nextSnapshot = snapshot(
            2_000_000_000u,
            counter(8u, 200u, selection(11u, 22u, 2u)),
        )

        val report = reportBuilder.stopBenchmark(baseline)
        val counter = report.benchmark!!.metrics.single() as ReportBenchmarkMetric.Counter

        assertEquals("interrupted", report.benchmark.status)
        assertEquals("interrupted_by_generation_change", report.benchmark.interruptionReason)
        assertNull(counter.delta)
        assertEquals("interrupted_by_generation_change", counter.unavailableReason)
    }

    @Test
    fun closedSessionInterruptsBenchmarkAndFixedInputsProduceStableJson() {
        val selected = selection(11u, 22u, 1u)
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, ULong.MAX_VALUE, selected)))
        val reportBuilder = builder(reader)
        val first = reportBuilder.captureSnapshot(selected)
        val second = reportBuilder.captureSnapshot(selected)
        val firstJson = StringWriter().also {
            DiagnosticReportJsonWriter().write(first, it)
        }.toString()
        val secondJson = StringWriter().also {
            DiagnosticReportJsonWriter().write(second, it)
        }.toString()

        assertEquals(firstJson, secondJson)
        assertTrue(firstJson.contains(ULong.MAX_VALUE.toString()))

        val baseline = reportBuilder.startBenchmark(selected)
        reader.nextSnapshot = snapshot(2_000_000_000u)
        val benchmark = reportBuilder.stopBenchmark(baseline).benchmark!!
        assertEquals("interrupted_by_session_close", benchmark.interruptionReason)
        assertTrue(
            benchmark.metrics.all {
                (it as? ReportBenchmarkMetric.Counter)?.delta == null
            },
        )
    }

    private fun builder(reader: FakeReader): DiagnosticReportBuilder = DiagnosticReportBuilder(
        reader,
        DiagnosticReportEnvironment(
            ReportRuntimeMetadata("test", "1"),
            ReportPlatformMetadata(35, "x86_64", "test", "model"),
            Clock.fixed(Instant.parse("2026-08-25T10:15:30Z"), ZoneOffset.UTC),
        ),
    )

    private fun selection(high: ULong, low: ULong, generation: UInt) = ReportSessionSelection(
        high,
        low,
        generation,
        "Host",
    )

    private fun snapshot(timestamp: ULong, vararg records: TelemetrySnapshotRecord) = TelemetrySnapshot(
        TelemetrySnapshotStatus.Complete,
        timestamp,
        timestamp,
        records.toList(),
        emptySet(),
    )

    private fun counter(source: UInt, value: ULong, selection: ReportSessionSelection): TelemetrySnapshotRecord =
        TelemetrySnapshotRecord(
            TelemetrySourceId(source),
            scope(selection),
            TelemetryMetricIds.UdpDatagramSent,
            TelemetryMetricKind.CounterU64,
            TelemetryMetricValue.Counter(value),
        )

    private fun histogram(
        source: UInt,
        selection: ReportSessionSelection,
        boundaries: ULongArray,
        buckets: ULongArray,
        count: ULong,
        sum: ULong,
    ): TelemetrySnapshotRecord = TelemetrySnapshotRecord(
        TelemetrySourceId(source),
        scope(selection),
        TelemetryMetricIds.TransportOneWay,
        TelemetryMetricKind.HistogramU64,
        TelemetryMetricValue.Histogram(count, sum, 1u, 99u, buckets),
    )

    private fun scope(selection: ReportSessionSelection) = TelemetryScope.Channel(
        SessionId.requireValid(selection.sessionIdHigh, selection.sessionIdLow),
        SessionGeneration.requireValid(selection.generation),
        ChannelId.requireValid(1u),
        SessionChannelKind.Video,
        SessionChannelDirection.HostToClient,
    )

    private class FakeReader(initial: TelemetrySnapshot) : DiagnosticReportReader {
        var nextSnapshot = initial
        var snapshots = 0
        override fun telemetrySnapshot(): TelemetrySnapshot {
            snapshots++
            return nextSnapshot
        }
        override fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot =
            DiagnosticEventSnapshot(
                DiagnosticEventBatch.empty(cursor.kotlinSequence),
                NativeDiagnosticEventBatch(
                    DiagnosticEventProviderStatus.Disabled,
                    DiagnosticEventBatch.empty(cursor.nativeSequence),
                ),
            )
    }
}
