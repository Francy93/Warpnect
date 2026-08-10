package io.warpnect.audio.encoder

object AudioEncoderValidation {
    private val supportedSampleRates = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
    private val supportedFrameDurationsUs = setOf(2_500, 5_000, 10_000, 20_000)

    fun validate(request: AudioEncoderRequest): AudioEncoderError = when {
        request.codec != AudioCodec.Opus -> AudioEncoderError.UnsupportedCodec
        request.sampleRateHz !in supportedSampleRates -> AudioEncoderError.UnsupportedSampleRate
        request.channelCount != 1 && request.channelCount != 2 -> AudioEncoderError.UnsupportedChannelCount
        request.frameDurationUs !in supportedFrameDurationsUs -> AudioEncoderError.UnsupportedFrameDuration
        samplesPerFrame(request.sampleRateHz, request.frameDurationUs) <= 0 ->
            AudioEncoderError.UnsupportedFrameDuration
        request.bitrateBps < AudioEncoderRequest.MIN_BITRATE_BPS ||
            request.bitrateBps > AudioEncoderRequest.MAX_BITRATE_BPS -> AudioEncoderError.InvalidBitrate
        request.complexity !in 0..10 -> AudioEncoderError.InvalidComplexity
        else -> AudioEncoderError.None
    }

    fun samplesPerFrame(sampleRateHz: Int, frameDurationUs: Int): Int {
        if (sampleRateHz <= 0 || frameDurationUs <= 0) return 0
        val product = sampleRateHz.toLong() * frameDurationUs.toLong()
        if (product % 1_000_000L != 0L) return 0
        val samples = product / 1_000_000L
        return if (samples in 1L..Int.MAX_VALUE.toLong()) samples.toInt() else 0
    }

    fun encodedFormatFrom(request: AudioEncoderRequest, lookaheadSamples: Int): EncodedAudioFormat = EncodedAudioFormat(
        codec = request.codec,
        source = request.source,
        sampleRateHz = request.sampleRateHz,
        channelCount = request.channelCount,
        frameDurationUs = request.frameDurationUs,
        samplesPerFrame = samplesPerFrame(request.sampleRateHz, request.frameDurationUs),
        bitrateBps = request.bitrateBps,
        bitrateMode = request.bitrateMode,
        complexity = request.complexity,
        dtxEnabled = false,
        inBandFecEnabled = false,
        lookaheadSamples = lookaheadSamples,
    )
}
