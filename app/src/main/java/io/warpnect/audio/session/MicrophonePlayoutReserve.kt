package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureSource

/** A bounded reserve for ordered audio that arrives in bursts, not a network reorder queue. */
internal object MicrophonePlayoutReserve {
    const val CAPACITY_US = 80_000
    const val PRIME_US = 70_000

    fun apply(config: AudioReceiverSessionConfig, codecFrameDurationUs: Int): AudioReceiverSessionConfig {
        if (config.receiverRuntimeConfig.source != AudioCaptureSource.MicrophoneAudio) return config
        require(codecFrameDurationUs > 0)
        // Measured ~64 ms local arrival-phase range plus a callback quantum and partial-slot headroom.
        val capacity = ceilFrames(CAPACITY_US, codecFrameDurationUs)
        val prime = ceilFrames(PRIME_US, codecFrameDurationUs)
        require(capacity <= 64)
        return config.copy(
            playbackRingCapacityCodecFrames = maxOf(config.playbackRingCapacityCodecFrames, capacity),
            playbackStartThresholdCodecFrames = maxOf(config.playbackStartThresholdCodecFrames, prime),
            playbackWarmStart = true,
        )
    }

    private fun ceilFrames(durationUs: Int, frameDurationUs: Int): Int =
        ((durationUs.toLong() + frameDurationUs - 1L) / frameDurationUs).toInt()
}
