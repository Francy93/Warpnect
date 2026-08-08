package io.warpnect.platform.video.encoder

import android.os.SystemClock

internal object VideoEncoderClock {
    fun monotonicUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L
}
