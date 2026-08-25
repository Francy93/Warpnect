@file:OptIn(ExperimentalUnsignedTypes::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.warpnect.diagnostics.ui

import io.warpnect.diagnostics.DiagnosticEventBatch
import io.warpnect.diagnostics.DiagnosticEventCursor
import io.warpnect.diagnostics.DiagnosticEventIds
import io.warpnect.diagnostics.DiagnosticEventProviderStatus
import io.warpnect.diagnostics.DiagnosticEventRecord
import io.warpnect.diagnostics.DiagnosticEventSnapshot
import io.warpnect.diagnostics.DiagnosticScopeKind
import io.warpnect.diagnostics.DiagnosticScopeSnapshot
import io.warpnect.diagnostics.DiagnosticSeverity
import io.warpnect.diagnostics.NativeDiagnosticEventBatch
import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.telemetry.ClockDomainId
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricKind
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshot
import io.warpnect.telemetry.TelemetrySnapshotError
import io.warpnect.telemetry.TelemetrySnapshotRecord
import io.warpnect.telemetry.TelemetrySnapshotStatus
import io.warpnect.telemetry.TelemetrySourceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsUiControllerTest {
    @Test
    fun refreshCoalescesAndPauseStillAllowsManualRefresh() = runTest {
        val reader = FakeReader(telemetry = snapshot(1_000_000_000u, counter(10u, 100u)))
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        assertTrue(controller.requestRefresh())
        assertFalse(controller.requestRefresh())
        advanceUntilIdle()
        assertEquals(1, reader.telemetryCalls)

        controller.setPaused(true)
        controller.manualRefresh()
        advanceUntilIdle()
        assertEquals(2, reader.telemetryCalls)
        assertTrue(controller.state.value.paused)
        controller.close()
    }

    @Test
    fun screenScopedSamplingUsesDefaultCadenceAndStops() = runTest {
        val reader = FakeReader(telemetry = snapshot(1_000_000_000u, counter(10u, 100u)))
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.startSampling()
        runCurrent()
        assertEquals(1, reader.telemetryCalls)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(1, reader.telemetryCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, reader.telemetryCalls)
        controller.stopSampling()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, reader.telemetryCalls)
        controller.close()
    }

    @Test
    fun sourceReplacementBreaksCounterRateAndDisabledTelemetryIsVisible() = runTest {
        val reader = FakeReader(telemetry = snapshot(1_000_000_000u, counter(10u, 100u)))
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()
        reader.telemetry = snapshot(2_000_000_000u, counter(11u, 160u))
        controller.manualRefresh()
        advanceUntilIdle()
        val current = controller.state.value.network.single {
            it.metricId == TelemetryMetricIds.UdpDatagramSent.value
        }
        assertNull(current.rate)

        reader.telemetry = TelemetrySnapshot(
            TelemetrySnapshotStatus.Disabled,
            3u,
            3_000_000_000u,
            emptyList(),
            setOf(TelemetrySnapshotError.Disabled),
        )
        controller.manualRefresh()
        advanceUntilIdle()
        assertEquals(DiagnosticsUiPhase.Disabled, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun partialSnapshotAndUnsupportedLatencyRemainVisible() = runTest {
        val reader = FakeReader(
            telemetry = snapshot(
                timestamp = 1_000_000_000u,
                status = TelemetrySnapshotStatus.Partial,
                records = arrayOf(counter(10u, 100u)),
            ),
        )
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()

        assertEquals(DiagnosticsUiPhase.Ready, controller.state.value.phase)
        assertEquals(TelemetrySnapshotStatus.Partial, controller.state.value.snapshotStatus)
        assertTrue(controller.state.value.latency.unavailable.any { it.startsWith("Transport one-way") })
        controller.close()
    }

    @Test
    fun refreshFailureRetainsTheLastValidUiState() = runTest {
        val reader = FakeReader(telemetry = snapshot(1_000_000_000u, counter(10u, 100u)))
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()
        reader.throwOnTelemetry = true
        controller.manualRefresh()
        advanceUntilIdle()

        assertEquals(DiagnosticsUiPhase.Ready, controller.state.value.phase)
        assertTrue(controller.state.value.refreshFailed)
        assertEquals(1, controller.state.value.network.size)
        controller.close()
    }

    @Test
    fun changingRefreshIntervalReplacesTheScreenScheduler() = runTest {
        val reader = FakeReader(telemetry = snapshot(1_000_000_000u, counter(10u, 100u)))
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.startSampling()
        runCurrent()
        controller.setRefreshInterval(DiagnosticsRefreshInterval.Slow)
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(1, reader.telemetryCalls)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, reader.telemetryCalls)
        controller.close()
    }

    @Test
    fun unavailableEventHistoryDoesNotHideMetrics() = runTest {
        val reader = FakeReader(
            telemetry = snapshot(1_000_000_000u, counter(10u, 100u)),
            eventHistoryEnabled = false,
        )
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()

        assertEquals("Unavailable", controller.state.value.events.applicationStatus)
        assertEquals(1, controller.state.value.network.size)
        controller.close()
    }

    @Test
    fun cursorsPreserveProviderOrderGapAndBoundedUiRetention() = runTest {
        val firstApplication = listOf(event(12u), event(10u))
        val firstNative = listOf(event(51u), event(50u))
        val reader = FakeReader(
            telemetry = snapshot(1_000_000_000u, counter(10u, 100u)),
            events = eventSnapshot(firstApplication, firstNative, appGap = true),
        )
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()
        assertEquals(listOf(10uL, 12uL), controller.state.value.events.application.map { it.sequence })
        assertEquals(listOf(50uL, 51uL), controller.state.value.events.native.map { it.sequence })
        assertTrue(controller.state.value.events.applicationGap)
        assertEquals(DiagnosticEventCursor(), reader.cursors.single())

        reader.events = eventSnapshot((13..524).map { event(it.toULong()) }, emptyList())
        controller.manualRefresh()
        advanceUntilIdle()
        assertEquals(DiagnosticEventCursor(12u, 51u), reader.cursors.last())
        assertEquals(512, controller.state.value.events.application.size)
        assertEquals(13uL, controller.state.value.events.application.first().sequence)
        assertEquals(524uL, controller.state.value.events.application.last().sequence)
        controller.close()
    }

    @Test
    fun sessionProjectionHidesSessionIdentityAndFiltersGenerationSelection() = runTest {
        val reader = FakeReader(
            telemetry = snapshot(
                1_000_000_000u,
                counter(10u, 10u, sessionHigh = 987_654u, sessionLow = 123_456u, generation = 1u),
                counter(11u, 20u, sessionHigh = 555_555u, sessionLow = 444_444u, generation = 2u),
            ),
        )
        val controller = controller(reader, StandardTestDispatcher(testScheduler))

        controller.requestRefresh()
        advanceUntilIdle()
        assertEquals(2, controller.state.value.sessions.size)
        assertFalse(controller.state.value.raw.joinToString { it.scope.summary }.contains("987654"))
        val generationTwo = controller.state.value.sessions.single { it.generation == 2u }.key
        controller.selectSession(generationTwo)
        assertEquals(generationTwo, controller.state.value.selectedSession)
        assertTrue(controller.state.value.network.all { it.scope.session == null || it.scope.session == generationTwo })
        controller.close()
    }

    private fun controller(reader: FakeReader, dispatcher: CoroutineDispatcher): DiagnosticsUiController =
        DiagnosticsUiController(
            reader = reader,
            clock = DiagnosticsUiClock { 4_000_000_000u },
            dispatcher = dispatcher,
        )

    private fun snapshot(
        timestamp: ULong,
        status: TelemetrySnapshotStatus = TelemetrySnapshotStatus.Complete,
        records: Array<out TelemetrySnapshotRecord>,
    ): TelemetrySnapshot = TelemetrySnapshot(status, 1u, timestamp, records.toList(), emptySet())

    private fun snapshot(timestamp: ULong, vararg records: TelemetrySnapshotRecord): TelemetrySnapshot =
        snapshot(timestamp, records = records)

    private fun counter(
        sourceId: UInt,
        value: ULong,
        sessionHigh: ULong = 1u,
        sessionLow: ULong = 2u,
        generation: UInt = 1u,
    ): TelemetrySnapshotRecord = TelemetrySnapshotRecord(
        sourceId = TelemetrySourceId(sourceId),
        scope = TelemetryScope.Channel(
            SessionId.requireValid(sessionHigh, sessionLow),
            SessionGeneration.requireValid(generation),
            ChannelId.requireValid(1u),
            SessionChannelKind.Video,
            SessionChannelDirection.HostToClient,
        ),
        metricId = TelemetryMetricIds.UdpDatagramSent,
        kind = TelemetryMetricKind.CounterU64,
        value = TelemetryMetricValue.Counter(value),
    )

    private fun event(sequence: ULong): DiagnosticEventRecord = DiagnosticEventRecord(
        sequence = sequence,
        timestampNs = sequence,
        clockDomain = ClockDomainId.AndroidBootTime,
        severity = DiagnosticSeverity.Info,
        scope = DiagnosticScopeSnapshot(DiagnosticScopeKind.Process),
        typeId = DiagnosticEventIds.HistoryStarted,
        payload = ulongArrayOf(),
    )

    private fun eventSnapshot(
        application: List<DiagnosticEventRecord>,
        native: List<DiagnosticEventRecord>,
        appGap: Boolean = false,
    ): DiagnosticEventSnapshot = DiagnosticEventSnapshot(
        kotlin = batch(application, appGap),
        native = NativeDiagnosticEventBatch(DiagnosticEventProviderStatus.Available, batch(native, false)),
    )

    private fun batch(events: List<DiagnosticEventRecord>, gap: Boolean): DiagnosticEventBatch {
        val newest = events.maxOfOrNull { it.sequence } ?: 0u
        return DiagnosticEventBatch(events, events.minOfOrNull { it.sequence } ?: 0u, newest, newest, gap, 0u, false)
    }

    private class FakeReader(
        var telemetry: TelemetrySnapshot,
        var events: DiagnosticEventSnapshot? = null,
        override var eventHistoryEnabled: Boolean = true,
    ) : DiagnosticsSnapshotReader {
        var telemetryCalls = 0
        var throwOnTelemetry = false
        val cursors = mutableListOf<DiagnosticEventCursor>()
        override val telemetryEnabled: Boolean = true

        override fun telemetrySnapshot(): TelemetrySnapshot {
            telemetryCalls += 1
            if (throwOnTelemetry) error("synthetic refresh failure")
            return telemetry
        }

        override fun diagnosticEvents(cursor: DiagnosticEventCursor, limit: Int): DiagnosticEventSnapshot {
            cursors += cursor
            return events ?: DiagnosticEventSnapshot(
                DiagnosticEventBatch.empty(cursor.kotlinSequence),
                NativeDiagnosticEventBatch(
                    DiagnosticEventProviderStatus.Available,
                    DiagnosticEventBatch.empty(cursor.nativeSequence),
                ),
            )
        }
    }
}
