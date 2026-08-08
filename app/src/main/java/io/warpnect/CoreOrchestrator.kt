package io.warpnect

import io.warpnect.capture.VideoCaptureController
import io.warpnect.video.decoder.VideoDecoderController
import io.warpnect.video.encoder.VideoEncoderController
import io.warpnect.video.transport.VideoTransportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CoreOrchestrator(
    val transmitterVideoCaptureController: VideoCaptureController? = null,
    val transmitterVideoEncoderController: VideoEncoderController? = null,
    val transmitterVideoTransportController: VideoTransportController? = null,
    val receiverVideoDecoderController: VideoDecoderController? = null,
) {
    private val _role = MutableStateFlow<WarpnectRole>(WarpnectRole.Receiver)
    val role: StateFlow<WarpnectRole> = _role.asStateFlow()

    fun enterIdle() {
        _role.value = WarpnectRole.Idle
    }

    fun enterReceiverMode() {
        _role.value = WarpnectRole.Receiver
    }

    fun enterTransmitterMode() {
        _role.value = WarpnectRole.Transmitter
    }

    fun shutdown() {
        transmitterVideoCaptureController?.close()
        transmitterVideoEncoderController?.close()
        transmitterVideoTransportController?.close()
        receiverVideoDecoderController?.close()
        enterIdle()
    }
}

sealed interface WarpnectRole {
    val displayName: String

    data object Idle : WarpnectRole {
        override val displayName: String = "Idle"
    }

    data object Receiver : WarpnectRole {
        override val displayName: String = "Receiver"
    }

    data object Transmitter : WarpnectRole {
        override val displayName: String = "Transmitter"
    }
}
