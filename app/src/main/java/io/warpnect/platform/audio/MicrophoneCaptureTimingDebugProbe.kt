package io.warpnect.platform.audio

import android.util.Log
import io.warpnect.BuildConfig

/** Measures synchronous capture work on one local monotonic clock; never microphone content. */
internal class MicrophoneCaptureTimingDebugProbe(private val emit: (String) -> Unit) {
    private var startNs = 0L
    private var windows = 0
    private var count = 0
    private var maxReadNs = 0L
    private var maxTimestampNs = 0L
    private var maxSinkNs = 0L
    private val readBins = IntArray(7)
    private var slowEvents = 0

    fun observe(position: Long, startedNs: Long, readNs: Long, timestampNs: Long, sinkNs: Long) {
        if (windows >= 24 || startedNs <= 0L || readNs < startedNs ||
            timestampNs < readNs || sinkNs < timestampNs
        ) {
            return
        }
        if (startNs == 0L) startNs = startedNs
        val duration = readNs - startedNs
        maxReadNs = maxOf(maxReadNs, duration)
        maxTimestampNs = maxOf(maxTimestampNs, timestampNs - readNs)
        maxSinkNs = maxOf(maxSinkNs, sinkNs - timestampNs)
        val bucket = READ_LIMITS_NS.indexOfFirst { duration < it }.let { if (it < 0) 6 else it }
        readBins[bucket]++
        count++
        if (sinkNs - startNs >= 5_000_000_000L) {
            emit(
                "MIC_CAPTURE_TIMING window=$windows count=$count last_position=$position " +
                    "max_read_ns=$maxReadNs max_post_read_ns=$maxTimestampNs max_sink_ns=$maxSinkNs " +
                    "read_bins_lt1_3_7_10_20_40_else_ms=${readBins.joinToString(",")}",
            )
            windows++
            startNs = sinkNs
            count = 0
            maxReadNs = 0L
            maxTimestampNs = 0L
            maxSinkNs = 0L
            readBins.fill(0)
        }
    }

    fun observeSlowChunk(
        position: Long,
        startedNs: Long,
        readNs: Long,
        timestampNs: Long,
        deliveryNs: Long,
        finishedNs: Long,
        cpuNs: Long,
    ) {
        if (slowEvents >= 32 || startedNs <= 0 || readNs < startedNs || timestampNs < readNs ||
            deliveryNs < timestampNs || finishedNs < deliveryNs || cpuNs < 0 ||
            finishedNs - startedNs < 20_000_000L
        ) {
            return
        }
        slowEvents++
        emit(
            "MIC_CAPTURE_SLOW position=$position start_ns=$startedNs end_ns=$finishedNs " +
                "read_ns=${readNs - startedNs} post_read_ns=${timestampNs - readNs} " +
                "pacing_ns=${deliveryNs - timestampNs} sink_ns=${finishedNs - deliveryNs} cpu_ns=$cpuNs",
        )
    }

    companion object {
        private val READ_LIMITS_NS = longArrayOf(1_000_000, 3_000_000, 7_000_000, 10_000_000, 20_000_000, 40_000_000)
        fun create(): MicrophoneCaptureTimingDebugProbe? = if (BuildConfig.DEBUG) {
            MicrophoneCaptureTimingDebugProbe { Log.i("WarpnectAudioDiagnostics", it) }
        } else {
            null
        }
    }
}
