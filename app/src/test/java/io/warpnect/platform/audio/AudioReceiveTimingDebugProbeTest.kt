package io.warpnect.platform.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReceiveTimingDebugProbeTest {
    @Test fun keepsKernelRealtimeIntervalsSeparateFromMonotonicProcessing() {
        val events = mutableListOf<String>()
        val probe = AudioReceiveTimingDebugProbe(events::add)
        fun event(sequence: Long, elapsedNs: Long) = LongArray(20).apply {
            this[14] = sequence
            this[15] = 1_800_000_000_000_000_000L + elapsedNs
            this[16] = 10_000L + elapsedNs
            this[17] = 11_000L + elapsedNs
            this[18] = 12_000L
            this[19] = 5_000_000L
        }
        probe.observe(event(1, 0))
        probe.listenerCompleted(9_000L)
        probe.observe(event(2, 5_000_000_000L))
        assertTrue(events.last().contains("max_socket_age_ns=12000 max_authentication_ns=1000"))
        assertTrue(events.last().contains("max_listener_ns=9000"))
        assertTrue(events.any { it.contains("kernel_receive_REALTIME") })
        assertTrue(events.any { it.contains("socket_read_MONOTONIC") })
    }

    @Test fun missingNativeDiagnosticsAreNotReportedAsMeasuredTiming() {
        val events = mutableListOf<String>()
        val probe = AudioReceiveTimingDebugProbe(events::add)
        probe.observe(LongArray(15))
        probe.observe(LongArray(20))
        assertTrue(events.isEmpty())
    }

    @Test fun extendedTimingAndKernelCadenceShareTheSameBoundedWindow() {
        val events = mutableListOf<String>()
        val probe = AudioReceiveTimingDebugProbe(events::add, maxWindows = 24)
        repeat(30) { index ->
            probe.observe(
                LongArray(20).apply {
                    this[14] = index.toLong()
                    this[15] = 1_800_000_000_000_000_000L + index * 5_000_000_000L
                    this[16] = 1 + index * 5_000_000_000L
                    this[17] = this[16]
                },
            )
        }
        assertTrue(events.count { it.startsWith("AUDIO_RECEIVE_TIMING ") } == 24)
        assertTrue(events.count { it.contains("boundary=kernel_receive_REALTIME ") } == 24)
    }

    @Test fun gapDiagnosticsKeepKernelAndReadClocksSeparateAndRemainBounded() {
        val events = mutableListOf<String>()
        val probe = AudioReceiveTimingDebugProbe(events::add)
        repeat(60) { index ->
            probe.observe(
                LongArray(20).apply {
                    this[10] = index * 240L
                    this[14] = index.toLong()
                    this[15] = 1_800_000_000_000_000_000L + index * 25_000_000L
                    this[16] = 1 + index * 30_000_000L
                    this[17] = this[16] + 1_000
                    this[18] = 5_000_000L
                },
            )
        }
        val gaps = events.filter { it.startsWith("AUDIO_RECEIVE_GAP ") }
        assertTrue(gaps.size == 48)
        assertTrue(gaps.first().contains("sequence=1 position=240"))
        assertTrue(gaps.first().contains("read_gap_ns=30000000 kernel_gap_ns=25000000"))
    }
}
