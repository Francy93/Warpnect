package io.warpnect.audio.encoder

enum class AudioCodec(
    val code: Int,
) {
    Unknown(code = 0),
    Opus(code = 1),
}
