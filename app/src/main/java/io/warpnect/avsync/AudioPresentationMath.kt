package io.warpnect.avsync

internal object AudioPresentationMath {
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L

    fun lookaheadDurationUs(lookaheadSamples: Int, sampleRateHz: Int): Long {
        if (lookaheadSamples < 0 || sampleRateHz <= 0) return 0L
        return (lookaheadSamples.toLong() * MICROS_PER_SECOND) / sampleRateHz.toLong()
    }

    fun sourceContentTimeUs(captureTimeUs: Long, lookaheadSamples: Int, sampleRateHz: Int): Long =
        captureTimeUs - lookaheadDurationUs(lookaheadSamples, sampleRateHz)

    fun interpolateLocalPresentationTimeNs(
        anchorOutputFramePosition: Long,
        timestampFramePosition: Long,
        timestampTimeNs: Long,
        sampleRateHz: Int,
    ): Long? {
        if (anchorOutputFramePosition < 0 || timestampFramePosition < 0 ||
            timestampTimeNs < 0 || sampleRateHz <= 0
        ) {
            return null
        }
        val deltaFrames = anchorOutputFramePosition - timestampFramePosition
        val magnitude = kotlin.math.abs(deltaFrames)
        val seconds = magnitude / sampleRateHz.toLong()
        val remainder = magnitude % sampleRateHz.toLong()
        val secondsNs = seconds.saturatingMultiply(NANOS_PER_SECOND) ?: return null
        val remainderNs = remainder.saturatingMultiply(NANOS_PER_SECOND)?.div(sampleRateHz) ?: return null
        val deltaNs = secondsNs.saturatingAdd(remainderNs) ?: return null
        return if (deltaFrames >= 0) {
            timestampTimeNs.saturatingAdd(deltaNs)
        } else {
            timestampTimeNs.saturatingSubtract(deltaNs)
        }
    }

    fun microsToNanos(valueUs: Long): Long? = valueUs.saturatingMultiply(1_000L)

    private fun Long.saturatingMultiply(other: Long): Long? {
        return try {
            Math.multiplyExact(this, other)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun Long.saturatingAdd(other: Long): Long? {
        if (other > 0L && this > Long.MAX_VALUE - other) return null
        return this + other
    }

    private fun Long.saturatingSubtract(other: Long): Long? {
        if (other > 0L && this < Long.MIN_VALUE + other) return null
        return this - other
    }
}
