package io.warpnect.video.session

import io.warpnect.capture.CaptureError
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderSnapshot
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSnapshot
import io.warpnect.video.render.VideoRenderSnapshot
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoReceiverRuntimeSnapshot
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportError
import io.warpnect.video.transport.VideoTransportSnapshot

enum class VideoSessionState {
    Idle,
    Starting,
    WaitingForSurface,
    WaitingForConfig,
    PreparingDecoder,
    WaitingForKeyFrame,
    Streaming,
    Stopping,
    Error,
    Closed,
}

enum class VideoSessionErrorSource {
    None,
    Capture,
    Encoder,
    Transport,
    Receiver,
    Decoder,
    Renderer,
    Session,
}

enum class VideoSessionError {
    None,
    InvalidConfiguration,
    AlreadyRunning,
    NotRunning,
    Closed,
    CaptureFailed,
    EncoderFailed,
    TransportFailed,
    ReceiverFailed,
    DecoderFailed,
    RendererFailed,
    SurfaceUnavailable,
    StreamConfigRequired,
    KeyFrameRequired,
}

data class VideoSessionFailure(
    val source: VideoSessionErrorSource = VideoSessionErrorSource.None,
    val error: VideoSessionError = VideoSessionError.None,
    val captureError: CaptureError? = null,
    val encoderError: VideoEncoderError? = null,
    val transportError: VideoTransportError? = null,
    val decoderError: VideoDecoderError? = null,
)

data class VideoSessionControlResult(
    val error: VideoSessionError,
    val failure: VideoSessionFailure,
) {
    val isSuccess: Boolean
        get() = error == VideoSessionError.None

    companion object {
        val Success = VideoSessionControlResult(
            error = VideoSessionError.None,
            failure = VideoSessionFailure(),
        )
    }
}

data class VideoTransmitterSessionConfig(
    val captureRequest: CaptureRequest,
    val encoderRequest: VideoEncoderRequest,
    val transportConfig: VideoTransportConfig,
    val senderControlPumpTimeoutUs: Long = 20_000L,
)

data class VideoReceiverSessionConfig(
    val receiverRuntimeConfig: VideoReceiverRuntimeConfig,
    val maxDecoderInputSizeBytes: Int? = receiverRuntimeConfig.maxLogicalPayloadSize,
)

data class VideoTransmitterSessionSnapshot(
    val state: VideoSessionState = VideoSessionState.Idle,
    val remoteAddress: String? = null,
    val remotePort: Int? = null,
    val capture: CaptureSessionSnapshot? = null,
    val encoder: VideoEncoderSnapshot? = null,
    val transport: VideoTransportSnapshot? = null,
    val control: VideoSenderControlSnapshot? = null,
    val lastError: VideoSessionFailure = VideoSessionFailure(),
)

data class VideoReceiverSessionSnapshot(
    val state: VideoSessionState = VideoSessionState.Idle,
    val activeConfigGeneration: Long = 0,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val receiver: VideoReceiverRuntimeSnapshot? = null,
    val decoder: VideoDecoderSnapshot? = null,
    val renderer: VideoRenderSnapshot? = null,
    val framesDelivered: Long = 0,
    val nonKeyframesDroppedAwaitingKeyframe: Long = 0,
    val discontinuities: Long = 0,
    val lastPresentationTimeUs: Long? = null,
    val lastFrameId: Long? = null,
    val lastError: VideoSessionFailure = VideoSessionFailure(),
)

data class VideoSenderControlSnapshot(
    val running: Boolean = false,
    val pumpIterations: Long = 0,
    val transportErrors: Long = 0,
    val lastError: VideoTransportError = VideoTransportError.None,
)

internal fun VideoReceiverSessionConfig.toDecoderConfig(
    streamConfig: io.warpnect.video.transport.VideoReceiverStreamConfig,
): VideoDecoderConfig = VideoDecoderConfig(
    width = streamConfig.width,
    height = streamConfig.height,
    configGeneration = streamConfig.configGeneration,
    codecSpecificData = streamConfig.codecSpecificData,
    maxInputSizeBytes = maxDecoderInputSizeBytes,
)
