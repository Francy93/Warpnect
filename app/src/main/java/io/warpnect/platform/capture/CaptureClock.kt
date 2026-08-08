package io.warpnect.platform.capture

import android.os.SystemClock

internal object CaptureClock {
    fun monotonicUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L
}
