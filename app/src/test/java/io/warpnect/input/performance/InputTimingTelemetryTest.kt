package io.warpnect.input.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputTimingTelemetryTest {
    @Test
    fun fixedHistogramReportsBoundedApproximatePercentilesAndCanReset() {
        val histogram = BoundedInputTimingHistogram()

        histogram.recordUs(0)
        histogram.recordUs(1)
        histogram.recordUs(2)
        histogram.recordElapsedNs(3_001)

        val snapshot = histogram.snapshot()
        assertEquals(4L, snapshot.count)
        assertEquals(0L, snapshot.minUs)
        assertEquals(1L, snapshot.meanUs)
        assertEquals(1L, snapshot.p50Us)
        assertEquals(7L, snapshot.p90Us)
        assertEquals(7L, snapshot.p95Us)
        assertEquals(7L, snapshot.p99Us)
        assertEquals(4L, snapshot.maxUs)

        histogram.clear()
        val cleared = histogram.snapshot()
        assertEquals(0L, cleared.count)
        assertNull(cleared.minUs)
    }
}
