@file:OptIn(ExperimentalUnsignedTypes::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

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
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportControllerTest {
    @Test
    fun stopClaimsTheBenchmarkOnceAndWinsOverConcurrentCancellation() = runTest {
        val selection = selection()
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, 100u, selection)))
        val cache = Files.createTempDirectory("warpnect-report-test").toFile()
        val controller = controller(reader, cache, StandardTestDispatcher(testScheduler))

        assertTrue(controller.startBenchmark(selection))
        assertFalse(controller.startBenchmark(selection))
        advanceUntilIdle()
        assertEquals(1, reader.snapshotCalls)

        reader.nextSnapshot = snapshot(2_000_000_000u, counter(7u, 160u, selection))
        assertTrue(controller.stopBenchmark())
        assertFalse(controller.stopBenchmark())
        controller.cancelBenchmark()
        advanceUntilIdle()

        assertEquals(2, reader.snapshotCalls)
        assertEquals(ReportExportPhase.Prepared, controller.state.value.phase)
        assertEquals(BenchmarkCaptureStatus.ReadyToExport, controller.state.value.benchmark)

        controller.destinationCancelled()
        assertEquals(ReportExportPhase.Idle, controller.state.value.phase)
        assertEquals(BenchmarkCaptureStatus.Idle, controller.state.value.benchmark)
        controller.close()
        cache.deleteRecursively()
    }

    @Test
    fun writeFailureAndPickerCancellationReleaseTheSingleExportGate() = runTest {
        val selection = selection()
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, 100u, selection)))
        val cache = Files.createTempDirectory("warpnect-report-test").toFile()
        val controller = controller(reader, cache, StandardTestDispatcher(testScheduler))

        assertTrue(controller.prepareDiagnosticsSnapshot(selection))
        advanceUntilIdle()
        assertEquals(ReportExportPhase.Prepared, controller.state.value.phase)
        assertTrue(controller.beginDestinationChoice()!!.startsWith("warpnect-diagnostics-"))
        assertTrue(controller.writePrepared(ReportDestination { throw IOException("synthetic destination failure") }))
        advanceUntilIdle()
        assertEquals(ReportExportPhase.Failed, controller.state.value.phase)

        assertTrue(controller.prepareDiagnosticsSnapshot(selection))
        advanceUntilIdle()
        assertEquals(ReportExportPhase.Prepared, controller.state.value.phase)
        controller.beginDestinationChoice()
        controller.destinationCancelled()
        assertEquals(ReportExportPhase.Idle, controller.state.value.phase)
        controller.close()
        cache.deleteRecursively()
    }

    @Test
    fun temporaryFileFailureIsReportedWithoutStrandingTheController() = runTest {
        val selection = selection()
        val reader = FakeReader(snapshot(1_000_000_000u, counter(7u, 100u, selection)))
        val nonDirectory = Files.createTempFile("warpnect-report-test", ".tmp").toFile()
        val controller = controller(reader, nonDirectory, StandardTestDispatcher(testScheduler))

        assertTrue(controller.prepareDiagnosticsSnapshot(selection))
        advanceUntilIdle()

        assertEquals(ReportExportPhase.Failed, controller.state.value.phase)
        assertTrue(controller.state.value.message!!.contains("preparation"))
        controller.close()
        nonDirectory.delete()
    }

    private fun controller(
        reader: FakeReader,
        cache: java.io.File,
        dispatcher: CoroutineDispatcher,
    ): ReportExportController = ReportExportController(
        DiagnosticReportBuilder(
            reader,
            DiagnosticReportEnvironment(
                ReportRuntimeMetadata("test", "1"),
                ReportPlatformMetadata(35, "x86_64", "test", "model"),
                Clock.fixed(Instant.parse("2026-08-25T10:15:30Z"), ZoneOffset.UTC),
            ),
        ),
        cache,
        dispatcher,
        Clock.fixed(Instant.parse("2026-08-25T10:15:30Z"), ZoneOffset.UTC),
    )

    private fun selection() = ReportSessionSelection(11u, 22u, 1u, "Host")

    private fun snapshot(timestamp: ULong, vararg records: TelemetrySnapshotRecord): TelemetrySnapshot =
        TelemetrySnapshot(
            TelemetrySnapshotStatus.Complete,
            timestamp,
            timestamp,
            records.toList(),
            emptySet(),
        )

    private fun counter(sourceId: UInt, value: ULong, selection: ReportSessionSelection): TelemetrySnapshotRecord =
        TelemetrySnapshotRecord(
            TelemetrySourceId(sourceId),
            TelemetryScope.Channel(
                SessionId.requireValid(selection.sessionIdHigh, selection.sessionIdLow),
                SessionGeneration.requireValid(selection.generation),
                ChannelId.requireValid(1u),
                SessionChannelKind.Video,
                SessionChannelDirection.HostToClient,
            ),
            TelemetryMetricIds.UdpDatagramSent,
            TelemetryMetricKind.CounterU64,
            TelemetryMetricValue.Counter(value),
        )

    private class FakeReader(initial: TelemetrySnapshot) : DiagnosticReportReader {
        var nextSnapshot = initial
        var snapshotCalls = 0

        override fun telemetrySnapshot(): TelemetrySnapshot {
            snapshotCalls++
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
