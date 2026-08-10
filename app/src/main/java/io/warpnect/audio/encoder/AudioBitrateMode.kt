package io.warpnect.audio.encoder

enum class AudioBitrateMode(
    val nativeCode: Int,
) {
    ConstantBitrate(nativeCode = 0),
    ConstrainedVariableBitrate(nativeCode = 1),
}
