package io.warpnect.platform.input.mapping

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper

data class TargetDisplayGeometry(
    val displayId: Int,
    val logicalWidthPx: Int = 0,
    val logicalHeightPx: Int = 0,
    val rotation: Int = 0,
    val generation: Long = 0L,
    val valid: Boolean = false,
) {
    fun isUsable(): Boolean = valid && displayId >= 0 && logicalWidthPx > 0 && logicalHeightPx > 0
}

fun interface TargetDisplayGeometryProvider {
    fun geometryFor(displayId: Int): TargetDisplayGeometry?
}

/**
 * Cold-path target-display provider. It reads Android logical display dimensions with
 * Display.getRealSize(), caches them, and invalidates the cache from DisplayManager callbacks.
 */
class AndroidTargetDisplayGeometryProvider(
    context: Context,
) : TargetDisplayGeometryProvider, DisplayManager.DisplayListener, AutoCloseable {
    private val displayManager = context.applicationContext.getSystemService(DisplayManager::class.java)
    private val callbackHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var cachedGeometry: TargetDisplayGeometry? = null

    @Volatile
    private var closed = false

    init {
        displayManager?.registerDisplayListener(this, callbackHandler)
    }

    override fun geometryFor(displayId: Int): TargetDisplayGeometry? {
        if (displayId < 0 || closed) return null
        val cached = cachedGeometry
        return if (cached != null && cached.displayId == displayId) cached else refresh(displayId)
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) {
        if (cachedGeometry?.displayId == displayId) {
            cachedGeometry = TargetDisplayGeometry(displayId = displayId)
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        if (cachedGeometry?.displayId == displayId) {
            refresh(displayId)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { displayManager?.unregisterDisplayListener(this) }
        cachedGeometry = null
    }

    @Suppress("DEPRECATION")
    private fun refresh(displayId: Int): TargetDisplayGeometry? {
        val display = displayManager?.getDisplay(displayId) ?: return null
        val size = Point()
        display.getRealSize(size)
        val previous = cachedGeometry
        val changed = previous == null ||
            previous.displayId != displayId ||
            previous.logicalWidthPx != size.x ||
            previous.logicalHeightPx != size.y ||
            previous.rotation != display.rotation
        val generation = when {
            previous == null -> 1L
            !changed -> previous.generation
            previous.generation == Long.MAX_VALUE -> 1L
            else -> previous.generation + 1L
        }
        return TargetDisplayGeometry(
            displayId = displayId,
            logicalWidthPx = size.x,
            logicalHeightPx = size.y,
            rotation = display.rotation,
            generation = generation,
            valid = size.x > 0 && size.y > 0,
        ).also { cachedGeometry = it }
    }
}
