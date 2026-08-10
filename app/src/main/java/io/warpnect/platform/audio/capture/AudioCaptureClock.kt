package io.warpnect.platform.audio.capture

object AudioCaptureClock {
    fun monotonicNs(): Long = System.nanoTime()
}
