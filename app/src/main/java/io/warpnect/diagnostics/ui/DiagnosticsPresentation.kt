@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.diagnostics.ui

import io.warpnect.telemetry.TelemetryMetricValue
import io.warpnect.telemetry.TelemetrySnapshotRecord
import io.warpnect.telemetry.TelemetryUnit
import kotlin.math.abs

/** Pure cold-path formatting; runtime metric values and their units remain unchanged. */
object DiagnosticsValueFormatter {
    fun value(value: ULong, unit: TelemetryUnit): String = when (unit) {
        TelemetryUnit.Bytes -> bytes(value)
        TelemetryUnit.Microseconds -> durationUs(value)
        TelemetryUnit.Nanoseconds -> durationNs(value)
        TelemetryUnit.Milliseconds -> "$value ms"
        TelemetryUnit.Boolean -> if (value == 0uL) "No" else "Yes"
        else -> "$value ${unitLabel(unit)}"
    }

    fun signed(value: Long, unit: TelemetryUnit): String {
        val sign = if (value > 0L) "+" else if (value < 0L) "-" else ""
        val magnitude = if (value == Long.MIN_VALUE) Long.MAX_VALUE.toULong() + 1u else abs(value).toULong()
        return sign + value(magnitude, unit)
    }

    fun rate(valuePerSecond: Double, unit: TelemetryUnit): String = when (unit) {
        TelemetryUnit.Bytes -> bytesPerSecond(valuePerSecond)
        TelemetryUnit.Packets -> "${decimal(valuePerSecond)} packets/s"
        TelemetryUnit.Frames -> "${decimal(valuePerSecond)} frames/s"
        TelemetryUnit.Events -> "${decimal(valuePerSecond)} events/s"
        TelemetryUnit.Samples -> "${decimal(valuePerSecond)} sample frames/s"
        else -> "${decimal(valuePerSecond)} /s"
    }

    fun unitLabel(unit: TelemetryUnit): String = when (unit) {
        TelemetryUnit.Count -> "count"
        TelemetryUnit.Bytes -> "B"
        TelemetryUnit.BitsPerSecond -> "bit/s"
        TelemetryUnit.Frames -> "frames"
        TelemetryUnit.Packets -> "packets"
        TelemetryUnit.Events -> "events"
        TelemetryUnit.Nanoseconds -> "ns"
        TelemetryUnit.Microseconds -> "us"
        TelemetryUnit.Milliseconds -> "ms"
        TelemetryUnit.Samples -> "sample frames"
        TelemetryUnit.PercentPermille -> "per mille"
        TelemetryUnit.Boolean -> "boolean"
    }

    private fun bytes(value: ULong): String = when {
        value >= 1_048_576u -> "${decimal(value.toDouble() / 1_048_576.0)} MiB"
        value >= 1_024u -> "${decimal(value.toDouble() / 1_024.0)} KiB"
        else -> "$value B"
    }

    private fun bytesPerSecond(value: Double): String = when {
        value >= 1_048_576.0 -> "${decimal(value / 1_048_576.0)} MiB/s"
        value >= 1_024.0 -> "${decimal(value / 1_024.0)} KiB/s"
        else -> "${decimal(value)} B/s"
    }

    private fun durationNs(value: ULong): String = when {
        value >= 1_000_000_000u -> "${decimal(value.toDouble() / 1_000_000_000.0)} s"
        value >= 1_000_000u -> "${decimal(value.toDouble() / 1_000_000.0)} ms"
        value >= 1_000u -> "${decimal(value.toDouble() / 1_000.0)} us"
        else -> "$value ns"
    }

    private fun durationUs(value: ULong): String = when {
        value >= 1_000_000u -> "${decimal(value.toDouble() / 1_000_000.0)} s"
        value >= 1_000u -> "${decimal(value.toDouble() / 1_000.0)} ms"
        else -> "$value us"
    }

    private fun decimal(value: Double): String = if (value >= 100.0 || value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.1f".format(java.util.Locale.ROOT, value)
    }
}

object DiagnosticsRateCalculator {
    fun counterRate(
        previous: TelemetrySnapshotRecord?,
        current: TelemetrySnapshotRecord,
        elapsedNs: ULong,
    ): DiagnosticsRateUi? {
        val oldCounter = previous?.value as? TelemetryMetricValue.Counter ?: return null
        val newCounter = current.value as? TelemetryMetricValue.Counter ?: return null
        if (previous.sourceId != current.sourceId || previous.metricId != current.metricId ||
            oldCounter.value > newCounter.value || elapsedNs == 0uL || newCounter.value == ULong.MAX_VALUE
        ) {
            return null
        }
        val rate = (newCounter.value - oldCounter.value).toDouble() * 1_000_000_000.0 / elapsedNs.toDouble()
        return DiagnosticsRateUi(rate, DiagnosticsValueFormatter.rate(rate, currentMetricUnit(current)))
    }

    private fun currentMetricUnit(record: TelemetrySnapshotRecord): TelemetryUnit =
        io.warpnect.telemetry.TelemetryDescriptorCatalog.descriptors.firstOrNull { it.id == record.metricId }?.unit
            ?: TelemetryUnit.Count
}

object DiagnosticsHistogramFormatter {
    fun summary(
        value: TelemetryMetricValue.Histogram,
        boundaries: ULongArray,
        unit: TelemetryUnit,
    ): DiagnosticsHistogramUi {
        if (value.count == 0uL) return DiagnosticsHistogramUi(0u, null, null, null, null, null, null)
        val average = DiagnosticsValueFormatter.value(value.sum / value.count, unit)
        return DiagnosticsHistogramUi(
            sampleCount = value.count,
            average = average,
            minimum = value.min?.let { DiagnosticsValueFormatter.value(it, unit) },
            maximum = value.max?.let { DiagnosticsValueFormatter.value(it, unit) },
            p50 = percentile(value.bucketCounts, boundaries, value.count, 0.50, unit),
            p95 = percentile(value.bucketCounts, boundaries, value.count, 0.95, unit),
            p99 = percentile(value.bucketCounts, boundaries, value.count, 0.99, unit),
        )
    }

    private fun percentile(
        buckets: ULongArray,
        boundaries: ULongArray,
        count: ULong,
        percentile: Double,
        unit: TelemetryUnit,
    ): String? {
        if (buckets.isEmpty() || count == 0uL) return null
        val target = kotlin.math.ceil(count.toDouble() * percentile).toULong().coerceAtLeast(1u)
        var seen = 0uL
        buckets.forEachIndexed { index, bucket ->
            seen += bucket
            if (seen >= target) {
                return if (index < boundaries.size) {
                    "<= ${DiagnosticsValueFormatter.value(boundaries[index], unit)}"
                } else {
                    "> ${boundaries.lastOrNull()?.let { DiagnosticsValueFormatter.value(it, unit) } ?: "Unavailable"}"
                }
            }
        }
        return null
    }
}
