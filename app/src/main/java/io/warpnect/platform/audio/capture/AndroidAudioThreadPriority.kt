package io.warpnect.platform.audio.capture

import android.os.Process

internal object AndroidAudioThreadPriority {
    fun applyMicrophoneCapturePriority(setPriority: (Int) -> Unit = Process::setThreadPriority): Boolean = try {
        setPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun applyMicrophoneReceiverPriority(): Boolean = try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun applyCapturePriority(): Boolean = try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        true
    } catch (_: RuntimeException) {
        false
    }
}
