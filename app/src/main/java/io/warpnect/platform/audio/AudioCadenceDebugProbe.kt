package io.warpnect.platform.audio

import android.util.Log
import io.warpnect.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/** Local delivery cadence only. Remote capture timestamps are deliberately not subtracted. */
internal class AudioCadenceDebugProbe(
    private val boundary: String,
    private val emit: (String) -> Unit,
    private val positionStep: Long = 240L,
    private val nominalPeriodNs: Long = 5_000_000L,
    private val maxWindows: Int = 12,
) {
    init {
        require(positionStep > 0)
        require(nominalPeriodNs > 0)
        require(maxWindows in 1..24)
    }

    private var startNs = 0L
    private var previousNs = 0L
    private var count = 0
    private var maxGapNs = 0L
    private var burst = 0
    private var maxBurst = 0
    private var windows = 0
    private var previousPosition: Long? = null
    private var missing = 0L
    private var reordered = 0L
    private var duplicates = 0L
    private val intervals = IntArray(7)
    private var timelineOriginPosition: Long? = null
    private var timelineOriginNs = 0L
    private var timelineMinNs = Long.MAX_VALUE
    private var timelineMaxNs = Long.MIN_VALUE

    fun observe(position: Long, nowNs: Long) {
        if (windows >= maxWindows) return
        if (nowNs <= 0L || (previousNs != 0L && nowNs < previousNs)) return
        if (startNs == 0L) startNs = nowNs
        val originPosition = timelineOriginPosition ?: position.also {
            timelineOriginPosition = it
            timelineOriginNs = nowNs
        }
        // Relative nominal sample progression, not remote time or one-way network latency.
        val phaseNs = nowNs - timelineOriginNs - (position - originPosition) / positionStep * nominalPeriodNs
        timelineMinNs = minOf(timelineMinNs, phaseNs)
        timelineMaxNs = maxOf(timelineMaxNs, phaseNs)
        val gap = if (previousNs == 0L) 0L else (nowNs - previousNs).coerceAtLeast(0L)
        maxGapNs = maxOf(maxGapNs, gap)
        burst = if (previousNs != 0L && gap < 1_000_000L) burst + 1 else 1
        maxBurst = maxOf(maxBurst, burst)
        if (previousNs != 0L) {
            val bucket = when {
                gap < 1_000_000L -> 0
                gap < 3_000_000L -> 1
                gap < 7_000_000L -> 2
                gap < 10_000_000L -> 3
                gap < 20_000_000L -> 4
                gap < 40_000_000L -> 5
                else -> 6
            }
            intervals[bucket]++
        }
        previousPosition?.let { previous ->
            when {
                position == previous -> duplicates++
                position < previous -> reordered++
                position > previous + positionStep -> missing += (position - previous) / positionStep - 1
            }
        }
        previousPosition = position
        previousNs = nowNs
        count++
        if (nowNs - startNs >= 5_000_000_000L) {
            emit(
                "AUDIO_CADENCE boundary=$boundary window=$windows count=$count " +
                    "last_position=$position max_gap_ns=$maxGapNs max_burst=$maxBurst elapsed_ns=${nowNs - startNs} " +
                    "interval_bins_lt1_3_7_10_20_40_else_ms=${intervals.joinToString(",")} " +
                    "missing_units=$missing reordered=$reordered duplicates=$duplicates " +
                    "nominal_phase_min_ns=$timelineMinNs nominal_phase_max_ns=$timelineMaxNs",
            )
            windows++
            startNs = nowNs
            count = 0
            maxGapNs = 0
            maxBurst = 0
            intervals.fill(0)
            missing = 0
            reordered = 0
            duplicates = 0
            timelineMinNs = Long.MAX_VALUE
            timelineMaxNs = Long.MIN_VALUE
        }
    }

    companion object {
        private val remainingLogEntries = AtomicInteger(768)

        fun create(
            boundary: String,
            positionStep: Long = 240L,
            nominalPeriodNs: Long = 5_000_000L,
        ): AudioCadenceDebugProbe? = if (BuildConfig.DEBUG) {
            AudioCadenceDebugProbe(
                boundary,
                emit = {
                    if (remainingLogEntries.getAndDecrement() > 0) runCatching { Log.i("WarpnectAudioDiagnostics", it) }
                },
                positionStep = positionStep,
                nominalPeriodNs = nominalPeriodNs,
                maxWindows = 24,
            )
        } else {
            null
        }
    }
}
