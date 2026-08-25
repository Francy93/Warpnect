@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.ui

import io.warpnect.session.ChannelId
import io.warpnect.session.SessionChannelDirection
import io.warpnect.session.SessionChannelKind
import io.warpnect.session.SessionGeneration
import io.warpnect.session.SessionId
import io.warpnect.telemetry.TelemetryMetricIds
import io.warpnect.telemetry.TelemetryMetricKind
import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetryScope
import io.warpnect.telemetry.TelemetrySnapshotRecord
import io.warpnect.telemetry.TelemetrySourceId
import io.warpnect.telemetry.TelemetryUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsPresentationTest {
    @Test
    fun counterRateUsesOnlySameSourceNonDecreasingCounters() {
        val previous = counterRecord(TelemetrySourceId(10u), 100u)
        val current = counterRecord(TelemetrySourceId(10u), 160u)

        val rate = DiagnosticsRateCalculator.counterRate(previous, current, 1_000_000_000u)

        assertEquals(60.0, rate!!.valuePerSecond, 0.001)
        assertEquals("60 packets/s", rate.label)
        assertNull(
            DiagnosticsRateCalculator.counterRate(
                counterRecord(TelemetrySourceId(11u), 100u),
                current,
                1_000_000_000u,
            ),
        )
        assertNull(DiagnosticsRateCalculator.counterRate(current, previous, 1_000_000_000u))
        assertNull(DiagnosticsRateCalculator.counterRate(previous, current, 0u))
    }

    @Test
    fun histogramUsesBucketUpperBoundsAndInfinityTruthfully() {
        val summary = DiagnosticsHistogramFormatter.summary(
            TelemetryMetricValue.Histogram(
                count = 10u,
                sum = 190u,
                min = 1u,
                max = 100u,
                bucketCounts = ulongArrayOf(5u, 4u, 1u),
            ),
            ulongArrayOf(10u, 20u),
            TelemetryUnit.Microseconds,
        )

        assertEquals("<= 10 us", summary.p50)
        assertEquals("> 20 us", summary.p95)
        assertEquals("> 20 us", summary.p99)
        assertEquals("19 us", summary.average)
    }

    @Test
    fun invalidGaugeAndUnitFormattingDoNotBecomeHealthyZeroes() {
        assertEquals("Unavailable", formatGauge(false, 0L))
        assertEquals("1.5 KiB", DiagnosticsValueFormatter.value(1_536u, TelemetryUnit.Bytes))
        assertEquals("+1.2 ms", DiagnosticsValueFormatter.signed(1_200L, TelemetryUnit.Microseconds))
        assertEquals("-340 us", DiagnosticsValueFormatter.signed(-340L, TelemetryUnit.Microseconds))
        assertEquals("2 packets/s", DiagnosticsValueFormatter.rate(2.0, TelemetryUnit.Packets))
    }

    private fun formatGauge(valid: Boolean, value: Long): String =
        if (valid) DiagnosticsValueFormatter.signed(value, TelemetryUnit.Microseconds) else "Unavailable"

    private fun counterRecord(sourceId: TelemetrySourceId, value: ULong): TelemetrySnapshotRecord =
        TelemetrySnapshotRecord(
            sourceId = sourceId,
            scope = TelemetryScope.Channel(
                SessionId.requireValid(1u, 2u),
                SessionGeneration.requireValid(1u),
                ChannelId.requireValid(1u),
                SessionChannelKind.Video,
                SessionChannelDirection.HostToClient,
            ),
            metricId = TelemetryMetricIds.UdpDatagramSent,
            kind = TelemetryMetricKind.CounterU64,
            value = TelemetryMetricValue.Counter(value),
        )
}
