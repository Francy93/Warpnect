package io.warpnect.video.session

import io.warpnect.capture.VideoCaptureController
import io.warpnect.video.encoder.VideoEncoderController
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.transport.SclEncodedVideoSink
import io.warpnect.video.transport.VideoTransportController
import io.warpnect.video.transport.VideoTransportError
import kotlinx.coroutines.runBlocking

interface VideoTransmitterSessionController : AutoCloseable {
    suspend fun start(config: VideoTransmitterSessionConfig): VideoSessionControlResult

    suspend fun stop(): VideoSessionControlResult

    fun snapshot(): VideoTransmitterSessionSnapshot

    override fun close()
}

class DefaultVideoTransmitterSessionController(
    private val captureController: VideoCaptureController,
    private val encoderController: VideoEncoderController,
    private val transportController: VideoTransportController,
    private val senderControlRuntimeFactory: VideoSenderControlRuntimeFactory =
        VideoSenderControlRuntimeFactory.None,
) : VideoTransmitterSessionController {
    private var state: VideoSessionState = VideoSessionState.Idle
    private var lastConfig: VideoTransmitterSessionConfig? = null
    private var lastError = VideoSessionFailure()
    private var controlRuntime: VideoSenderControlRuntime? = null

    override suspend fun start(config: VideoTransmitterSessionConfig): VideoSessionControlResult {
        if (state == VideoSessionState.Closed) {
            return remember(sessionFailure(VideoSessionError.Closed))
        }
        if (state != VideoSessionState.Idle && state != VideoSessionState.Error) {
            return remember(sessionFailure(VideoSessionError.AlreadyRunning))
        }
        if (config.senderControlPumpTimeoutUs < 0L) {
            return remember(sessionFailure(VideoSessionError.InvalidConfiguration))
        }
        if (config.performanceConfig.validate() != VideoTransportError.None) {
            return remember(sessionFailure(VideoSessionError.InvalidConfiguration))
        }

        state = VideoSessionState.Starting
        lastConfig = config
        var transportOpened = false
        var encoderPrepared = false
        var encoderStarted = false
        var captureStarted = false

        val transportOpen = transportController.open(config.transportConfig)
        if (!transportOpen.isSuccess) {
            rollback(captureStarted, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                VideoSessionFailure(
                    source = VideoSessionErrorSource.Transport,
                    error = VideoSessionError.TransportFailed,
                    transportError = transportOpen.error,
                ),
            )
        }
        transportOpened = true

        val sink = SclEncodedVideoSink(transportController)
        val prepare = encoderController.prepare(config.encoderRequest, sink)
        encoderPrepared = prepare.error == VideoEncoderError.None
        if (!prepare.isSuccess) {
            rollback(captureStarted, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                VideoSessionFailure(
                    source = VideoSessionErrorSource.Encoder,
                    error = VideoSessionError.EncoderFailed,
                    encoderError = prepare.error,
                ),
            )
        }
        encoderPrepared = true
        val inputSurface = prepare.inputSurface

        val encoderStart = encoderController.start()
        if (!encoderStart.isSuccess || inputSurface == null) {
            rollback(captureStarted, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                VideoSessionFailure(
                    source = VideoSessionErrorSource.Encoder,
                    error = VideoSessionError.EncoderFailed,
                    encoderError = encoderStart.error,
                ),
            )
        }
        encoderStarted = true

        controlRuntime = senderControlRuntimeFactory.create(
            transportController,
            VideoKeyFrameRequestHandler { requestEncoderKeyFrameBlocking() },
        )
        val controlStart = controlRuntime?.start(config.senderControlPumpTimeoutUs)
            ?: VideoSessionControlResult.Success
        if (!controlStart.isSuccess) {
            rollback(captureStarted, encoderStarted || encoderPrepared, transportOpened)
            return remember(controlStart.failure)
        }

        val captureStart = captureController.start(config.captureRequest, inputSurface)
        if (!captureStart.isSuccess) {
            captureStarted = false
            rollback(captureStarted, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                VideoSessionFailure(
                    source = VideoSessionErrorSource.Capture,
                    error = VideoSessionError.CaptureFailed,
                    captureError = captureStart.error,
                ),
            )
        }
        captureStarted = true

        state = VideoSessionState.Streaming
        lastError = VideoSessionFailure()
        return VideoSessionControlResult.Success
    }

    override suspend fun stop(): VideoSessionControlResult {
        if (state == VideoSessionState.Closed) {
            return remember(sessionFailure(VideoSessionError.Closed))
        }
        if (state == VideoSessionState.Idle) {
            return VideoSessionControlResult.Success
        }
        state = VideoSessionState.Stopping
        captureController.stop()
        encoderController.stop()
        controlRuntime?.stop()
        controlRuntime = null
        transportController.closeResult()
        state = VideoSessionState.Idle
        lastError = VideoSessionFailure()
        return VideoSessionControlResult.Success
    }

    override fun snapshot(): VideoTransmitterSessionSnapshot = VideoTransmitterSessionSnapshot(
        state = state,
        remoteAddress = lastConfig?.transportConfig?.remoteAddress,
        remotePort = lastConfig?.transportConfig?.remotePort,
        capture = captureController.snapshot(),
        encoder = encoderController.snapshot(),
        transport = transportController.snapshot(),
        control = controlRuntime?.snapshot(),
        lastError = lastError,
    )

    override fun close() {
        controlRuntime?.close()
        controlRuntime = null
        captureController.close()
        encoderController.close()
        transportController.close()
        state = VideoSessionState.Closed
    }

    private suspend fun rollback(
        captureStarted: Boolean,
        encoderStartedOrPrepared: Boolean,
        transportOpened: Boolean,
    ) {
        if (captureStarted) {
            captureController.stop()
        }
        if (encoderStartedOrPrepared) {
            encoderController.stop()
        }
        controlRuntime?.stop()
        controlRuntime = null
        if (transportOpened) {
            transportController.closeResult()
        }
        state = VideoSessionState.Error
    }

    private fun remember(failure: VideoSessionFailure): VideoSessionControlResult {
        lastError = failure
        if (failure.error != VideoSessionError.None && state != VideoSessionState.Closed) {
            state = VideoSessionState.Error
        }
        return VideoSessionControlResult(failure.error, failure)
    }

    private fun sessionFailure(error: VideoSessionError): VideoSessionFailure = VideoSessionFailure(
        source = VideoSessionErrorSource.Session,
        error = error,
    )

    private fun requestEncoderKeyFrameBlocking(): VideoSessionControlResult = runBlocking {
        val requested = encoderController.requestKeyFrame()
        if (requested.isSuccess) {
            VideoSessionControlResult.Success
        } else {
            VideoSessionControlResult(
                error = VideoSessionError.EncoderFailed,
                failure = VideoSessionFailure(
                    source = VideoSessionErrorSource.Encoder,
                    error = VideoSessionError.EncoderFailed,
                    encoderError = requested.error,
                ),
            )
        }
    }
}
