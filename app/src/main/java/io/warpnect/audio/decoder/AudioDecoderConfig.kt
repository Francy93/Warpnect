package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioCodec

data class AudioDecoderConfig(
    val source: AudioCaptureSource,
    val codec: AudioCodec = AudioCodec.Opus,
    val configGeneration: Long,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val channelCount: Int,
    val frameDurationUs: Int = DEFAULT_FRAME_DURATION_US,
    val lookaheadSamples: Int = 0,
) {
    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 48_000
        const val DEFAULT_FRAME_DURATION_US = 5_000
        const val MAX_CONFIG_GENERATION = 0xFFFF_FFFFL
    }
}
