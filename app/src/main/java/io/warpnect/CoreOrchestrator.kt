package io.warpnect

import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.decoder.AudioDecoderController
import io.warpnect.audio.encoder.AudioEncoderController
import io.warpnect.audio.playback.AudioPlaybackController
import io.warpnect.audio.session.AudioReceiverSessionController
import io.warpnect.audio.session.AudioTransmitterSessionController
import io.warpnect.audio.transport.AudioTransportController
import io.warpnect.avsync.AvSyncController
import io.warpnect.capture.VideoCaptureController
import io.warpnect.input.capture.InputCaptureController
import io.warpnect.input.injection.InputInjectionController
import io.warpnect.input.mapping.RemoteVideoViewportInputMapper
import io.warpnect.input.transport.InputTransportController
import io.warpnect.platform.input.mapping.TargetInputMapper
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
    val systemAudioTransportController: AudioTransportController? = null,
    val microphoneAudioTransportController: AudioTransportController? = null,
    val systemAudioDecoderController: AudioDecoderController? = null,
    val microphoneAudioDecoderController: AudioDecoderController? = null,
    val systemAudioPlaybackController: AudioPlaybackController? = null,
    val microphoneAudioPlaybackController: AudioPlaybackController? = null,
    val systemAudioTransmitterSessionController: AudioTransmitterSessionController? = null,
    val microphoneAudioTransmitterSessionController: AudioTransmitterSessionController? = null,
    val systemAudioReceiverSessionController: AudioReceiverSessionController? = null,
    val microphoneAudioReceiverSessionController: AudioReceiverSessionController? = null,
    val avSyncController: AvSyncController? = null,
    val inputCaptureController: InputCaptureController? = null,
    val remoteVideoViewportInputMapper: RemoteVideoViewportInputMapper? = null,
    val inputTransportController: InputTransportController? = null,
    val targetInputMapper: TargetInputMapper? = null,
    val inputInjectionController: InputInjectionController? = null,
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
        avSyncController?.close()
        videoTransmitterSessionController?.close()
        videoReceiverSessionController?.close()
        systemAudioTransmitterSessionController?.close()
        microphoneAudioTransmitterSessionController?.close()
        systemAudioReceiverSessionController?.close()
        microphoneAudioReceiverSessionController?.close()
        inputCaptureController?.close()
        remoteVideoViewportInputMapper?.close()
        inputTransportController?.close()
        targetInputMapper?.close()
        inputInjectionController?.close()
        transmitterVideoCaptureController?.close()
        transmitterVideoEncoderController?.close()
        transmitterVideoTransportController?.close()
        receiverVideoDecoderController?.close()
        receiverVideoRenderController?.close()
        systemAudioCaptureController?.close()
        microphoneAudioCaptureController?.close()
        systemAudioEncoderController?.close()
        microphoneAudioEncoderController?.close()
        systemAudioTransportController?.close()
        microphoneAudioTransportController?.close()
        systemAudioDecoderController?.close()
        microphoneAudioDecoderController?.close()
        systemAudioPlaybackController?.close()
        microphoneAudioPlaybackController?.close()
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
