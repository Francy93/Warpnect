package io.warpnect.audio.encoder

data class AudioEncoderCapabilities(
    val request: AudioEncoderRequest,
    val available: Boolean,
    val support: AudioEncoderSupport,
    val selectedFormat: EncodedAudioFormat?,
    val error: AudioEncoderError = AudioEncoderError.None,
)

data class AudioEncoderSupport(
    val codecSupported: Boolean,
    val sampleRateSupported: Boolean,
    val channelCountSupported: Boolean,
    val frameDurationSupported: Boolean,
    val bitrateSupported: Boolean,
    val complexitySupported: Boolean,
)
