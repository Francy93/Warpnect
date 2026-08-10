package io.warpnect.audio.capture

enum class AudioPcmEncoding(
    val bytesPerSample: Int,
) {
    Pcm16(bytesPerSample = 2),
}
