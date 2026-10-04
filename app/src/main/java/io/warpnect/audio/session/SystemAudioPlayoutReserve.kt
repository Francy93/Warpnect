package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureSource

/** Ordered SystemAudio burst tolerance; neither an IPC freshness budget nor a reorder queue. */
internal object SystemAudioPlayoutReserve {
    const val CAPACITY_US = 85_000
    const val PRIME_US = 70_000

    fun apply(config: AudioReceiverSessionConfig, codecFrameDurationUs: Int): AudioReceiverSessionConfig {
        if (config.receiverRuntimeConfig.source != AudioCaptureSource.SystemAudio) return config
        require(codecFrameDurationUs > 0)
        // 63.38 ms measured ordered phase range rounds to 65 ms, plus one packet of reserve.
        // Three spare packets cover recovered bursts and partial-head admission.
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
