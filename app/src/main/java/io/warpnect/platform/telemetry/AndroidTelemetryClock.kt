package io.warpnect.platform.telemetry

import android.os.SystemClock
import io.warpnect.telemetry.TelemetryMonotonicClock

/** Android's elapsed-realtime domain for top-level local snapshot timestamps. */
object AndroidTelemetryClock : TelemetryMonotonicClock {
    override fun nowNs(): ULong = SystemClock.elapsedRealtimeNanos().coerceAtLeast(0L).toULong()
}
