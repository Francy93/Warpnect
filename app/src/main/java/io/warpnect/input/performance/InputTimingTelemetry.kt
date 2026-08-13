package io.warpnect.input.performance

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * A fixed, logarithmic microsecond histogram for cold snapshot diagnostics.
 *
 * Recording is allocation-free and lock-free. Percentile values are bucket upper bounds, which is
 * intentionally sufficient for low-overhead input-path diagnostics rather than a tracing system.
 */
data class InputTimingDistribution(
    val count: Long = 0L,
    val minUs: Long? = null,
    val meanUs: Long? = null,
    val p50Us: Long? = null,
    val p90Us: Long? = null,
    val p95Us: Long? = null,
    val p99Us: Long? = null,
    val maxUs: Long? = null,
)

class BoundedInputTimingHistogram {
    private val buckets = AtomicLongArray(BUCKET_COUNT)
    private val count = AtomicLong(0L)
    private val totalUs = AtomicLong(0L)
    private val minimumUs = AtomicLong(Long.MAX_VALUE)
    private val maximumUs = AtomicLong(0L)

    fun recordElapsedNs(elapsedNs: Long) {
        val elapsedUs = if (elapsedNs <= 0L) {
            0L
        } else {
            (elapsedNs + NANOS_PER_MICROSECOND - 1L) /
                NANOS_PER_MICROSECOND
        }
        recordUs(elapsedUs)
    }

    fun recordUs(elapsedUs: Long) {
        val value = elapsedUs.coerceAtLeast(0L)
        buckets.incrementAndGet(bucketFor(value))
        count.incrementAndGet()
        totalUs.addAndGet(value)
        updateMinimum(value)
        updateMaximum(value)
    }

    fun snapshot(): InputTimingDistribution {
        val observedCount = count.get()
        if (observedCount == 0L) return InputTimingDistribution()
        return InputTimingDistribution(
            count = observedCount,
            minUs = minimumUs.get(),
            meanUs = totalUs.get() / observedCount,
            p50Us = percentileUpperBound(observedCount, 50),
            p90Us = percentileUpperBound(observedCount, 90),
            p95Us = percentileUpperBound(observedCount, 95),
            p99Us = percentileUpperBound(observedCount, 99),
            maxUs = maximumUs.get(),
        )
    }

    fun clear() {
        for (index in 0 until BUCKET_COUNT) buckets.set(index, 0L)
        count.set(0L)
        totalUs.set(0L)
        minimumUs.set(Long.MAX_VALUE)
        maximumUs.set(0L)
    }

    private fun percentileUpperBound(observedCount: Long, percentile: Int): Long {
        val target = (observedCount * percentile + 99L) / 100L
        var cumulative = 0L
        for (index in 0 until BUCKET_COUNT) {
            cumulative += buckets.get(index)
            if (cumulative >= target) return bucketUpperBound(index)
        }
        return maximumUs.get()
    }

    private fun updateMinimum(value: Long) {
        while (true) {
            val current = minimumUs.get()
            if (value >= current || minimumUs.compareAndSet(current, value)) return
        }
    }

    private fun updateMaximum(value: Long) {
        while (true) {
            val current = maximumUs.get()
            if (value <= current || maximumUs.compareAndSet(current, value)) return
        }
    }

    private fun bucketFor(value: Long): Int {
        if (value == 0L) return 0
        val exponent = Long.SIZE_BITS - java.lang.Long.numberOfLeadingZeros(value)
        return exponent.coerceAtMost(BUCKET_COUNT - 1)
    }

    private fun bucketUpperBound(index: Int): Long = when {
        index == 0 -> 0L
        index >= Long.SIZE_BITS - 1 -> Long.MAX_VALUE
        else -> (1L shl index) - 1L
    }

    private companion object {
        const val NANOS_PER_MICROSECOND = 1_000L
        const val BUCKET_COUNT = Long.SIZE_BITS
    }
}
