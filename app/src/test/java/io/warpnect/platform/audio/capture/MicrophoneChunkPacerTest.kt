package io.warpnect.platform.audio.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneChunkPacerTest {
    @Test fun fourChunkHalBatchIsDeliveredAtNegotiatedFiveMillisecondCadence() {
        val pacer = MicrophoneChunkPacer(48_000, 960)
        assertEquals(20_000_000L, pacer.deadlineNs(20_000_000, 240))
        assertEquals(25_000_000L, pacer.deadlineNs(20_100_000, 240))
        assertEquals(30_000_000L, pacer.deadlineNs(25_100_000, 240))
        assertEquals(35_000_000L, pacer.deadlineNs(30_100_000, 240))
        assertEquals(40_000_000L, pacer.deadlineNs(40_000_000, 240))
    }

    @Test fun schedulingJitterDoesNotAccumulateButGapBeyondCaptureBacklogStartsFreshClock() {
        val pacer = MicrophoneChunkPacer(48_000, 960)
        assertEquals(1_000_000L, pacer.deadlineNs(1_000_000, 240))
        assertEquals(6_200_000L, pacer.deadlineNs(6_200_000, 240))
        pacer.recordDelivery(6_200_000)
        assertEquals(11_000_000L, pacer.deadlineNs(6_300_000, 240))
        assertEquals(100_000_000L, pacer.deadlineNs(100_000_000, 240))
        assertEquals(105_000_000L, pacer.deadlineNs(100_100_000, 240))
    }

    @Test fun periodsUseFramesNotMonoSamplesOrBytesAndNewSessionHasFreshClock() {
        assertEquals(0L, MicrophoneChunkPacer(48_000, 960).deadlineNs(0, 480))
        val pacer = MicrophoneChunkPacer(48_000, 960)
        pacer.deadlineNs(0, 480)
        assertEquals(10_000_000L, pacer.deadlineNs(1, 480))
        assertEquals(42L, MicrophoneChunkPacer(48_000, 960).deadlineNs(42, 240))
    }

    @Test fun repeatedRecoverableSchedulingGapsDoNotPermanentlyShiftTheSourceClock() {
        val pacer = MicrophoneChunkPacer(48_000, 960)
        var nowNs = 0L
        for (chunk in 0 until 160) {
            val nominalNs = chunk * 5_000_000L
            nowNs = maxOf(nowNs, nominalNs)
            if (chunk % 20 == 1) nowNs += 15_000_000L
            val deadlineNs = pacer.deadlineNs(nowNs, 240)
            nowNs = maxOf(nowNs, deadlineNs)
            pacer.recordDelivery(nowNs)
            if (chunk % 20 == 19) assertEquals(nominalNs, deadlineNs)
        }
    }

    @Test fun catchUpNeverUsesImmediateBatchAndAccountsForActualLateDelivery() {
        val pacer = MicrophoneChunkPacer(48_000, 1_920)
        pacer.deadlineNs(0, 240)
        pacer.recordDelivery(0)
        assertEquals(20_000_000L, pacer.deadlineNs(20_000_000, 240))
        pacer.recordDelivery(23_000_000)
        assertEquals(25_500_000L, pacer.deadlineNs(23_100_000, 240))
        pacer.recordDelivery(25_500_000)
        assertEquals(28_000_000L, pacer.deadlineNs(25_600_000, 240))
    }

    @Test fun recoverableGapUsesActualCaptureCapacityRatherThanPacketCount() {
        val small = MicrophoneChunkPacer(48_000, 480)
        val large = MicrophoneChunkPacer(48_000, 1_920)
        for (pacer in listOf(small, large)) {
            pacer.deadlineNs(0, 240)
            pacer.recordDelivery(0)
            assertEquals(20_000_000L, pacer.deadlineNs(20_000_000, 240))
            pacer.recordDelivery(20_000_000)
        }
        assertEquals(25_000_000L, small.deadlineNs(20_100_000, 240))
        assertEquals(22_500_000L, large.deadlineNs(20_100_000, 240))
    }
}
