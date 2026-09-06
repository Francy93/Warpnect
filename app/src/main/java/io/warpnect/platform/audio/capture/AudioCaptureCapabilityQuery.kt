package io.warpnect.platform.audio.capture

import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioCaptureRequest

internal fun queryCapabilitiesAndClose(
    controller: AudioCaptureController,
    request: AudioCaptureRequest,
): AudioCaptureCapabilities = try {
    controller.queryCapabilities(request)
} finally {
    controller.close()
}
