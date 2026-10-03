package io.warpnect.platform.audio.capture

/** Smooths synchronous AudioRecord batches without storing another PCM chunk. */
internal class MicrophoneChunkPacer(private val sampleRateHz: Int, captureBufferFrames: Int) {
    init {
        require(sampleRateHz > 0 && captureBufferFrames > 0)
    }

    private val recoverableGapNs = captureBufferFrames.toLong() * 1_000_000_000L / sampleRateHz
    private var nextNominalDeadlineNs: Long? = null
    private var lastDeliveryNs: Long? = null

    fun deadlineNs(nowNs: Long, frameCount: Int): Long {
        require(nowNs >= 0 && frameCount > 0)
        val periodNs = frameCount.toLong() * 1_000_000_000L / sampleRateHz
        val previous = nextNominalDeadlineNs
        // Recover scheduling debt without shifting the source clock or replaying beyond AudioRecord's backlog.
        val nominal = if (previous == null || nowNs - previous > recoverableGapNs) nowNs else previous
        val earliest = lastDeliveryNs?.plus(periodNs / 2) ?: nowNs
        val deadline = maxOf(nowNs, nominal, earliest)
        nextNominalDeadlineNs = nominal + periodNs
        return deadline
    }

    fun recordDelivery(deliveryNs: Long) {
        require(deliveryNs >= 0)
        lastDeliveryNs = deliveryNs
    }
}
