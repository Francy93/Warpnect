package io.warpnect.video.session

import io.warpnect.video.transport.VideoTransportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPerformanceConfigTest {
    @Test
    fun validatesExplicitTimingBounds() {
        assertEquals(VideoTransportError.None, VideoPerformanceConfig().validate())
        assertEquals(
            VideoTransportError.PerformanceConfigInvalid,
            VideoPerformanceConfig(maxFrameRecoveryAgeUs = -1).validate(),
        )
        assertEquals(
            VideoTransportError.PerformanceConfigInvalid,
            VideoPerformanceConfig(resyncRequestCooldownUs = -1).validate(),
        )
        assertEquals(
            VideoTransportError.ClockSyncUnavailable,
            VideoPerformanceConfig(clockSyncIntervalUs = -1).validate(),
        )
    }

    @Test
    fun fixedBitrateRemainsDeterministicDefault() {
        val config = VideoPerformanceConfig.UltraLowLatency

        assertEquals(VideoBitrateTuning.Fixed, config.bitrate)
        assertEquals(VideoTransportError.None, config.validate())
    }

    @Test
    fun lossReactiveBitrateRejectsInvalidBounds() {
        val invalid = VideoBitrateTuning.LossReactive(
            minBitrateBps = 2_000_000,
            initialBitrateBps = 1_000_000,
            maxBitrateBps = 4_000_000,
        )

        assertEquals(VideoTransportError.BitrateAdaptationFailed, invalid.validate())
    }

    @Test
    fun lossReactiveBitrateDecreasesOnLossAndRespectsUpdateInterval() {
        val controller = LossReactiveBitrateController(policy())

        assertFalse(controller.evaluate(sample(nowUs = 0)).changed)
        val firstDrop = controller.evaluate(sample(nowUs = 1_000, nackCount = 1))
        assertTrue(firstDrop.changed)
        assertEquals(BitrateDecisionReason.LossOrBackpressure, firstDrop.reason)
        assertEquals(1_700_000, firstDrop.bitrateBps)

        val suppressed = controller.evaluate(sample(nowUs = 1_500, nackCount = 2))
        assertFalse(suppressed.changed)
        assertEquals(1_700_000, suppressed.bitrateBps)
    }

    @Test
    fun lossReactiveBitrateIncreasesSlowlyAfterCleanSamples() {
        val controller = LossReactiveBitrateController(policy())

        controller.evaluate(sample(nowUs = 0))
        controller.evaluate(sample(nowUs = 1_000, nackCount = 1))
        controller.evaluate(sample(nowUs = 2_000, nackCount = 1))
        controller.evaluate(sample(nowUs = 3_000, nackCount = 1))
        val decision = controller.evaluate(sample(nowUs = 4_000, nackCount = 1))

        assertTrue(decision.changed)
        assertEquals(BitrateDecisionReason.SustainedCleanOperation, decision.reason)
        assertEquals(1_785_000, decision.bitrateBps)
    }

    private fun policy(): VideoBitrateTuning.LossReactive = VideoBitrateTuning.LossReactive(
        minBitrateBps = 1_000_000,
        initialBitrateBps = 2_000_000,
        maxBitrateBps = 4_000_000,
        decreasePercent = 15,
        increasePercent = 5,
        minimumUpdateIntervalUs = 1_000,
    )

    private fun sample(
        nowUs: Long,
        nackCount: Long = 0,
        retransmissionCount: Long = 0,
        wouldBlockCount: Long = 0,
    ): BitrateTelemetrySample = BitrateTelemetrySample(
        nowUs = nowUs,
        nackCount = nackCount,
        retransmissionCount = retransmissionCount,
        wouldBlockCount = wouldBlockCount,
    )
}
