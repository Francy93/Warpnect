package io.warpnect.platform.diagnostics

import android.os.SystemClock
import io.warpnect.diagnostics.DiagnosticEventClock

/** Android BOOTTIME for retained diagnostic record timestamps. */
object AndroidDiagnosticEventClock : DiagnosticEventClock {
    override fun nowNs(): ULong = SystemClock.elapsedRealtimeNanos().coerceAtLeast(0L).toULong()
}
