package io.warpnect.audio.session

import io.warpnect.audio.capture.AudioCaptureController
import io.warpnect.audio.capture.AudioTimestampQuality
import io.warpnect.audio.encoder.AudioCodec
import io.warpnect.audio.encoder.AudioEncoderError
import io.warpnect.audio.encoder.EncodedAudioFormat
import io.warpnect.audio.encoder.EncodedAudioSink
import io.warpnect.audio.encoder.PcmSubmittingAudioEncoderController
import io.warpnect.audio.transport.AudioTransportController
import io.warpnect.audio.transport.AudioTransportError
import io.warpnect.platform.audio.encoder.OpusPcmAudioSink
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

interface AudioTransmitterSessionController : AutoCloseable {
    suspend fun start(config: AudioTransmitterSessionConfig): AudioSessionControlResult

    suspend fun stop(): AudioSessionControlResult

    fun snapshot(): AudioTransmitterSessionSnapshot

    override fun close()
}

class DefaultAudioTransmitterSessionController(
    private val captureController: AudioCaptureController,
    private val encoderController: PcmSubmittingAudioEncoderController,
    private val transportController: AudioTransportController,
) : AudioTransmitterSessionController {
    private var state = AudioSessionState.Idle
    private var lastConfig: AudioTransmitterSessionConfig? = null
    private var lastError = AudioSessionFailure()
    private var transportSink: FreshnessEncodedAudioSink? = null

    override suspend fun start(config: AudioTransmitterSessionConfig): AudioSessionControlResult {
        if (state == AudioSessionState.Closed) {
            return remember(sessionFailure(AudioSessionError.Closed))
        }
        if (state != AudioSessionState.Idle && state != AudioSessionState.Error) {
            return remember(sessionFailure(AudioSessionError.AlreadyRunning))
        }
        if (!config.sourcesMatch()) {
            return remember(sessionFailure(AudioSessionError.InvalidConfiguration))
        }

        state = AudioSessionState.Starting
        lastConfig = config
        var transportOpened = false
        var encoderPrepared = false
        var encoderStarted = false
        var capturePrepared = false
        var captureStarted = false

        val transportOpen = transportController.open(config.transportConfig)
        if (!transportOpen.isSuccess) {
            rollback(captureStarted, capturePrepared, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Transport,
                    error = AudioSessionError.TransportFailed,
                    transportError = transportOpen.error,
                ),
            )
        }
        transportOpened = true

        val sink = FreshnessEncodedAudioSink(transportController)
        transportSink = sink
        val prepare = encoderController.prepare(config.encoderRequest, sink)
        encoderPrepared = prepare.error == AudioEncoderError.None
        if (!prepare.isSuccess) {
            rollback(captureStarted, capturePrepared, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Encoder,
                    error = AudioSessionError.EncoderFailed,
                    encoderError = prepare.error,
                ),
            )
        }

        val encoderStart = encoderController.start()
        if (!encoderStart.isSuccess) {
            rollback(captureStarted, capturePrepared, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Encoder,
                    error = AudioSessionError.EncoderFailed,
                    encoderError = encoderStart.error,
                ),
            )
        }
        encoderStarted = true

        val capturePrepare = captureController.prepare(config.captureRequest, OpusPcmAudioSink(encoderController))
        capturePrepared = capturePrepare.error == io.warpnect.audio.capture.AudioCaptureError.None
        if (!capturePrepare.isSuccess) {
            rollback(captureStarted, capturePrepared, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Capture,
                    error = AudioSessionError.CaptureFailed,
                    captureError = capturePrepare.error,
                ),
            )
        }

        val captureStart = captureController.start()
        if (!captureStart.isSuccess) {
            rollback(captureStarted, capturePrepared, encoderStarted || encoderPrepared, transportOpened)
            return remember(
                AudioSessionFailure(
                    source = AudioSessionErrorSource.Capture,
                    error = AudioSessionError.CaptureFailed,
                    captureError = captureStart.error,
                ),
            )
        }
        captureStarted = true

        state = AudioSessionState.Streaming
        lastError = AudioSessionFailure()
        return AudioSessionControlResult.Success
    }

    override suspend fun stop(): AudioSessionControlResult {
        if (state == AudioSessionState.Closed) {
            return remember(sessionFailure(AudioSessionError.Closed))
        }
        if (state == AudioSessionState.Idle) {
            return AudioSessionControlResult.Success
        }
        state = AudioSessionState.Stopping
        captureController.stop()
        encoderController.stop()
        transportController.closeResult()
        transportSink = null
        state = AudioSessionState.Idle
        lastError = AudioSessionFailure()
        return AudioSessionControlResult.Success
    }

    override fun snapshot(): AudioTransmitterSessionSnapshot {
        val capture = captureController.snapshot()
        val encoder = encoderController.snapshot()
        val transport = transportController.snapshot()
        val sink = transportSink
        return AudioTransmitterSessionSnapshot(
            state = state,
            source = lastConfig?.captureRequest?.source,
            remoteAddress = lastConfig?.transportConfig?.remoteAddress,
            remotePort = lastConfig?.transportConfig?.remotePort,
            capture = capture,
            encoder = encoder,
            transport = transport,
            pcmFramesCaptured = capture.framesCaptured,
            opusFramesEncoded = encoder.encodedFrames,
            audioFramesSubmitted = transport.framesSubmitted,
            datagramsSent = transport.datagramsSent,
            wouldBlockDrops = sink?.wouldBlockDrops ?: 0L,
            sendFailureDrops = sink?.sendFailureDrops ?: 0L,
            lastFramePosition = encoder.lastEncodedFramePosition,
            lastCaptureTimeNs = encoder.lastCaptureTimeNs,
            lastError = lastError,
        )
    }

    override fun close() {
        runBlocking {
            stop()
        }
        captureController.close()
        encoderController.close()
        transportController.close()
        transportSink = null
        state = AudioSessionState.Closed
    }

    private suspend fun rollback(
        captureStarted: Boolean,
        capturePrepared: Boolean,
        encoderStartedOrPrepared: Boolean,
        transportOpened: Boolean,
    ) {
        if (captureStarted || capturePrepared) {
            captureController.stop()
        }
        if (encoderStartedOrPrepared) {
            encoderController.stop()
        }
        if (transportOpened) {
            transportController.closeResult()
        }
        transportSink = null
        state = AudioSessionState.Error
    }

    private fun remember(failure: AudioSessionFailure): AudioSessionControlResult {
        lastError = failure
        if (failure.error != AudioSessionError.None && state != AudioSessionState.Closed) {
            state = AudioSessionState.Error
        }
        return AudioSessionControlResult(failure.error, failure)
    }

    private fun sessionFailure(error: AudioSessionError): AudioSessionFailure = AudioSessionFailure(
        source = AudioSessionErrorSource.Session,
        error = error,
    )

    private fun AudioTransmitterSessionConfig.sourcesMatch(): Boolean =
        captureRequest.source == encoderRequest.source && captureRequest.source == transportConfig.source
}

