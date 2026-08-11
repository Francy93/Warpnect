package io.warpnect.audio.decoder

import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.encoder.AudioCodec

data class DecodedAudioFormat(
    val codec: AudioCodec,
    val source: AudioCaptureSource,
    val configGeneration: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    val frameDurationUs: Int,
    val samplesPerFrame: Int,
    val bytesPerFrame: Int,
    val lookaheadSamples: Int,
)
