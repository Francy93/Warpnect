package io.warpnect.video.session

import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.MainActivity
import io.warpnect.platform.video.decoder.AndroidMediaCodecVideoDecoder
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import io.warpnect.platform.video.transport.NativeSclVideoReceiverController
import io.warpnect.platform.video.transport.NativeSclVideoTransportController
import io.warpnect.video.encoder.SyntheticEglSurfaceProducer
import io.warpnect.video.encoder.VideoEncoderRequest
import io.warpnect.video.render.VideoRenderTarget
import io.warpnect.video.render.VideoRenderTargetListener
import io.warpnect.video.transport.SclEncodedVideoSink
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportFecConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEndToEndVideoStreamingInstrumentationTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var encoder: AndroidMediaCodecVideoEncoder? = null
    private var sender: NativeSclVideoTransportController? = null
    private var receiverRuntime: NativeSclVideoReceiverController? = null
    private var decoder: AndroidMediaCodecVideoDecoder? = null
    private var renderer: AndroidVideoRenderController? = null
    private var receiverSession: DefaultVideoReceiverSessionController? = null

    @After
    fun tearDown() {
        runBlocking {
            receiverSession?.stop()
        }
        encoder?.close()
        sender?.close()
        receiverSession?.close()
        receiverRuntime?.close()
        decoder?.close()
        renderer?.close()
        scenario?.close()
    }

    @Test
    fun syntheticEncoderToSclLoopbackDecoderSurfaceView() = runBlocking {
        val request = VideoEncoderRequest(
            width = 320,
            height = 240,
            frameRate = 30,
            bitrateBps = 1_000_000,
            iFrameIntervalSeconds = 1f,
        )
        val encoderController = AndroidMediaCodecVideoEncoder()
        encoder = encoderController
        val encoderCapabilities = encoderController.queryCapabilities(request)
        assumeTrue("No supported hardware AVC encoder: ${encoderCapabilities.error}", encoderCapabilities.isSupported)

        val targetLatch = CountDownLatch(1)
        val sessionRef = AtomicReference<DefaultVideoReceiverSessionController?>()
        val renderController = AndroidVideoRenderController(
            targetListener = object : VideoRenderTargetListener {
                override fun onRenderTargetAvailable(target: VideoRenderTarget) {
                    sessionRef.get()?.onRenderTargetAvailable(target)
                    targetLatch.countDown()
                }

                override fun onRenderTargetChanged(target: VideoRenderTarget) {
                    sessionRef.get()?.onRenderTargetChanged(target)
                }

                override fun onRenderTargetDestroyed(surfaceGeneration: Long) {
                    sessionRef.get()?.onRenderTargetDestroyed(surfaceGeneration)
                }
            },
        )
        renderer = renderController
        val receiverController = NativeSclVideoReceiverController(pumpTimeoutUs = 5_000)
        receiverRuntime = receiverController
        val decoderController = AndroidMediaCodecVideoDecoder()
        decoder = decoderController
        val session = DefaultVideoReceiverSessionController(
            receiverRuntimeController = receiverController,
            decoderController = decoderController,
            renderController = renderController,
        )
        receiverSession = session
        sessionRef.set(session)

        val localPort = 45_000 + (System.nanoTime() % 8_000L).toInt()
        val receiverStart = session.start(
            VideoReceiverSessionConfig(
                receiverRuntimeConfig = VideoReceiverRuntimeConfig(
                    localAddress = "127.0.0.1",
                    localPort = localPort,
                    remoteAddress = null,
                    remotePort = 0,
                    restrictRemoteEndpoint = false,
                    maxWireDatagramSize = 768,
                    maxLogicalPayloadSize = 16 * 1024,
                    reassemblySlotCount = 16,
                    readySlotCount = 16,
                    lossSlotCount = 128,
                    maxNacksPerPump = 16,
                    reorderDelayUs = 2_000,
                    renackIntervalUs = 10_000,
                    maxNackAttempts = 2,
                    fec = VideoTransportFecConfig.Disabled,
                    reassemblyTimeoutUs = 100_000,
                ),
            ),
        )
        assumeTrue("Loopback receiver unavailable: ${receiverStart.failure.transportError}", receiverStart.isSuccess)

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            val container = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    WarpnectVideoSurfaceView(activity).apply {
                        attachController(renderController)
                    },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            activity.setContentView(container)
        }
        assertTrue("Render SurfaceView target not available", targetLatch.await(5, TimeUnit.SECONDS))

        val senderController = NativeSclVideoTransportController()
        sender = senderController
        val senderOpen = senderController.open(
            VideoTransportConfig(
                remoteAddress = "127.0.0.1",
                remotePort = localPort,
                maxWireDatagramSize = 768,
                retransmissionCacheSlots = 128,
            ),
        )
        assertTrue("SCL sender open failed: ${senderOpen.error}", senderOpen.isSuccess)

        val prepare = encoderController.prepare(request, SclEncodedVideoSink(senderController))
        assertTrue("encoder prepare failed: $prepare", prepare.isSuccess)
        val inputSurface = requireNotNull(prepare.inputSurface)
        assertTrue("encoder start failed", encoderController.start().isSuccess)
        SyntheticEglSurfaceProducer(inputSurface, request.width, request.height).use { producer ->
            repeat(36) { frame ->
                producer.drawFrame(frame, frame * FRAME_INTERVAL_US)
            }
        }

        val rendered = waitUntil(timeoutMs = 10_000) {
            val snapshot = session.snapshot()
            if (snapshot.state == VideoSessionState.Error &&
                snapshot.lastError.source == VideoSessionErrorSource.Decoder
            ) {
                assumeTrue("Decoder unavailable: ${snapshot.lastError.decoderError}", false)
            }
            snapshot.renderer?.renderNowDecisions ?: 0L > 0L
        }
        assertTrue("No rendered decision observed: ${session.snapshot()}", rendered)
        assertTrue("encoder stop failed", encoderController.stop().isSuccess)
        assertTrue("receiver stop failed", session.stop().isSuccess)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return true
            }
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        const val FRAME_INTERVAL_US = 33_333L
    }
}
