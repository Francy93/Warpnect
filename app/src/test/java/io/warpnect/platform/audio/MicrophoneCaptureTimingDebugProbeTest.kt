package io.warpnect.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneCaptureTimingDebugProbeTest {
    @Test fun separatesBlockingReadFromTimestampAndSynchronousSinkWork() {
        val events = mutableListOf<String>()
        val probe = MicrophoneCaptureTimingDebugProbe(events::add)
        probe.observe(0, 1, 20_000_001, 21_000_001, 23_000_001)
        probe.observe(240, 5_000_000_001, 5_000_010_001, 5_000_020_001, 5_000_030_001)
        assertTrue(events.single().contains("max_read_ns=20000000 max_post_read_ns=1000000 max_sink_ns=2000000"))
        assertTrue(events.single().contains("read_bins_lt1_3_7_10_20_40_else_ms=1,0,0,0,0,1,0"))
    }

    @Test fun invalidLocalClockOrderIsSkippedAndOutputIsBounded() {
        val events = mutableListOf<String>()
        val probe = MicrophoneCaptureTimingDebugProbe(events::add)
        probe.observe(0, 1, 0, 3, 4)
        repeat(30) { index ->
            val start = 1L + index * 5_000_000_000L
            probe.observe(index * 240L, start, start + 1, start + 2, start + 3)
        }
        assertEquals(24, events.size)
    }

    @Test fun slowEventsSeparatePacingFromSinkAndAreBounded() {
        val events = mutableListOf<String>()
        val probe = MicrophoneCaptureTimingDebugProbe(events::add)
        repeat(40) {
            probe.observeSlowChunk(240, 1, 2_000_001, 3_000_001, 8_000_001, 28_000_001, 4_000_000)
        }
        assertEquals(32, events.size)
        assertTrue(
            events.first().contains(
                "read_ns=2000000 post_read_ns=1000000 pacing_ns=5000000 sink_ns=20000000 cpu_ns=4000000",
            ),
        )
        probe.observeSlowChunk(480, 1, 0, 3, 4, 5, 0)
        assertEquals(32, events.size)
    }
}
