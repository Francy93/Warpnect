package io.warpnect.audio.encoder

fun audioFrameOffsetTimeNs(frameOffset: Long, sampleRateHz: Int): Long {
    if (frameOffset <= 0L || sampleRateHz <= 0) return 0L
    return if (frameOffset > Long.MAX_VALUE / 1_000_000_000L) {
        Long.MAX_VALUE
    } else {
        (frameOffset * 1_000_000_000L) / sampleRateHz
    }
}
