package io.warpnect.platform.capture.privileged

/** Keeps the privileged legacy configuration order explicit and independently testable. */
internal object LegacySurfaceControlTransactionRunner {
    fun configure(
        open: () -> Unit,
        attachSurface: (() -> Unit)?,
        setProjection: () -> Unit,
        setLayerStack: () -> Unit,
        close: () -> Unit,
    ) {
        open()
        try {
            attachSurface?.invoke()
            setProjection()
            setLayerStack()
        } finally {
            close()
        }
    }
}
