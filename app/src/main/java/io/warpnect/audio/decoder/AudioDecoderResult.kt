package io.warpnect.audio.decoder

data class AudioDecoderResult(
    val error: AudioDecoderError,
    val snapshot: AudioDecoderSnapshot = AudioDecoderSnapshot(),
    val format: DecodedAudioFormat? = null,
)
