package io.warpnect.platform.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

internal enum class DisplayConfigurationEvent {
    Changed,
    Removed,
}

internal class DisplayConfigurationMonitor(
    context: Context,
) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var displayId: Int? = null
    private var callback: ((DisplayConfigurationEvent) -> Unit)? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (this@DisplayConfigurationMonitor.displayId == displayId) {
                callback?.invoke(DisplayConfigurationEvent.Changed)
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (this@DisplayConfigurationMonitor.displayId == displayId) {
                callback?.invoke(DisplayConfigurationEvent.Removed)
            }
        }
    }

    fun start(sourceDisplayId: Int, onEvent: (DisplayConfigurationEvent) -> Unit) {
        stop()
        displayId = sourceDisplayId
        callback = onEvent
        displayManager.registerDisplayListener(listener, handler)
    }

    fun stop() {
        if (displayId != null) {
            displayManager.unregisterDisplayListener(listener)
        }
        displayId = null
        callback = null
    }
}
