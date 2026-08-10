package io.warpnect.audio.encoder

data class AudioEncoderResult(
    val error: AudioEncoderError,
    val snapshot: AudioEncoderSnapshot,
    val format: EncodedAudioFormat? = null,
) {
    val isSuccess: Boolean
        get() = error == AudioEncoderError.None
}
