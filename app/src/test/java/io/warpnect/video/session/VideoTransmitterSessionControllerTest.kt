package io.warpnect.video.session

import android.view.Surface
import io.warpnect.capture.CaptureCapabilities
import io.warpnect.capture.CaptureError
import io.warpnect.capture.CapturePermissionResult
import io.warpnect.capture.CaptureRequest
import io.warpnect.capture.CaptureSessionSnapshot
import io.warpnect.capture.CaptureStartResult
import io.warpnect.capture.CaptureStopResult
import io.warpnect.capture.VideoCaptureController
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.VideoBitrateMode
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderCapabilities
import io.warpnect.video.encoder.VideoEncoderControlResult
import io.warpnect.video.encoder.VideoEncoderController
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.encoder.VideoEncoderPrepareResult
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.encoder.VideoEncoderSnapshot
import io.warpnect.video.encoder.VideoEncoderStartResult
import io.warpnect.video.encoder.VideoEncoderStopResult
import io.warpnect.video.transport.VideoTransportCloseResult
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportController
import io.warpnect.video.transport.VideoTransportError
import io.warpnect.video.transport.VideoTransportOpenResult
import io.warpnect.video.transport.VideoTransportSnapshot
import io.warpnect.video.transport.VideoTransportState
import io.warpnect.video.transport.VideoTransportSubmitResult
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTransmitterSessionControllerTest {
    @Test
    fun transportOpenFailureStopsBeforeEncoderOrCapture() = runBlocking {
        val capture = FakeCapture()
        val encoder = FakeEncoder()
        val transport = FakeTransport(openError = VideoTransportError.UdpOpenFailed)
        val session = DefaultVideoTransmitterSessionController(capture, encoder, transport)

        val result = session.start(config())

        assertEquals(VideoSessionError.TransportFailed, result.error)
        assertEquals(0, encoder.prepareCalls)
        assertEquals(0, capture.startCalls)
        assertEquals(VideoSessionState.Error, session.snapshot().state)
    }

    @Test
    fun encoderPrepareFailureRollsBackOpenedTransport() = runBlocking {
        val capture = FakeCapture()
        val encoder = FakeEncoder(prepareError = VideoEncoderError.None)
        val transport = FakeTransport()
        val session = DefaultVideoTransmitterSessionController(capture, encoder, transport)

        val result = session.start(config())

        assertEquals(VideoSessionError.EncoderFailed, result.error)
        assertEquals(1, encoder.prepareCalls)
        assertEquals(1, encoder.stopCalls)
        assertEquals(1, transport.closeCalls)
        assertEquals(0, capture.startCalls)
        assertEquals(VideoSessionState.Error, session.snapshot().state)
    }

    private fun config(): VideoTransmitterSessionConfig = VideoTransmitterSessionConfig(
        captureRequest = CaptureRequest(
            sourceDisplayId = 0,
            outputWidth = 640,
            outputHeight = 360,
        ),
        encoderRequest = VideoEncoderRequest(
            codec = VideoCodec.Avc,
            width = 640,
            height = 360,
            frameRate = 30,
            bitrateBps = 2_000_000,
            iFrameIntervalSeconds = 1f,
            bitrateMode = VideoBitrateMode.Cbr,
        ),
        transportConfig = VideoTransportConfig(
            remoteAddress = "127.0.0.1",
            remotePort = 9000,
            maxWireDatagramSize = 1200,
            retransmissionCacheSlots = 64,
        ),
    )

    private class FakeCapture : VideoCaptureController {
        var startCalls = 0

        override suspend fun queryCapabilities(): CaptureCapabilities =
            throw AssertionError("unexpected queryCapabilities")

        override suspend fun requestPermission(): CapturePermissionResult =
            throw AssertionError("unexpected requestPermission")

        override suspend fun start(request: CaptureRequest, target: Surface): CaptureStartResult {
            startCalls += 1
            return CaptureStartResult(CaptureError.None, snapshot())
        }

        override suspend fun stop(): CaptureStopResult = CaptureStopResult(CaptureError.None, snapshot())

        override fun snapshot(): CaptureSessionSnapshot = CaptureSessionSnapshot()

        override fun close() = Unit
    }

    private class FakeEncoder(
        private val prepareError: VideoEncoderError = VideoEncoderError.None,
    ) : VideoEncoderController {
        var prepareCalls = 0
        var stopCalls = 0

        override suspend fun queryCapabilities(request: VideoEncoderRequest): VideoEncoderCapabilities =
            throw AssertionError("unexpected queryCapabilities")

        override suspend fun prepare(request: VideoEncoderRequest, sink: EncodedVideoSink): VideoEncoderPrepareResult {
            prepareCalls += 1
            return VideoEncoderPrepareResult(
                error = prepareError,
                inputSurface = null,
                capabilities = null,
                snapshot = snapshot(),
            )
        }

        override suspend fun start(): VideoEncoderStartResult =
            VideoEncoderStartResult(VideoEncoderError.CodecStartFailed, snapshot())

        override suspend fun requestKeyFrame(): VideoEncoderControlResult =
            throw AssertionError("unexpected requestKeyFrame")

        override suspend fun updateBitrate(bitrateBps: Int): VideoEncoderControlResult =
            throw AssertionError("unexpected updateBitrate")

        override suspend fun stop(): VideoEncoderStopResult {
            stopCalls += 1
            return VideoEncoderStopResult(VideoEncoderError.None, snapshot())
        }

        override fun snapshot(): VideoEncoderSnapshot = VideoEncoderSnapshot()

        override fun close() = Unit
    }

    private class FakeTransport(
        private val openError: VideoTransportError = VideoTransportError.None,
    ) : VideoTransportController {
        var closeCalls = 0

        override fun open(config: VideoTransportConfig): VideoTransportOpenResult = VideoTransportOpenResult(
            error = openError,
            snapshot = snapshot(),
        )

        override fun submitStreamConfig(format: VideoEncoderOutputFormat): VideoTransportError {
            return VideoTransportError.None
        }

        override fun submitAccessUnit(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            keyframe: Boolean,
        ): VideoTransportError = VideoTransportError.None

        override fun handleControlDatagram(buffer: ByteBuffer, offset: Int, size: Int): VideoTransportSubmitResult {
            return VideoTransportSubmitResult(VideoTransportError.None, snapshot())
        }

        override fun snapshot(): VideoTransportSnapshot = VideoTransportSnapshot(
            state = if (openError == VideoTransportError.None) {
                VideoTransportState.Ready
            } else {
                VideoTransportState.Error
            },
            lastError = openError,
        )

        override fun closeResult(): VideoTransportCloseResult {
            closeCalls += 1
            return VideoTransportCloseResult(VideoTransportError.None, snapshot())
        }
    }
}
