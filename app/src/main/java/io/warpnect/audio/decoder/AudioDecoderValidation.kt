package io.warpnect.audio.decoder

import io.warpnect.audio.encoder.AudioCodec

object AudioDecoderValidation {
    private val supportedSampleRates = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
    private val supportedFrameDurationsUs = setOf(2_500, 5_000, 10_000, 20_000)

    fun validate(config: AudioDecoderConfig): AudioDecoderError = when {
        config.codec != AudioCodec.Opus -> AudioDecoderError.UnsupportedCodec
        config.configGeneration !in 1L..AudioDecoderConfig.MAX_CONFIG_GENERATION ->
            AudioDecoderError.InvalidConfigGeneration
        config.sampleRateHz !in supportedSampleRates -> AudioDecoderError.UnsupportedSampleRate
        config.channelCount != 1 && config.channelCount != 2 -> AudioDecoderError.UnsupportedChannelCount
        config.frameDurationUs !in supportedFrameDurationsUs -> AudioDecoderError.UnsupportedFrameDuration
        samplesPerFrame(config.sampleRateHz, config.frameDurationUs) <= 0 ->
            AudioDecoderError.UnsupportedFrameDuration
        config.lookaheadSamples < 0 -> AudioDecoderError.InvalidBufferRange
        else -> AudioDecoderError.None
    }

    fun samplesPerFrame(sampleRateHz: Int, frameDurationUs: Int): Int {
        if (sampleRateHz <= 0 || frameDurationUs <= 0) return 0
        val product = sampleRateHz.toLong() * frameDurationUs.toLong()
        if (product % 1_000_000L != 0L) return 0
        val samples = product / 1_000_000L
        return if (samples in 1L..Int.MAX_VALUE.toLong()) samples.toInt() else 0
    }

    fun decodedFormatFrom(config: AudioDecoderConfig): DecodedAudioFormat = DecodedAudioFormat(
        codec = config.codec,
        source = config.source,
        configGeneration = config.configGeneration,
        sampleRateHz = config.sampleRateHz,
        channelCount = config.channelCount,
        frameDurationUs = config.frameDurationUs,
        samplesPerFrame = samplesPerFrame(config.sampleRateHz, config.frameDurationUs),
        bytesPerFrame = config.channelCount * Short.SIZE_BYTES,
        lookaheadSamples = config.lookaheadSamples,
    )
}
