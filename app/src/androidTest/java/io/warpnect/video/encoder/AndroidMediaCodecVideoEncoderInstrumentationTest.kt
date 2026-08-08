package io.warpnect.video.encoder

import android.media.MediaCodec
import android.os.Build
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.capture.CapturePrivilegeState
import io.warpnect.capture.CaptureRequest
import io.warpnect.platform.capture.AndroidVideoCaptureController
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMediaCodecVideoEncoderInstrumentationTest {
    private var encoder: AndroidMediaCodecVideoEncoder? = null
    private var capture: AndroidVideoCaptureController? = null

    @After
    fun tearDown() {
        capture?.close()
        encoder?.close()
    }

    @Test
    fun syntheticSurfaceFramesProduceHardwareAvcAccessUnits() {
        val request = testRequest()
        val encoderController = AndroidMediaCodecVideoEncoder()
        encoder = encoderController
        val capabilities = runBlocking { encoderController.queryCapabilities(request) }
        printEncoderDiagnostics("synthetic-query", capabilities)
        assumeTrue("No supported hardware AVC encoder: ${capabilities.error}", capabilities.isSupported)

        val sink = CountingSink()
        val prepare = runBlocking { encoderController.prepare(request, sink) }
        assertTrue("prepare failed: $prepare", prepare.isSuccess)
        val inputSurface = requireNotNull(prepare.inputSurface)
        val start = runBlocking { encoderController.start() }
        assertTrue("start failed: $start", start.isSuccess)

        SyntheticEglSurfaceProducer(inputSurface, request.width, request.height).use { producer ->
            repeat(30) { frame ->
                if (frame == 10) {
                    assertTrue(runBlocking { encoderController.requestKeyFrame() }.isSuccess)
                }
                if (frame == 15) {
                    assertTrue(runBlocking { encoderController.updateBitrate(1_500_000) }.isSuccess)
                }
                producer.drawFrame(frame, frame * FRAME_INTERVAL_US)
            }
        }

        assertTrue("output format not observed", sink.formatLatch.await(3, TimeUnit.SECONDS))
        assertTrue("encoded access units not observed", sink.accessUnitLatch.await(3, TimeUnit.SECONDS))
        val stop = runBlocking { encoderController.stop() }
        assertTrue("stop failed: $stop", stop.isSuccess)
        assertTrue("expected encoded bytes", sink.encodedBytes.get() > 0)
        assertTrue("expected at least one keyframe", sink.keyFrames.get() >= 1)
        assertTrue("expected monotonic PTS", sink.ptsRegression.get() == 0)
        println(
            "Warpnect encoder diagnostics: state=${encoderController.snapshot()} " +
                "format=${sink.latestFormat}",
        )
    }

    @Test
    fun privilegedCaptureCanFeedEncoderWhenAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captureController = AndroidVideoCaptureController(context)
        capture = captureController
        val captureCapabilities = runBlocking { captureController.queryCapabilities() }
        assumeTrue(
            "Privileged capture unavailable: ${captureCapabilities.privilegeState}/${captureCapabilities.lastError}",
            captureCapabilities.privilegeState == CapturePrivilegeState.Ready &&
                captureCapabilities.backendAvailable,
        )

        val request = testRequest()
        val encoderController = AndroidMediaCodecVideoEncoder()
        encoder = encoderController
        val encoderCapabilities = runBlocking { encoderController.queryCapabilities(request) }
        printEncoderDiagnostics("capture-query", encoderCapabilities)
        assumeTrue("No supported hardware AVC encoder: ${encoderCapabilities.error}", encoderCapabilities.isSupported)

        val sink = CountingSink()
        val prepare = runBlocking { encoderController.prepare(request, sink) }
        assertTrue("prepare failed: $prepare", prepare.isSuccess)
        val inputSurface = requireNotNull(prepare.inputSurface)
        assertTrue(runBlocking { encoderController.start() }.isSuccess)
        val startCapture = runBlocking {
            captureController.start(
                CaptureRequest(
                    sourceDisplayId = Display.DEFAULT_DISPLAY,
                    outputWidth = request.width,
                    outputHeight = request.height,
                ),
                inputSurface,
            )
        }
        assertTrue("capture start failed: $startCapture", startCapture.isSuccess)

        assertTrue("encoded access unit missing", sink.accessUnitLatch.await(5, TimeUnit.SECONDS))
        assertTrue(runBlocking { captureController.stop() }.isSuccess)
        assertTrue(runBlocking { encoderController.stop() }.isSuccess)
    }

    private fun testRequest(): VideoEncoderRequest = VideoEncoderRequest(
        width = 320,
        height = 240,
        frameRate = 30,
        bitrateBps = 1_000_000,
        iFrameIntervalSeconds = 1f,
    )

    private fun printEncoderDiagnostics(label: String, capabilities: VideoEncoderCapabilities) {
        val support = capabilities.support
        println(
            "Warpnect encoder diagnostics: label=$label " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} api=${Build.VERSION.SDK_INT} " +
                "codec=${capabilities.selectedCodec} " +
                "size=${support?.minWidth}..${support?.maxWidth}x${support?.minHeight}..${support?.maxHeight} " +
                "bitrate=${support?.minBitrateBps}..${support?.maxBitrateBps} " +
                "cbr=${support?.bitrateModeSupported} error=${capabilities.error}",
        )
    }

    private class CountingSink : EncodedVideoSink {
        val formatLatch = CountDownLatch(1)
        val accessUnitLatch = CountDownLatch(1)
        val encodedBytes = AtomicLong(0)
        val keyFrames = AtomicInteger(0)
        val ptsRegression = AtomicInteger(0)
        var latestFormat: VideoEncoderOutputFormat? = null
        private var lastPts: Long? = null

        override fun onOutputFormatChanged(format: VideoEncoderOutputFormat) {
            latestFormat = format
            formatLatch.countDown()
        }

        override fun onAccessUnit(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            assertTrue(offset >= 0)
            assertTrue(size > 0)
            assertTrue(buffer.capacity() >= offset + size)
            val previous = lastPts
            if (previous != null && presentationTimeUs < previous) {
                ptsRegression.incrementAndGet()
            }
            lastPts = presentationTimeUs
            encodedBytes.addAndGet(size.toLong())
            if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                keyFrames.incrementAndGet()
            }
            accessUnitLatch.countDown()
        }

        override fun onEncoderError(error: VideoEncoderError) {
            if (error == VideoEncoderError.UnexpectedOutputReordering) {
                ptsRegression.incrementAndGet()
            }
        }
    }

    private companion object {
        const val FRAME_INTERVAL_US = 33_333L
    }
}
