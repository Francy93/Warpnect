package io.warpnect.audio.capture

data class AudioTimestampAnchor(
    val framePosition: Long,
    val nanoTime: Long,
)

data class AudioChunkTiming(
    val firstFramePosition: Long,
    val captureTimeNs: Long,
    val timestampQuality: AudioTimestampQuality,
)

class AudioCaptureTimestampTracker(
    private val sampleRateHz: Int,
) {
    private var totalFramesRead = 0L
    private var latestAnchor: AudioTimestampAnchor? = null

    fun reset() {
        totalFramesRead = 0L
        latestAnchor = null
    }

    fun updateAnchor(anchor: AudioTimestampAnchor): AudioCaptureError {
        if (anchor.framePosition < 0L || anchor.nanoTime <= 0L) {
            return AudioCaptureError.TimestampInvalid
        }
        latestAnchor = anchor
        return AudioCaptureError.None
    }

    fun recordChunk(frameCount: Int, readCompletionTimeNs: Long): AudioChunkTiming {
        val firstFrame = totalFramesRead
        totalFramesRead = saturatingAdd(totalFramesRead, frameCount.toLong())
        val anchor = latestAnchor
        if (sampleRateHz <= 0 || frameCount <= 0 || readCompletionTimeNs <= 0L) {
            return AudioChunkTiming(
                firstFramePosition = firstFrame,
                captureTimeNs = readCompletionTimeNs.coerceAtLeast(0L),
                timestampQuality = AudioTimestampQuality.Unavailable,
            )
        }

        if (anchor != null) {
            val deltaFrames = firstFrame - anchor.framePosition
            val deltaNs = framesToNanos(deltaFrames, sampleRateHz)
            return AudioChunkTiming(
                firstFramePosition = firstFrame,
                captureTimeNs = saturatingAdd(anchor.nanoTime, deltaNs),
                timestampQuality = AudioTimestampQuality.AudioRecordTimestamp,
            )
        }

        return AudioChunkTiming(
            firstFramePosition = firstFrame,
            captureTimeNs = saturatingAdd(
                readCompletionTimeNs,
                -framesToNanos(frameCount.toLong(), sampleRateHz),
            ).coerceAtLeast(0L),
            timestampQuality = AudioTimestampQuality.EstimatedFromReadCompletion,
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        fun framesToNanos(frames: Long, sampleRateHz: Int): Long {
            if (sampleRateHz <= 0) {
                return 0L
            }
            val wholeSeconds = frames / sampleRateHz
            val remainingFrames = frames % sampleRateHz
            val wholeNanos = saturatingMultiply(wholeSeconds, NANOS_PER_SECOND)
            val fractionalNanos = remainingFrames * NANOS_PER_SECOND / sampleRateHz
            return saturatingAdd(wholeNanos, fractionalNanos)
        }

        private fun saturatingMultiply(left: Long, right: Long): Long = try {
            Math.multiplyExact(left, right)
        } catch (_: ArithmeticException) {
            if ((left < 0L) xor (right < 0L)) Long.MIN_VALUE else Long.MAX_VALUE
        }

        private fun saturatingAdd(left: Long, right: Long): Long = try {
            Math.addExact(left, right)
        } catch (_: ArithmeticException) {
            if (right < 0L) Long.MIN_VALUE else Long.MAX_VALUE
        }
    }
}
