package io.warpnect.platform.audio

import android.util.Log
import io.warpnect.BuildConfig

/** Kernel REALTIME intervals and local MONOTONIC stages stay separate. No remote clock arithmetic. */
internal class AudioReceiveTimingDebugProbe(
    private val emit: (String) -> Unit,
    nominalPeriodNs: Long = 5_000_000L,
    private val maxWindows: Int = 12,
) {
    private val kernel = AudioCadenceDebugProbe("kernel_receive_REALTIME", emit, 1L, nominalPeriodNs, maxWindows)
    private val read = AudioCadenceDebugProbe("socket_read_MONOTONIC", emit, 1L, nominalPeriodNs, maxWindows)
    private val authenticated = AudioCadenceDebugProbe("authenticated_MONOTONIC", emit, 1L, nominalPeriodNs, maxWindows)
    private var startNs = 0L
    private var windows = 0
    private var maxAgeNs = 0L
    private var maxAuthenticationNs = 0L
    private var maxPumpLockWaitNs = 0L
    private var maxListenerNs = 0L
    private var previousReadNs = 0L
    private var previousKernelNs = 0L
    private var gapSamples = 0

    fun observe(values: LongArray) {
        if (windows >= maxWindows || values.size < 20) return
        val sequence = values[14]
        val readGap = values[16] - previousReadNs
        val kernelGap = values[15] - previousKernelNs
        if (previousReadNs > 0L && readGap >= 20_000_000L && gapSamples++ < 48) {
            emit(
                "AUDIO_RECEIVE_GAP sequence=$sequence position=${values[10]} " +
                    "read_ns=${values[16]} authenticated_ns=${values[17]} " +
                    "read_gap_ns=$readGap kernel_gap_ns=$kernelGap socket_age_ns=${values[18]}",
            )
        }
        previousReadNs = values[16]
        previousKernelNs = values[15]
        kernel.observe(sequence, values[15])
        read.observe(sequence, values[16])
        authenticated.observe(sequence, values[17])
        maxAgeNs = maxOf(maxAgeNs, values[18])
        maxAuthenticationNs = maxOf(maxAuthenticationNs, (values[17] - values[16]).coerceAtLeast(0L))
        maxPumpLockWaitNs = maxOf(maxPumpLockWaitNs, values[19])
        val nowNs = values[17]
        if (startNs == 0L) startNs = nowNs
        if (nowNs > 0L && nowNs - startNs >= 5_000_000_000L) {
            emit(
                "AUDIO_RECEIVE_TIMING window=$windows max_socket_age_ns=$maxAgeNs " +
                    "max_authentication_ns=$maxAuthenticationNs max_pump_lock_wait_ns=$maxPumpLockWaitNs " +
                    "max_listener_ns=$maxListenerNs last_sequence=$sequence",
            )
            windows++
            startNs = nowNs
            maxAgeNs = 0L
            maxAuthenticationNs = 0L
            maxPumpLockWaitNs = 0L
            maxListenerNs = 0L
        }
    }

    fun listenerCompleted(elapsedNs: Long) {
        maxListenerNs = maxOf(maxListenerNs, elapsedNs.coerceAtLeast(0L))
    }

    companion object {
        fun create(nominalPeriodNs: Long = 5_000_000L): AudioReceiveTimingDebugProbe? = if (BuildConfig.DEBUG) {
            AudioReceiveTimingDebugProbe({ Log.i("WarpnectAudioDiagnostics", it) }, nominalPeriodNs, 24)
        } else {
            null
        }
    }
}
