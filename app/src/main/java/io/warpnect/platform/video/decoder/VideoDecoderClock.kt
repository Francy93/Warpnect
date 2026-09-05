package io.warpnect.platform.video.decoder

import android.os.SystemClock

internal object VideoDecoderClock {
    fun monotonicNs(): Long = SystemClock.elapsedRealtimeNanos()

    fun monotonicUs(): Long = monotonicNs() / 1_000L
}
