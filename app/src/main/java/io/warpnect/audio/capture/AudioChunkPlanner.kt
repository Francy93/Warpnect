package io.warpnect.audio.capture

object AudioChunkPlanner {
    fun targetFramesPerChunk(sampleRateHz: Int, targetChunkDurationUs: Long): Int {
        if (sampleRateHz <= 0 || targetChunkDurationUs <= 0L) {
            return 0
        }
        val numerator = sampleRateHz.toLong() * targetChunkDurationUs
        val frames = (numerator + MICROSECONDS_PER_SECOND - 1L) / MICROSECONDS_PER_SECOND
        return frames.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    fun chunkBytes(targetFramesPerChunk: Int, bytesPerFrame: Int): Int {
        if (targetFramesPerChunk <= 0 || bytesPerFrame <= 0) {
            return 0
        }
        val bytes = targetFramesPerChunk.toLong() * bytesPerFrame.toLong()
        return if (bytes > Int.MAX_VALUE) 0 else bytes.toInt()
    }

    private const val MICROSECONDS_PER_SECOND = 1_000_000L
}
