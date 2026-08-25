@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics

import io.warpnect.diagnostics.report.DiagnosticReportBuilder
import io.warpnect.diagnostics.report.DiagnosticReportEnvironment
import io.warpnect.diagnostics.report.HubDiagnosticReportReader
import io.warpnect.diagnostics.report.ReportPlatformMetadata
import io.warpnect.diagnostics.report.ReportRuntimeMetadata
import io.warpnect.diagnostics.report.ReportSessionSelection
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.session.security.SessionProtectionError
import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.SessionControlNetworkTelemetry
import io.warpnect.telemetry.SessionLifecycleTelemetry
import io.warpnect.telemetry.TelemetryHub
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshotError
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6DiagnosticsIntegrationTest {
    @Test
    fun networkFloodRemainsAggregateTelemetryRatherThanDiagnosticHistory() {
        val session = sessionScope()
        val telemetry = TelemetryHub()
        val events = DiagnosticEventHub(
            FixedClock(1u),
            ClockDomainId.AndroidBootTime,
            telemetry,
        )
        val control = SessionControlNetworkTelemetry.register(telemetry, session)
        val writer = events.writer(session)
        val before = events.snapshotSince().kotlin.events.size
        assertTrue(writer.emit(DiagnosticEventIds.SessionRunning))

        repeat(2_048) {
            control.udpReceived(128)
            control.recordUnprotectError(SessionProtectionError.AuthFailure)
        }

        val metrics = telemetry.snapshot().records.associateBy { it.metricId }
        assertEquals(
            2_048uL,
            (
                metrics.getValue(TelemetryMetricIds.ProtectionAuthenticationFailed).value as
                    TelemetryMetricValue.Counter
                ).value,
        )
        assertEquals(
            2_048uL,
            (
                metrics.getValue(TelemetryMetricIds.UdpDatagramReceived).value as
                    TelemetryMetricValue.Counter
                ).value,
        )
        assertEquals(before + 1, events.snapshotSince().kotlin.events.size)

        control.close()
        events.close()
        telemetry.close()
    }

    @Test
    fun nativeProviderFailureLeavesKotlinDiagnosticsAndReportTruthfullyPartial() {
        val session = sessionScope()
        val telemetry = TelemetryHub()
        telemetry.registerProvider { error("synthetic WNTM failure") }
        val events = DiagnosticEventHub(
            FixedClock(10u),
            ClockDomainId.AndroidBootTime,
            telemetry,
            NativeDiagnosticEventProvider { _, _ ->
                NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Failed, DiagnosticEventBatch.empty(0u))
            },
        )
        val lifecycle = SessionLifecycleTelemetry.register(telemetry, session)
        lifecycle.migrationStarted.increment()
        assertTrue(events.writer(session).emit(DiagnosticEventIds.MigrationStarted, 1u, 2u))

        val report = reportBuilder(telemetry, events).captureSnapshot(selection())

        assertTrue(report.capture.partial)
        assertTrue(TelemetrySnapshotError.ProviderFailure.name in report.capture.telemetryErrors)
        assertEquals("failed", report.nativeEvents.status)
        assertTrue(
            report.applicationEvents.events.any {
                it.canonicalName == "warpnect.event.session.path_migration_started"
            },
        )
        assertTrue(report.telemetry.any { it.name == "warpnect.session.path_migration.started" })

        lifecycle.close()
        events.close()
        telemetry.close()
    }

    @Test
    fun benchmarkReportsEventHistoryGapAfterBoundedRingOverwrite() {
        val session = sessionScope()
        val telemetry = TelemetryHub()
        val events = DiagnosticEventHub(
            FixedClock(20u),
            ClockDomainId.AndroidBootTime,
            telemetry,
        )
        val lifecycle = SessionLifecycleTelemetry.register(telemetry, session)
        val writer = events.writer(session)
        val builder = reportBuilder(telemetry, events)
        val baseline = builder.startBenchmark(selection())

        repeat(1_025) { assertTrue(writer.emit(DiagnosticEventIds.SessionRunning)) }
        lifecycle.heartbeatSent.increment()
        val report = builder.stopBenchmark(baseline)

        assertTrue(report.applicationEvents.gapDetected)
        assertTrue(report.capture.partial)
        assertEquals("completed", report.benchmark!!.status)
        assertTrue(
            report.benchmark.metrics.any {
                it.name == "warpnect.session.heartbeat.sent" &&
                    (it as io.warpnect.diagnostics.report.ReportBenchmarkMetric.Counter).delta == "1"
            },
        )

        lifecycle.close()
        events.close()
        telemetry.close()
    }

    private fun reportBuilder(telemetry: TelemetryHub, events: DiagnosticEventHub) = DiagnosticReportBuilder(
        HubDiagnosticReportReader(telemetry, events),
        DiagnosticReportEnvironment(
            ReportRuntimeMetadata("test", "1"),
            ReportPlatformMetadata(35, "x86_64", "test", "model"),
            Clock.fixed(Instant.parse("2026-08-25T10:15:30Z"), ZoneOffset.UTC),
        ),
    )

    private fun sessionScope() = TelemetryScope.Session(
        SessionId.requireValid(11u, 22u),
        SessionGeneration.requireValid(1u),
    )

    private fun selection() = ReportSessionSelection(11u, 22u, 1u, "Host")

    private class FixedClock(private val value: ULong) : DiagnosticEventClock {
        override fun nowNs(): ULong = value
    }
}
