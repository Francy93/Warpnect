package io.warpnect.platform.diagnostics

import android.os.SystemClock
import io.warpnect.diagnostics.ui.DiagnosticsUiClock

/** UI refresh age shares AndroidTelemetryClock's BOOTTIME basis without entering runtime hot paths. */
object AndroidDiagnosticsUiClock : DiagnosticsUiClock {
    override fun nowNs(): ULong = SystemClock.elapsedRealtimeNanos().coerceAtLeast(0L).toULong()
}
