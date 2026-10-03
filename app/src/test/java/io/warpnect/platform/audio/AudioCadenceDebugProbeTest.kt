package io.warpnect.platform.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCadenceDebugProbeTest {
    @Test fun relativeNominalPhaseSeparatesArrivalDelayFromSequenceLoss() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("receiver", events::add)
        probe.observe(0, 1)
        probe.observe(240, 45_000_001)
        probe.observe(480, 45_100_001)
        probe.observe(240_000, 5_000_000_001)
        assertTrue(events.single().contains("nominal_phase_min_ns=0 nominal_phase_max_ns=40000000"))
    }

    @Test fun mediaFrameDurationDoesNotPretendToBeWallClockPacketCadence() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("mono_10ms", events::add, 480)
        probe.observe(0, 1)
        probe.observe(480, 20_000_001)
        probe.observe(960, 5_000_000_001)
        assertTrue(events.single().contains("missing_units=0"))
        assertTrue(events.single().contains("max_gap_ns=4980000000"))
    }

    @Test fun separatesMissingDuplicateAndReorderedPositionsFromArrivalGaps() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("receiver", events::add)
        probe.observe(0, 1)
        probe.observe(480, 5_000_001)
        probe.observe(480, 6_000_001)
        probe.observe(240, 7_000_001)
        probe.observe(480, 5_000_000_001)
        assertTrue(events.single().contains("missing_units=1 reordered=1 duplicates=1"))
        assertTrue(events.single().contains("interval_bins_lt1_3_7_10_20_40_else_ms=0,2,1,0,0,0,1"))
    }

    @Test fun doesNotDeriveCadenceFromMissingOrBackwardClockSamples() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("kernel_REALTIME", events::add, 1)
        probe.observe(1, 0)
        probe.observe(1, 10)
        probe.observe(2, 9)
        probe.observe(2, 5_000_000_010)
        assertTrue(events.single().contains("missing_units=0"))
        assertTrue(events.single().contains("count=2"))
    }

    @Test fun reportsLocalBatchingWithoutUsingRemoteTimestamps() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("receiver", events::add)
        probe.observe(0, 1)
        probe.observe(240, 500_000)
        probe.observe(480, 5_000_000_001)
        assertTrue(events.single().contains("max_burst=2"))
        assertTrue(events.single().contains("last_position=480"))
        assertTrue(events.single().contains("max_gap_ns=4999500001"))
    }

    @Test fun observationsAreBoundedAndDoNotChangeFrameIdentity() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("sender", events::add)
        repeat(20) { probe.observe(it * 240L, 1L + it * 5_000_000_000L) }
        assertTrue(events.size == 12)
    }

    @Test fun extendedQualityObservationRemainsBoundedToTwentyFourWindows() {
        val events = mutableListOf<String>()
        val probe = AudioCadenceDebugProbe("sender", events::add, maxWindows = 24)
        repeat(30) { probe.observe(it * 240L, 1L + it * 5_000_000_000L) }
        assertTrue(events.size == 24)
        assertTrue(events.last().contains("window=23"))
    }
}
