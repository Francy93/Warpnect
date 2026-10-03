package io.warpnect.platform.audio

import android.util.Log
import io.warpnect.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

internal class PcmDeliveryDebugProbe(
    private val boundary: String,
    private val emit: (String) -> Unit,
) {
    private var windowStartNs = 0L
    private var previousNs = 0L
    private var windows = 0
    private var chunks = 0L
    private var frames = 0L
    private var samples = 0L
    private var squares = 0.0
    private var peak = 0
    private var tonePrevious = 0.0
    private var toneOlder = 0.0
    private var maxGapNs = 0L
    private var burst = 0
    private var maxBurst = 0

    fun observe(buffer: ByteBuffer, offset: Int, sizeBytes: Int, frameCount: Int, nowNs: Long) {
        if (windows >= MAX_WINDOWS) return
        if (windowStartNs == 0L) windowStartNs = nowNs
        val gap = if (previousNs == 0L) 0L else (nowNs - previousNs).coerceAtLeast(0L)
        maxGapNs = maxOf(maxGapNs, gap)
        burst = if (previousNs != 0L && gap < BURST_GAP_NS) burst + 1 else 1
        maxBurst = maxOf(maxBurst, burst)
        previousNs = nowNs
        val pcm = buffer.duplicate().order(ByteOrder.nativeOrder())
        for (index in 0 until sizeBytes / Short.SIZE_BYTES) {
            val sample = pcm.getShort(offset + index * Short.SIZE_BYTES).toInt()
            squares += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample))
            if (boundary.contains("microphone", ignoreCase = true)) {
                val next = sample + TONE_COEFFICIENT * tonePrevious - toneOlder
                toneOlder = tonePrevious
                tonePrevious = next
            }
        }
        chunks++
        frames += frameCount
        samples += sizeBytes / Short.SIZE_BYTES
        if (nowNs - windowStartNs >= WINDOW_NS) flush(nowNs)
    }

    fun flush(nowNs: Long) {
        if (chunks == 0L || windows >= MAX_WINDOWS) return
        emit(
            "PCM_DELIVERY boundary=$boundary window=$windows chunks=$chunks frames=$frames " +
                "rms=${sqrt(squares / samples.coerceAtLeast(1L))} peak=$peak " +
                "tone997=${toneAmplitude()} " +
                "max_gap_ns=$maxGapNs max_burst=$maxBurst elapsed_ns=${nowNs - windowStartNs}",
        )
        windows++
        windowStartNs = nowNs
        chunks = 0
        frames = 0
        samples = 0
        squares = 0.0
        peak = 0
        tonePrevious = 0.0
        toneOlder = 0.0
        maxGapNs = 0
        maxBurst = 0
    }

    private fun toneAmplitude(): Double {
        val power = tonePrevious * tonePrevious + toneOlder * toneOlder -
            TONE_COEFFICIENT * tonePrevious * toneOlder
        return 2 * sqrt(power.coerceAtLeast(0.0)) / samples.coerceAtLeast(1L)
    }

    companion object {
        private const val WINDOW_NS = 5_000_000_000L
        private const val BURST_GAP_NS = 1_000_000L
        private const val MAX_WINDOWS = 12
        private val TONE_COEFFICIENT = 2 * cos(2 * Math.PI * 997 / 48_000)
        private val remainingLogEntries = AtomicInteger(256)

        fun create(boundary: String): PcmDeliveryDebugProbe? = if (BuildConfig.DEBUG) {
            PcmDeliveryDebugProbe(boundary) {
                if (remainingLogEntries.getAndDecrement() > 0) {
                    runCatching { Log.i("WarpnectAudioDiagnostics", it) }
                }
            }
        } else {
            null
        }
    }
}
