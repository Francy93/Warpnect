package io.warpnect.audio.encoder

import io.warpnect.audio.capture.AudioCaptureSource

data class EncodedAudioFormat(
    val codec: AudioCodec,
    val source: AudioCaptureSource,
    val sampleRateHz: Int,
    val channelCount: Int,
    val frameDurationUs: Int,
    val samplesPerFrame: Int,
    val bitrateBps: Int,
    val bitrateMode: AudioBitrateMode,
    val complexity: Int,
    val dtxEnabled: Boolean,
    val inBandFecEnabled: Boolean,
    val lookaheadSamples: Int,
)
