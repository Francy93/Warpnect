package io.warpnect.platform.capture

import android.view.Surface
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import java.nio.ByteBuffer

/** Fixed mirror control only. Surface parceling belongs to a separate authenticated Binder lease. */
internal interface RootVideoControl {
    fun videoCapabilities(): CaptureCapabilities
    fun startVideo(request: CaptureRequest, target: Surface): CaptureError
    fun updateVideo(request: CaptureRequest): CaptureError
    fun stopVideo(): CaptureError
    fun videoSnapshot(): CaptureSessionSnapshot
}

internal data class RootVideoRequest(
    val generation: Long,
    val surfaceGeneration: Long,
    val capture: CaptureRequest,
)

internal object RootVideoRequestCodec {
    const val SIZE = 40

    fun encode(value: RootVideoRequest): ByteArray {
        require(valid(value)) { "root_video_request_invalid" }
        return ByteBuffer.allocate(SIZE).putInt(PrivilegedSurfaceLease.MAGIC).putInt(PrivilegedSurfaceLease.VERSION)
            .putLong(value.generation).putLong(value.surfaceGeneration).putInt(value.capture.sourceDisplayId)
            .putInt(value.capture.outputWidth).putInt(value.capture.outputHeight)
            .putInt(if (value.capture.followSourceRotation) 1 else 0).array()
    }

    fun decode(bytes: ByteArray): RootVideoRequest {
        require(bytes.size == SIZE) { "root_video_request_size" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == PrivilegedSurfaceLease.MAGIC && buffer.int == PrivilegedSurfaceLease.VERSION)
        val generation = buffer.long
        val surface = buffer.long
        val display = buffer.int
        val width = buffer.int
        val height = buffer.int
        val follow = buffer.int.also { require(it == 0 || it == 1) }
        return RootVideoRequest(generation, surface, CaptureRequest(display, width, height, follow == 1))
            .also { require(valid(it)) }
    }

    private fun valid(value: RootVideoRequest) = value.generation > 0 && value.surfaceGeneration > 0 &&
        value.capture.sourceDisplayId == 0 && value.capture.outputWidth in 1..4096 &&
        value.capture.outputHeight in 1..4096
}
