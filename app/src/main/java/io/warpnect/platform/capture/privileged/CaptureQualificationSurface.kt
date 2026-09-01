package io.warpnect.platform.capture.privileged

import android.graphics.PixelFormat
import android.media.ImageReader

/**
 * Creates a short-lived non-media Surface for cold mirror-strategy qualification. No image is
 * acquired, read back, or retained; the application-owned encoder Surface is used only at capture
 * start.
 */
internal object CaptureQualificationSurface {
    fun create(): ImageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, MAX_IMAGES)

    private const val WIDTH = 64
    private const val HEIGHT = 64
    private const val MAX_IMAGES = 1
}
