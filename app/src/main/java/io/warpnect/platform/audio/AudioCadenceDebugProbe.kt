package io.warpnect.platform.audio

import android.util.Log
import io.warpnect.BuildConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Local delivery cadence only. Remote capture timestamps are deliberately not subtracted. */
internal class AudioCadenceDebugProbe(
    private val boundary: String,
    private val emit: (String) -> Unit,
    private val positionStep: Long = 240L,
    private val nominalPeriodNs: Long = 5_000_000L,
    private val maxWindows: Int = 12,
    private val deferEmission: (() -> Unit) -> Unit = { it() },
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
    private var previousPosition = 0L
    private var hasPreviousPosition = false
    private var missing = 0L
    private var reordered = 0L
    private var duplicates = 0L
    private val intervals = IntArray(7)
    private var timelineOriginPosition = 0L
    private var hasTimelineOrigin = false
    private var timelineOriginNs = 0L
    private var timelineMinNs = Long.MAX_VALUE
    private var timelineMaxNs = Long.MIN_VALUE
    private val startupPositions = LongArray(32)
    private val startupTimes = LongArray(32)
    private var startupCount = 0

    fun observe(position: Long, nowNs: Long) {
        if (windows >= maxWindows) return
        if (nowNs <= 0L || (previousNs != 0L && nowNs < previousNs)) return
        if (startupCount < startupPositions.size) {
            startupPositions[startupCount] = position
            startupTimes[startupCount] = nowNs
            startupCount++
            if (startupCount == startupPositions.size) {
                deferEmission {
                    emit(
                        "AUDIO_STARTUP boundary=$boundary positions=${startupPositions.joinToString(",")} " +
                            "local_ns=${startupTimes.joinToString(",")}",
                    )
                }
            }
        }
        if (startNs == 0L) startNs = nowNs
        if (!hasTimelineOrigin) {
            timelineOriginPosition = position
            timelineOriginNs = nowNs
            hasTimelineOrigin = true
        }
        // Relative nominal sample progression, not remote time or one-way network latency.
        val phaseNs = nowNs - timelineOriginNs - (position - timelineOriginPosition) / positionStep * nominalPeriodNs
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
        if (hasPreviousPosition) {
            when {
                position == previousPosition -> duplicates++
                position < previousPosition -> reordered++
                position > previousPosition + positionStep ->
                    missing += (position - previousPosition) / positionStep - 1
            }
        }
        previousPosition = position
        hasPreviousPosition = true
        previousNs = nowNs
        count++
        if (nowNs - startNs >= 5_000_000_000L) {
            val snapshot = longArrayOf(
                windows.toLong(), count.toLong(), position, maxGapNs, maxBurst.toLong(), nowNs - startNs,
                missing, reordered, duplicates, timelineMinNs, timelineMaxNs,
            )
            val bins = intervals.copyOf()
            deferEmission {
                emit(
                    "AUDIO_CADENCE boundary=$boundary window=${snapshot[0]} count=${snapshot[1]} " +
                        "last_position=${snapshot[2]} max_gap_ns=${snapshot[3]} max_burst=${snapshot[4]} " +
                        "elapsed_ns=${snapshot[5]} " +
                        "interval_bins_lt1_3_7_10_20_40_else_ms=${bins.joinToString(",")} " +
                        "missing_units=${snapshot[6]} reordered=${snapshot[7]} duplicates=${snapshot[8]} " +
                        "nominal_phase_min_ns=${snapshot[9]} nominal_phase_max_ns=${snapshot[10]}",
                )
            }
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
        private val diagnostics = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32),
            { task -> Thread(task, "WarpnectAudioCadenceDiagnostics").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        )

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
                deferEmission = { diagnostics.execute(it) },
            )
        } else {
            null
        }
    }
}
