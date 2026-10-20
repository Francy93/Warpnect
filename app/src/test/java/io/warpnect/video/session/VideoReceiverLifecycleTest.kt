package io.warpnect.video.session

import android.graphics.SurfaceTexture
import android.view.Surface
import io.warpnect.video.decoder.DecodedVideoFrame
import io.warpnect.video.decoder.DecodedVideoOutputAction
import io.warpnect.video.decoder.DecodedVideoSink
import io.warpnect.video.decoder.VideoDecoderCapabilities
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderControlResult
import io.warpnect.video.decoder.VideoDecoderController
import io.warpnect.video.decoder.VideoDecoderError
import io.warpnect.video.decoder.VideoDecoderInputResult
import io.warpnect.video.decoder.VideoDecoderInputSource
import io.warpnect.video.decoder.VideoDecoderPrepareResult
import io.warpnect.video.decoder.VideoDecoderSnapshot
import io.warpnect.video.decoder.VideoDecoderStartResult
import io.warpnect.video.decoder.VideoDecoderStopResult
import io.warpnect.video.render.VideoRenderControlResult
import io.warpnect.video.render.VideoRenderController
import io.warpnect.video.render.VideoRenderError
import io.warpnect.video.render.VideoRenderPolicy
import io.warpnect.video.render.VideoRenderSnapshot
import io.warpnect.video.render.VideoRenderTarget
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoReceiverRuntimeController
import io.warpnect.video.transport.VideoReceiverRuntimeEvent
import io.warpnect.video.transport.VideoReceiverRuntimeEventType
import io.warpnect.video.transport.VideoReceiverRuntimeListener
import io.warpnect.video.transport.VideoReceiverRuntimeResult
import io.warpnect.video.transport.VideoReceiverRuntimeSnapshot
import io.warpnect.video.transport.VideoReceiverStreamConfig
import io.warpnect.video.transport.VideoResyncReason
import io.warpnect.video.transport.VideoTransportError
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoReceiverLifecycleTest {
    @Test
    fun repeatedSurfaceCallbacksCannotPrepareTheDecoderConcurrently() = runTest {
        val target = target(1)
        val runtime = Runtime()
        val decoder = Decoder()
        val session = DefaultVideoReceiverSessionController(runtime, decoder, Renderer(target), scope = backgroundScope)
        session.start(config())
        runtime.listener.onStreamConfig(streamConfig())
        runCurrent()
        session.onRenderTargetChanged(target)
        runCurrent()
        val callsWhileBlocked = decoder.prepares
        decoder.ready.complete(Unit)
        runCurrent()

        assertEquals("Only one preparation owns the decoder", 1, callsWhileBlocked)
        assertEquals(1, decoder.prepares)
        assertEquals(1, decoder.starts)
        assertEquals(VideoSessionState.WaitingForKeyFrame, session.snapshot().state)
    }

    @Test
    fun disconnectDuringPreparationCannotRestartTheDecoder() = runTest {
        val runtime = Runtime()
        val decoder = Decoder()
        val session = DefaultVideoReceiverSessionController(
            runtime,
            decoder,
            Renderer(target(1)),
            scope = backgroundScope,
        )
        session.start(config())
        runtime.listener.onStreamConfig(streamConfig())
        runCurrent()
        val stopping = async { session.stop() }
        runCurrent()
        decoder.ready.complete(Unit)
        runCurrent()
        stopping.await()

        assertEquals(0, decoder.starts)
        assertEquals(VideoSessionState.Idle, session.snapshot().state)
    }

    @Test
    fun aNewStartWaitsUntilTheOldDecoderHasStopped() = runTest {
        val runtime = Runtime()
        val decoder = Decoder()
        val session = DefaultVideoReceiverSessionController(
            runtime,
            decoder,
            Renderer(target(1)),
            scope = backgroundScope,
        )
        session.start(config())
        runtime.listener.onStreamConfig(streamConfig())
        runCurrent()
        val stopping = async { session.stop() }
        runCurrent()

        assertEquals(VideoSessionError.AlreadyRunning, session.start(config()).error)
        decoder.ready.complete(Unit)
        runCurrent()
        stopping.await()
        assertEquals(VideoSessionState.Idle, session.snapshot().state)
    }

    @Test
    fun destructionOfAnOldSurfaceCannotInvalidateItsReplacement() = runTest {
        val first = target(1)
        val runtime = Runtime()
        val decoder = Decoder().apply { ready.complete(Unit) }
        val session = DefaultVideoReceiverSessionController(runtime, decoder, Renderer(first), scope = backgroundScope)
        session.start(config())
        runtime.listener.onStreamConfig(streamConfig())
        runCurrent()
        session.onRenderTargetAvailable(target(2))
        runCurrent()
        session.onRenderTargetDestroyed(first.surfaceGeneration)
        runCurrent()

        assertEquals(VideoSessionState.WaitingForKeyFrame, session.snapshot().state)
        assertEquals(2, decoder.starts)
    }

    @Test
    fun surfaceReplacementDuringPreparationStartsOnlyTheFreshTarget() = runTest {
        val runtime = Runtime()
        val decoder = Decoder()
        val first = target(1)
        val second = target(2)
        val session = DefaultVideoReceiverSessionController(runtime, decoder, Renderer(first), scope = backgroundScope)
        session.start(config())
        runtime.listener.onStreamConfig(streamConfig())
        runCurrent()
        session.onRenderTargetDestroyed(first.surfaceGeneration)
        session.onRenderTargetAvailable(second)
        runCurrent()
        decoder.ready.complete(Unit)
        runCurrent()

        assertEquals(2, decoder.prepares)
        assertEquals(1, decoder.starts)
        assertEquals(second.surface, decoder.lastSurface)
        assertEquals(VideoSessionState.WaitingForKeyFrame, session.snapshot().state)
    }

    private fun target(generation: Long) = VideoRenderTarget(Surface(SurfaceTexture(0)), generation, 640, 360)

    private fun streamConfig() = VideoReceiverStreamConfig(1, 640, 360, 1, listOf(byteArrayOf(1)))

    private fun config() = VideoReceiverSessionConfig(
        VideoReceiverRuntimeConfig(
            localPort = 0,
            maxWireDatagramSize = 1200,
            maxLogicalPayloadSize = 4096,
            reassemblySlotCount = 8,
            readySlotCount = 8,
            lossSlotCount = 32,
            maxNacksPerPump = 8,
            reorderDelayUs = 0,
            renackIntervalUs = 10_000,
            maxNackAttempts = 2,
            reassemblyTimeoutUs = 100_000,
        ),
    )

    private class Runtime : VideoReceiverRuntimeController {
        lateinit var listener: VideoReceiverRuntimeListener
        override val inputSource = object : VideoDecoderInputSource {
            override fun fillInput(target: ByteBuffer, capacity: Int) = VideoDecoderInputResult.NoData
        }
        override fun open(config: VideoReceiverRuntimeConfig) = result()
        override fun start(listener: VideoReceiverRuntimeListener): VideoReceiverRuntimeResult {
            this.listener = listener
            return result()
        }
        override fun pumpOnce(timeoutUs: Long) = VideoReceiverRuntimeEvent(VideoReceiverRuntimeEventType.Timeout)
        override fun activateConfigGeneration(generation: Long) = VideoTransportError.None
        override fun setAwaitingKeyFrame(awaiting: Boolean) = Unit
        override fun requestResync(reason: VideoResyncReason, generation: Long) = VideoTransportError.None
        override fun stop() = result()
        override fun snapshot() = VideoReceiverRuntimeSnapshot()
        override fun close() = Unit
        private fun result() = VideoReceiverRuntimeResult(VideoTransportError.None, snapshot())
    }

    private class Decoder : VideoDecoderController {
        val ready = CompletableDeferred<Unit>()
        var prepares = 0
        var starts = 0
        var lastSurface: Surface? = null
        override fun queryCapabilities(config: VideoDecoderConfig): VideoDecoderCapabilities = error("Not used")
        override suspend fun prepare(
            config: VideoDecoderConfig,
            outputSurface: Surface,
            inputSource: VideoDecoderInputSource,
            outputSink: DecodedVideoSink,
        ): VideoDecoderPrepareResult {
            prepares++
            lastSurface = outputSurface
            ready.await()
            return VideoDecoderPrepareResult(VideoDecoderError.None, null, snapshot())
        }
        override suspend fun start(): VideoDecoderStartResult {
            starts++
            return VideoDecoderStartResult(VideoDecoderError.None, snapshot())
        }
        override fun notifyInputAvailable() = Unit
        override suspend fun signalEndOfStream() = VideoDecoderControlResult(VideoDecoderError.None, snapshot())
        override suspend fun stop() = VideoDecoderStopResult(VideoDecoderError.None, snapshot())
        override fun snapshot() = VideoDecoderSnapshot()
        override fun close() = Unit
    }

    private class Renderer(private val target: VideoRenderTarget) : VideoRenderController {
        override val decodedVideoSink = object : DecodedVideoSink {
            override fun onFrameAvailable(frame: DecodedVideoFrame) = DecodedVideoOutputAction.RenderNow
        }
        override fun setVideoGeometry(width: Int, height: Int) = result()
        override fun setPreferredFrameRate(frameRateHz: Float?) = result()
        override fun setRenderPolicy(policy: VideoRenderPolicy) = result()
        override fun currentTarget() = target
        override fun snapshot() = VideoRenderSnapshot()
        override fun close() = Unit
        private fun result() = VideoRenderControlResult(VideoRenderError.None, snapshot())
    }
}