private class FreshnessEncodedAudioSink(
    private val backend: AudioTransportController,
) : EncodedAudioSink {
    var formatSubmitted = false
        private set
    var wouldBlockDrops = 0L
        private set
    var sendFailureDrops = 0L
        private set
    var lastTransportError = AudioTransportError.None
        private set

    private var pendingDiscontinuity = false

    override fun onOutputFormatChanged(format: EncodedAudioFormat) {
        validateFormat(format).throwIfFailed()
        backend.submitStreamConfig(format).throwIfFailed()
        formatSubmitted = true
    }

    override fun onEncodedFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
        timestampQuality: AudioTimestampQuality,
        encodedFrameIndex: Long,
    ) {
        if (!formatSubmitted) {
            AudioTransportError.AudioConfigRequired.throwIfFailed()
        }
        validateFrame(buffer, offset, sizeBytes, firstFramePosition, captureTimeNs).throwIfFailed()
        val discontinuity = pendingDiscontinuity
        val error = backend.submitAudioFrame(
            buffer = buffer,
            offset = offset,
            sizeBytes = sizeBytes,
            firstFramePosition = firstFramePosition,
            captureTimeNs = captureTimeNs,
            timestampQuality = timestampQuality,
            discontinuityBefore = discontinuity,
        )
        lastTransportError = error
        when (error) {
            AudioTransportError.None -> {
                if (discontinuity) {
                    pendingDiscontinuity = false
                }
            }
            AudioTransportError.WouldBlock -> {
                wouldBlockDrops += 1
            }
            AudioTransportError.UdpSendFailed,
            AudioTransportError.PartialEmission,
            -> {
                sendFailureDrops += 1
            }
            else -> error.throwIfFailed()
        }
    }

    override fun onAudioDiscontinuity(expectedFramePosition: Long, actualFramePosition: Long) {
        pendingDiscontinuity = true
    }

    override fun onEncoderError(error: AudioEncoderError) = Unit

    private fun validateFormat(format: EncodedAudioFormat): AudioTransportError = when {
        format.codec != AudioCodec.Opus -> AudioTransportError.UnsupportedAudioCodec
        format.sampleRateHz !in SUPPORTED_SAMPLE_RATES -> AudioTransportError.InvalidSampleRate
        format.channelCount !in 1..2 -> AudioTransportError.InvalidChannelCount
        format.frameDurationUs !in SUPPORTED_FRAME_DURATIONS_US -> AudioTransportError.InvalidFrameDuration
        format.lookaheadSamples < 0 -> AudioTransportError.InvalidLookahead
        else -> AudioTransportError.None
    }

    private fun validateFrame(
        buffer: ByteBuffer,
        offset: Int,
        sizeBytes: Int,
        firstFramePosition: Long,
        captureTimeNs: Long,
    ): AudioTransportError = when {
        !buffer.isDirect -> AudioTransportError.NonDirectBuffer
        firstFramePosition < 0 -> AudioTransportError.InvalidFramePosition
        captureTimeNs < 0 -> AudioTransportError.InvalidCaptureTimestamp
        offset < 0 || sizeBytes <= 0 -> AudioTransportError.InvalidBufferRange
        offset.toLong() + sizeBytes.toLong() > buffer.capacity().toLong() -> AudioTransportError.InvalidBufferRange
        else -> AudioTransportError.None
    }

    private fun AudioTransportError.throwIfFailed() {
        lastTransportError = this
        if (this != AudioTransportError.None) {
            throw AudioTransmitterTransportException(this)
        }
    }

    private companion object {
        val SUPPORTED_SAMPLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)
        val SUPPORTED_FRAME_DURATIONS_US = setOf(2_500, 5_000, 10_000, 20_000)
    }
}

private class AudioTransmitterTransportException(
    val error: AudioTransportError,
) : RuntimeException("Audio transport failed: $error")
