package io.warpnect.platform.capture

import android.view.Surface

internal fun interface CaptureSurfaceValidator {
    fun isValid(surface: Surface): Boolean
}

internal object AndroidCaptureSurfaceValidator : CaptureSurfaceValidator {
    override fun isValid(surface: Surface): Boolean = try {
        surface.isValid
    } catch (_: RuntimeException) {
        false
    }
}
