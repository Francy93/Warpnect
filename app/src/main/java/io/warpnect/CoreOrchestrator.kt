package io.warpnect

import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.encoder.AudioEncoderController
import io.warpnect.capture.VideoCaptureController
import io.warpnect.video.decoder.VideoDecoderController
import io.warpnect.video.encoder.VideoEncoderController
import io.warpnect.video.render.VideoRenderController
import io.warpnect.video.session.VideoReceiverSessionController
import io.warpnect.video.session.VideoTransmitterSessionController
import io.warpnect.video.transport.VideoTransportController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CoreOrchestrator(
    val transmitterVideoCaptureController: VideoCaptureController? = null,
    val transmitterVideoEncoderController: VideoEncoderController? = null,
    val transmitterVideoTransportController: VideoTransportController? = null,
    val videoTransmitterSessionController: VideoTransmitterSessionController? = null,
    val receiverVideoDecoderController: VideoDecoderController? = null,
    val receiverVideoRenderController: VideoRenderController? = null,
    val videoReceiverSessionController: VideoReceiverSessionController? = null,
    val systemAudioCaptureController: AudioCaptureController? = null,
    val microphoneAudioCaptureController: AudioCaptureController? = null,
    val systemAudioEncoderController: AudioEncoderController? = null,
    val microphoneAudioEncoderController: AudioEncoderController? = null,
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
        videoTransmitterSessionController?.close()
        videoReceiverSessionController?.close()
        transmitterVideoCaptureController?.close()
        transmitterVideoEncoderController?.close()
        transmitterVideoTransportController?.close()
        receiverVideoDecoderController?.close()
        receiverVideoRenderController?.close()
        systemAudioCaptureController?.close()
        microphoneAudioCaptureController?.close()
        systemAudioEncoderController?.close()
        microphoneAudioEncoderController?.close()
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
