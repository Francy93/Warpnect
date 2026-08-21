@file:OptIn(ExperimentalUnsignedTypes::class)

package io.warpnect.telemetry

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

interface TelemetryCounterHandle {
    fun increment()

    fun add(delta: ULong)
}

interface TelemetryGaugeHandle {
    fun set(value: Long)

    fun clear()
}

interface TelemetryHistogramHandle {
    fun record(value: ULong)
}

internal object DisabledTelemetryCounter : TelemetryCounterHandle {
    override fun increment() = Unit

    override fun add(delta: ULong) = Unit
}

internal object DisabledTelemetryGauge : TelemetryGaugeHandle {
    override fun set(value: Long) = Unit

    override fun clear() = Unit
}

internal object DisabledTelemetryHistogram : TelemetryHistogramHandle {
    override fun record(value: ULong) = Unit
}

/** Saturating unsigned counter backed by an AtomicLong bit pattern. */
class TelemetryCounterU64(
    private val onOverflow: () -> Unit = {},
) : TelemetryCounterHandle {
    private val value = AtomicLong(0L)
    private val overflowReported = AtomicBoolean(false)

    override fun increment() = add(1u)

    override fun add(delta: ULong) {
        if (delta == 0uL) return
        while (true) {
            val currentBits = value.get()
            val current = currentBits.toULong()
            val saturated = ULong.MAX_VALUE - current < delta
            val next = if (saturated) ULong.MAX_VALUE else current + delta
            if (value.compareAndSet(currentBits, next.toLong())) {
                if (saturated && overflowReported.compareAndSet(false, true)) onOverflow()
                return
            }
        }
    }

    fun snapshot(): ULong = value.get().toULong()
}

data class TelemetryGaugeValue(
    val valid: Boolean,
    val value: Long,
)

class TelemetryGaugeI64 : TelemetryGaugeHandle {
    private val value = AtomicLong(0L)
    private val valid = AtomicBoolean(false)

    override fun set(value: Long) {
        this.value.set(value)
        valid.set(true)
    }

    override fun clear() {
        valid.set(false)
    }

    fun snapshot(): TelemetryGaugeValue = TelemetryGaugeValue(valid.get(), value.get())
}

data class TelemetryHistogramValue(
    val count: ULong,
    val sum: ULong,
    val min: ULong?,
    val max: ULong?,
    val bucketCounts: ULongArray,
)

/** Fixed-boundary histogram. Updates allocate neither containers nor registry entries. */
class TelemetryHistogramU64(
    boundaries: ULongArray,
    private val onOverflow: () -> Unit = {},
) : TelemetryHistogramHandle {
    val boundaries: ULongArray = boundaries.copyOf()
    private val buckets = AtomicLongArray(this.boundaries.size + 1)
    private val count = TelemetryCounterU64(onOverflow)
    private val sum = TelemetryCounterU64(onOverflow)
    private val minimum = AtomicLong(-1L)
    private val maximum = AtomicLong(0L)
    private val bucketOverflowReported = AtomicBoolean(false)

    init {
        require(this.boundaries.size <= MAX_TELEMETRY_HISTOGRAM_BOUNDARIES) {
            "Histogram boundary bound exceeded"
        }
        this.boundaries.zipWithNext().forEach { (left, right) ->
            require(left < right) { "Histogram boundaries must be strictly increasing" }
        }
    }

    override fun record(value: ULong) {
        count.increment()
        sum.add(value)
        incrementBucket(bucketIndex(value))
        updateExtrema(value)
    }

    fun snapshot(): TelemetryHistogramValue {
        val countValue = count.snapshot()
        val present = countValue != 0uL
        val copiedBuckets = ULongArray(buckets.length()) { index -> buckets.get(index).toULong() }
        return TelemetryHistogramValue(
            count = countValue,
            sum = sum.snapshot(),
            min = if (present) minimum.get().toULong() else null,
            max = if (present) maximum.get().toULong() else null,
            bucketCounts = copiedBuckets,
        )
    }

    private fun bucketIndex(value: ULong): Int {
        for (index in boundaries.indices) {
            if (value <= boundaries[index]) return index
        }
        return boundaries.size
    }

    private fun updateExtrema(value: ULong) {
        updateMinimum(value.toLong())
        updateMaximum(value.toLong())
    }

    private fun incrementBucket(index: Int) {
        while (true) {
            val current = buckets.get(index)
            if (current.toULong() == ULong.MAX_VALUE) {
                if (bucketOverflowReported.compareAndSet(false, true)) onOverflow()
                return
            }
            if (buckets.compareAndSet(index, current, current + 1)) return
        }
    }

    private fun updateMinimum(candidate: Long) {
        while (true) {
            val current = minimum.get()
            if (current.toULong() <= candidate.toULong()) return
            if (minimum.compareAndSet(current, candidate)) return
        }
    }

    private fun updateMaximum(candidate: Long) {
        while (true) {
            val current = maximum.get()
            if (current.toULong() >= candidate.toULong()) return
            if (maximum.compareAndSet(current, candidate)) return
        }
    }
}
