package io.warpnect.video.session

data class BitrateTelemetrySample(
    val nowUs: Long,
    val nackCount: Long,
    val retransmissionCount: Long,
    val wouldBlockCount: Long,
)

data class BitrateDecision(
    val bitrateBps: Int,
    val changed: Boolean,
    val reason: BitrateDecisionReason = BitrateDecisionReason.None,
)

enum class BitrateDecisionReason {
    None,
    LossOrBackpressure,
    SustainedCleanOperation,
}

class LossReactiveBitrateController(
    private val config: VideoBitrateTuning.LossReactive,
) {
    private var currentBitrateBps = config.initialBitrateBps
    private var lastUpdateUs = Long.MIN_VALUE
    private var previousSample: BitrateTelemetrySample? = null
    private var cleanSamples = 0

    fun evaluate(sample: BitrateTelemetrySample): BitrateDecision {
        val previous = previousSample
        previousSample = sample
        if (previous == null || !canUpdate(sample.nowUs)) {
            return BitrateDecision(currentBitrateBps, changed = false)
        }

        val hasOverloadSignal =
            sample.nackCount > previous.nackCount ||
                sample.retransmissionCount > previous.retransmissionCount ||
                sample.wouldBlockCount > previous.wouldBlockCount
        if (hasOverloadSignal) {
            cleanSamples = 0
            return updateBitrate(
                newBitrate = decreasedBitrate(),
                nowUs = sample.nowUs,
                reason = BitrateDecisionReason.LossOrBackpressure,
            )
        }

        cleanSamples += 1
        if (cleanSamples >= CLEAN_SAMPLE_THRESHOLD) {
            cleanSamples = 0
            return updateBitrate(
                newBitrate = increasedBitrate(),
                nowUs = sample.nowUs,
                reason = BitrateDecisionReason.SustainedCleanOperation,
            )
        }
        return BitrateDecision(currentBitrateBps, changed = false)
    }

    fun snapshotBitrateBps(): Int = currentBitrateBps

    private fun canUpdate(nowUs: Long): Boolean =
        lastUpdateUs == Long.MIN_VALUE || nowUs - lastUpdateUs >= config.minimumUpdateIntervalUs

    private fun decreasedBitrate(): Int {
        val delta = (currentBitrateBps.toLong() * config.decreasePercent.toLong()) / 100L
        return (currentBitrateBps.toLong() - delta).coerceAtLeast(config.minBitrateBps.toLong()).toInt()
    }

    private fun increasedBitrate(): Int {
        val delta = (currentBitrateBps.toLong() * config.increasePercent.toLong()) / 100L
        return (currentBitrateBps.toLong() + delta).coerceAtMost(config.maxBitrateBps.toLong()).toInt()
    }

    private fun updateBitrate(newBitrate: Int, nowUs: Long, reason: BitrateDecisionReason): BitrateDecision {
        if (newBitrate == currentBitrateBps) {
            return BitrateDecision(currentBitrateBps, changed = false)
        }
        currentBitrateBps = newBitrate
        lastUpdateUs = nowUs
        return BitrateDecision(currentBitrateBps, changed = true, reason = reason)
    }

    private companion object {
        const val CLEAN_SAMPLE_THRESHOLD = 3
    }
}
