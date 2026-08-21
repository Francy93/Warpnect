@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTelemetrySnapshotBridgeTest {
    @Test
    fun parsesExactLittleEndianCounterGaugeAndHistogramVector() {
        val bytes = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("WNTM".encodeToByteArray())
        bytes.putShort(1)
        bytes.putShort(32)
        bytes.putLong(8)
        bytes.putLong(9)
        bytes.putInt(3)
        bytes.putInt(200)
        recordHeader(bytes, 1, TelemetryMetricIds.SnapshotCount, TelemetryMetricKind.CounterU64, 8)
        bytes.putLong(42)
        recordHeader(bytes, 1, TelemetryMetricIds.SourceActive, TelemetryMetricKind.GaugeI64, 16)
        bytes.put(1)
        repeat(7) { bytes.put(0) }
        bytes.putLong(-1)
        recordHeader(bytes, 1, TelemetryMetricIds.SnapshotDuration, TelemetryMetricKind.HistogramU64, 96)
        bytes.putLong(7)
        bytes.putLong(28)
        bytes.putLong(1)
        bytes.putLong(7)
        bytes.put(6)
        repeat(7) { bytes.put(0) }
        repeat(7) { bytes.putLong(1) }
        bytes.flip()

        val parsed = NativeTelemetrySnapshotBridge.parse(
            bytes,
            NativeTelemetrySourceScopeResolver { TelemetryScope.Process },
        )

        assertTrue(parsed.errors.isEmpty())
        assertEquals(3, parsed.records.size)
        assertEquals(TelemetryMetricValue.Counter(42u), parsed.records[0].value)
        assertEquals(TelemetryMetricValue.Gauge(true, -1), parsed.records[1].value)
        val histogram = parsed.records[2].value as TelemetryMetricValue.Histogram
        assertEquals(7uL, histogram.count)
        assertTrue(
            histogram.bucketCounts.contentEquals(ulongArrayOf(1u, 1u, 1u, 1u, 1u, 1u, 1u)),
        )
    }

    @Test
    fun rejectsMalformedBridgeWithoutAffectingOtherTelemetry() {
        val bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("BAD!".encodeToByteArray())
        bytes.position(0)

        val parsed = NativeTelemetrySnapshotBridge.parse(
            bytes,
            NativeTelemetrySourceScopeResolver { TelemetryScope.Process },
        )

        assertTrue(TelemetrySnapshotError.MalformedNativeSnapshot in parsed.errors)
        assertTrue(parsed.records.isEmpty())
    }

    private fun recordHeader(
        output: ByteBuffer,
        sourceId: Int,
        metricId: TelemetryMetricId,
        kind: TelemetryMetricKind,
        payloadBytes: Int,
    ) {
        output.putInt(sourceId)
        output.putShort(metricId.value.toShort())
        output.put(kind.bridgeId.toByte())
        output.put(0)
        output.putShort(payloadBytes.toShort())
        output.putShort(0)
        output.putInt(0)
    }
}
