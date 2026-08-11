package io.warpnect.audio.playback

object AudioPlaybackValidation {
    private val supportedRates = setOf(8000, 12000, 16000, 24000, 48000)
    private val supportedDurationsUs = setOf(2500, 5000, 10000, 20000)

    fun validate(config: AudioPlaybackConfig): AudioPlaybackError {
        if (config.configGeneration <= 0 || config.configGeneration > UInt.MAX_VALUE.toLong()) {
            return AudioPlaybackError.InvalidConfiguration
        }
        if (config.sampleRateHz !in supportedRates) return AudioPlaybackError.UnsupportedSampleRate
        if (config.channelCount != 1 && config.channelCount != 2) {
            return AudioPlaybackError.UnsupportedChannelCount
        }
        if (config.frameDurationUs !in supportedDurationsUs) {
            return AudioPlaybackError.InvalidConfiguration
        }
        val expectedFrames = samplesPerFrame(config.sampleRateHz, config.frameDurationUs)
        if (expectedFrames <= 0 || config.framesPerCodecFrame != expectedFrames) {
            return AudioPlaybackError.InvalidConfiguration
        }
        if (config.lookaheadSamples < 0) {
            return AudioPlaybackError.InvalidConfiguration
        }
        if (config.ringCapacityCodecFrames <= 0 || config.ringCapacityCodecFrames > 64) {
            return AudioPlaybackError.InvalidConfiguration
        }
        if (config.startThresholdCodecFrames <= 0 ||
            config.startThresholdCodecFrames > config.ringCapacityCodecFrames
        ) {
            return AudioPlaybackError.InvalidConfiguration
        }
        if (config.requestedBufferBursts <= 0 || config.requestedBufferBursts > 8) {
            return AudioPlaybackError.InvalidConfiguration
        }
        return AudioPlaybackError.None
    }

    fun samplesPerFrame(sampleRateHz: Int, frameDurationUs: Int): Int {
        val product = sampleRateHz.toLong() * frameDurationUs.toLong()
        if (product % 1_000_000L != 0L) return 0
        val frames = product / 1_000_000L
        return if (frames in 1..Int.MAX_VALUE) frames.toInt() else 0
    }
}
