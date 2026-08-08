package io.warpnect.video.transport

import java.nio.ByteBuffer

interface VideoTransportController : EncodedVideoTransportBackend, AutoCloseable {
    fun open(config: VideoTransportConfig): VideoTransportOpenResult

    fun handleControlDatagram(buffer: ByteBuffer, offset: Int, size: Int): VideoTransportSubmitResult

    fun snapshot(): VideoTransportSnapshot

    fun closeResult(): VideoTransportCloseResult

    override fun close() {
        closeResult()
    }
}
