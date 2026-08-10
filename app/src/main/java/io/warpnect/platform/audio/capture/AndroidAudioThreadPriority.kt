package io.warpnect.platform.audio.capture

import android.os.Process

internal object AndroidAudioThreadPriority {
    fun applyCapturePriority(): Boolean = try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        true
    } catch (_: RuntimeException) {
        false
    }
}
