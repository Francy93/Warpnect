package io.warpnect.video.session

import io.warpnect.video.transport.VideoTransportError

data class VideoPerformanceConfig(
    val maxFrameRecoveryAgeUs: Long = 50_000L,
    val resyncRequestCooldownUs: Long = 250_000L,
    val startupConfigRequestDelayUs: Long = 250_000L,
    val keyFrameWaitRequestDelayUs: Long = 250_000L,
    val clockSyncIntervalUs: Long = 1_000_000L,
    val diagnosticSampleCapacity: Int = 256,
    val bitrate: VideoBitrateTuning = VideoBitrateTuning.Fixed,
) {
    fun validate(): VideoTransportError = when {
        maxFrameRecoveryAgeUs < 0L -> VideoTransportError.PerformanceConfigInvalid
        resyncRequestCooldownUs < 0L -> VideoTransportError.PerformanceConfigInvalid
        startupConfigRequestDelayUs < 0L -> VideoTransportError.PerformanceConfigInvalid
        keyFrameWaitRequestDelayUs < 0L -> VideoTransportError.PerformanceConfigInvalid
        clockSyncIntervalUs < 0L -> VideoTransportError.ClockSyncUnavailable
        diagnosticSampleCapacity < 0 -> VideoTransportError.PerformanceConfigInvalid
        else -> bitrate.validate()
    }

    companion object {
        val UltraLowLatency = VideoPerformanceConfig()
    }
}

sealed interface VideoBitrateTuning {
    fun validate(): VideoTransportError

    data object Fixed : VideoBitrateTuning {
        override fun validate(): VideoTransportError = VideoTransportError.None
    }

    data class LossReactive(
        val minBitrateBps: Int,
        val initialBitrateBps: Int,
        val maxBitrateBps: Int,
        val decreasePercent: Int = 15,
        val increasePercent: Int = 5,
        val minimumUpdateIntervalUs: Long = 500_000L,
    ) : VideoBitrateTuning {
        override fun validate(): VideoTransportError = when {
            minBitrateBps <= 0 -> VideoTransportError.BitrateAdaptationFailed
            initialBitrateBps !in minBitrateBps..maxBitrateBps -> VideoTransportError.BitrateAdaptationFailed
            decreasePercent !in 1..90 -> VideoTransportError.BitrateAdaptationFailed
            increasePercent !in 1..50 -> VideoTransportError.BitrateAdaptationFailed
            minimumUpdateIntervalUs < 0L -> VideoTransportError.BitrateAdaptationFailed
            else -> VideoTransportError.None
        }
    }
}
