package io.warpnect.platform.video.decoder

import android.os.SystemClock

internal object VideoDecoderClock {
    fun monotonicUs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000L
}
