package io.warpnect.video.decoder

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.video.decoder.AndroidMediaCodecVideoDecoder
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.platform.video.encoder.safeCodecProbeDiscovery
import io.warpnect.video.encoder.EncodedVideoSink
import io.warpnect.video.encoder.SyntheticEglSurfaceProducer
import io.warpnect.video.encoder.VideoEncoderError
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.encoder.VideoEncoderRequest
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaCodecVideoDecoderInstrumentationTest {
    private var encoder: AndroidMediaCodecVideoEncoder? = null
    private var decoder: AndroidMediaCodecVideoDecoder? = null
    private var outputSurface: TestOutputSurface? = null

    @After
    fun tearDown() {
        decoder?.close()
        encoder?.close()
        outputSurface?.close()
    }

    @Test
    fun syntheticEncoderOutputDecodesToSurfaceWhenHardwareDecoderAvailable() {
        val request = testEncoderRequest()
        val encoded = produceEncodedAvc(request)
        assumeTrue("Encoder did not provide CSD", encoded.format.codecSpecificData.isNotEmpty())
        assumeTrue("Encoder did not provide AUs", encoded.accessUnits.isNotEmpty())

        val config = VideoDecoderConfig(
            width = request.width,
            height = request.height,
            configGeneration = 1,
            codecSpecificData = encoded.format.codecSpecificData.map { it.copyOf() },
            maxInputSizeBytes = encoded.accessUnits.maxOf { it.bytes.size } + 1024,
        )
        val decoderController = AndroidMediaCodecVideoDecoder()
        decoder = decoderController
        val capabilities = decoderController.queryCapabilities(config)
        printDecoderDiagnostics(capabilities)
        assumeTrue("No supported hardware AVC decoder: ${capabilities.error}", capabilities.isSupported)

        val surface = TestOutputSurface(request.width, request.height)
        outputSurface = surface
        val sink = CountingDecodedSink()
        val prepare = runBlocking {
            decoderController.prepare(
                config = config,
                outputSurface = surface.surface,
                inputSource = ListInputSource(encoded.accessUnits, config.configGeneration),
                outputSink = sink,
            )
        }
        assertTrue("decoder prepare failed: $prepare", prepare.isSuccess)
        assertTrue(runBlocking { decoderController.start() }.isSuccess)

        assertTrue("decoded output buffer missing", sink.outputLatch.await(5, TimeUnit.SECONDS))
        assertTrue("Surface frame missing", surface.frameLatch.await(5, TimeUnit.SECONDS))
        assertTrue("decoder stop failed", runBlocking { decoderController.stop() }.isSuccess)
        assertTrue("expected decoded frames", sink.decodedFrames.get() > 0)
        println(
            "Warpnect decoder diagnostics: encoder=${encoded.encoderCodec} " +
                "decoder=${capabilities.selectedCodec} decoded=${sink.decodedFrames.get()} " +
                "renderedCallbacks=${sink.renderedCallbacks.get()} snapshot=${decoderController.snapshot()}",
        )
    }

    private fun produceEncodedAvc(request: VideoEncoderRequest): EncodedAvcFixture {
        val encoderController = AndroidMediaCodecVideoEncoder(
            safeCodecProbeDiscovery(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        encoder = encoderController
        val capabilities = runBlocking { encoderController.queryCapabilities(request) }
        assumeTrue("No supported hardware AVC encoder: ${capabilities.error}", capabilities.isSupported)
        val sink = CollectingEncodedSink()
        val prepare = runBlocking { encoderController.prepare(request, sink) }
        assertTrue("encoder prepare failed: $prepare", prepare.isSuccess)
        val inputSurface = requireNotNull(prepare.inputSurface)
        assertTrue(runBlocking { encoderController.start() }.isSuccess)

        SyntheticEglSurfaceProducer(inputSurface, request.width, request.height).use { producer ->
            repeat(24) { frame ->
                producer.drawFrame(frame, frame * FRAME_INTERVAL_US)
            }
        }

        assertTrue("encoder output format missing", sink.formatLatch.await(3, TimeUnit.SECONDS))
        assertTrue("encoder AU missing", sink.accessUnitLatch.await(3, TimeUnit.SECONDS))
        assertTrue("encoder stop failed", runBlocking { encoderController.stop() }.isSuccess)
        return EncodedAvcFixture(
            encoderCodec = capabilities.selectedCodec?.codecName,
            format = requireNotNull(sink.format.get()),
            accessUnits = sink.accessUnits.toList(),
        )
    }

    private fun testEncoderRequest(): VideoEncoderRequest = VideoEncoderRequest(
        width = 320,
        height = 240,
        frameRate = 30,
        bitrateBps = 1_000_000,
        iFrameIntervalSeconds = 1f,
    )

    private fun printDecoderDiagnostics(capabilities: VideoDecoderCapabilities) {
        val support = capabilities.support
        println(
            "Warpnect decoder diagnostics: device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "api=${Build.VERSION.SDK_INT} codec=${capabilities.selectedCodec} " +
                "size=${support?.minWidth}..${support?.maxWidth}x${support?.minHeight}..${support?.maxHeight} " +
                "lowLatency=${support?.lowLatencyFeatureSupported} error=${capabilities.error}",
        )
    }

    private data class EncodedAvcFixture(
        val encoderCodec: String?,
        val format: VideoEncoderOutputFormat,
        val accessUnits: List<StoredAccessUnit>,
    )

    private data class StoredAccessUnit(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    private class CollectingEncodedSink : EncodedVideoSink {
        val formatLatch = CountDownLatch(1)
        val accessUnitLatch = CountDownLatch(1)
        val format = AtomicReference<VideoEncoderOutputFormat?>()
        val accessUnits = mutableListOf<StoredAccessUnit>()

        override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
            this.format.set(format)
            formatLatch.countDown()
        }

        override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            val duplicate = buffer.duplicate()
            duplicate.position(offset)
            duplicate.limit(offset + size)
            val bytes = ByteArray(size)
            duplicate.get(bytes)
            accessUnits += StoredAccessUnit(bytes, presentationTimeUs, flags)
            accessUnitLatch.countDown()
        }

        override fun onEncoderError(error: VideoEncoderError) = Unit
    }

    private class ListInputSource(
        private val accessUnits: List<StoredAccessUnit>,
        private val configGeneration: Long,
    ) : VideoDecoderInputSource {
        private var nextIndex = 0

        override fun fillInput(target: ByteBuffer, capacity: Int): VideoDecoderInputResult {
            if (nextIndex >= accessUnits.size) {
                return VideoDecoderInputResult.EndOfStream
            }
            val unit = accessUnits[nextIndex++]
            if (unit.bytes.size > capacity) {
                return VideoDecoderInputResult.AccessUnit(
                    size = unit.bytes.size,
                    presentationTimeUs = unit.presentationTimeUs,
                    configGeneration = configGeneration,
                    frameId = nextIndex.toLong(),
                    isKeyFrame = (unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                )
            }
            target.put(unit.bytes)
            return VideoDecoderInputResult.AccessUnit(
                size = unit.bytes.size,
                presentationTimeUs = unit.presentationTimeUs,
                configGeneration = configGeneration,
                frameId = nextIndex.toLong(),
                isKeyFrame = (unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
            )
        }
    }

    private class CountingDecodedSink : DecodedVideoSink {
        val outputLatch = CountDownLatch(1)
        val decodedFrames = AtomicInteger(0)
        val renderedCallbacks = AtomicInteger(0)

        override fun onFrameAvailable(frame: DecodedVideoFrame): DecodedVideoOutputAction {
            decodedFrames.incrementAndGet()
            outputLatch.countDown()
            return DecodedVideoOutputAction.RenderNow
        }

        override fun onFrameRendered(event: VideoDecoderFrameRenderedEvent) {
            renderedCallbacks.incrementAndGet()
        }
    }

    private class TestOutputSurface(
        width: Int,
        height: Int,
    ) : AutoCloseable {
        private val thread = HandlerThread("WarpnectDecoderSurfaceTest").apply { start() }
        private val texture = SurfaceTexture(0).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener(
                {
                    frameLatch.countDown()
                },
                Handler(thread.looper),
            )
        }
        val frameLatch = CountDownLatch(1)
        val surface = Surface(texture)

        override fun close() {
            surface.release()
            texture.release()
            thread.quitSafely()
        }
    }

    private companion object {
        const val FRAME_INTERVAL_US = 33_333L
    }
}
