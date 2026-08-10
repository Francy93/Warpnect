package io.warpnect.video.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.platform.video.transport.NativeSclVideoReceiverController
import io.warpnect.platform.video.transport.NativeSclVideoTransportController
import io.warpnect.video.decoder.VideoDecoderInputResult
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.transport.SclEncodedVideoSink
import io.warpnect.video.transport.VideoReceiverAccessUnitReady
import io.warpnect.video.transport.VideoReceiverRuntimeConfig
import io.warpnect.video.transport.VideoReceiverRuntimeListener
import io.warpnect.video.transport.VideoReceiverStreamConfig
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportError
import io.warpnect.video.transport.VideoTransportFecConfig
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoStreamingLoopbackInstrumentationTest {
    private var receiver: NativeSclVideoReceiverController? = null
    private var sender: NativeSclVideoTransportController? = null

    @After
    fun tearDown() {
        sender?.close()
        receiver?.close()
    }

    @Test
    fun sclSenderReceiverFillsDirectDecoderInputBuffer() {
        val localPort = 43_000 + (System.nanoTime() % 10_000L).toInt()
        val receiverController = NativeSclVideoReceiverController(pumpTimeoutUs = 5_000)
        receiver = receiverController
        val configLatch = CountDownLatch(1)
        val accessUnitLatch = CountDownLatch(1)
        val latestConfig = AtomicReference<VideoReceiverStreamConfig?>()
        val latestAccessUnit = AtomicReference<VideoReceiverAccessUnitReady?>()

        val openReceiver = receiverController.open(
            VideoReceiverRuntimeConfig(
                localAddress = "127.0.0.1",
                localPort = localPort,
                remoteAddress = null,
                remotePort = 0,
                restrictRemoteEndpoint = false,
                maxWireDatagramSize = 512,
                maxLogicalPayloadSize = 4096,
                reassemblySlotCount = 8,
                readySlotCount = 8,
                lossSlotCount = 64,
                maxNacksPerPump = 8,
                reorderDelayUs = 1_000,
                renackIntervalUs = 5_000,
                maxNackAttempts = 2,
                fec = VideoTransportFecConfig.Disabled,
                reassemblyTimeoutUs = 50_000,
            ),
        )
        assumeTrue("Loopback receiver UDP port unavailable: ${openReceiver.error}", openReceiver.isSuccess)

        receiverController.start(
            object : VideoReceiverRuntimeListener {
                override fun onStreamConfig(config: VideoReceiverStreamConfig) {
                    latestConfig.set(config)
                    assertEquals(
                        VideoTransportError.None,
                        receiverController.activateConfigGeneration(config.configGeneration),
                    )
                    receiverController.setAwaitingKeyFrame(true)
                    configLatch.countDown()
                }

                override fun onAccessUnitReady(accessUnit: VideoReceiverAccessUnitReady) {
                    latestAccessUnit.set(accessUnit)
                    accessUnitLatch.countDown()
                }
            },
        )

        val senderController = NativeSclVideoTransportController()
        sender = senderController
        val openSender = senderController.open(
            VideoTransportConfig(
                remoteAddress = "127.0.0.1",
                remotePort = localPort,
                maxWireDatagramSize = 512,
                retransmissionCacheSlots = 32,
            ),
        )
        assertTrue("SCL sender open failed: ${openSender.error}", openSender.isSuccess)
        val sink = SclEncodedVideoSink(senderController)
        sink.onOutputFormatChanged(testFormat())

        val expected = ByteArray(96) { index -> ((index * 13 + 7) and 0xFF).toByte() }
        val encoded = ByteBuffer.allocateDirect(expected.size)
        encoded.put(expected)
        sink.onAccessUnit(
            buffer = encoded,
            offset = 0,
            size = expected.size,
            presentationTimeUs = 123_456L,
            flags = android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME,
        )

        assertTrue("StreamConfig was not received", configLatch.await(5, TimeUnit.SECONDS))
        assertTrue("AccessUnit was not received", accessUnitLatch.await(5, TimeUnit.SECONDS))
        assertEquals(123_456L, latestAccessUnit.get()?.presentationTimeUs)

        val decoderInput = ByteBuffer.allocateDirect(256)
        val fill = receiverController.inputSource.fillInput(decoderInput, decoderInput.capacity())
        assertTrue("Expected direct decoder fill AU but got $fill", fill is VideoDecoderInputResult.AccessUnit)
        val accessUnit = fill as VideoDecoderInputResult.AccessUnit
        assertEquals(expected.size, accessUnit.size)
        assertEquals(123_456L, accessUnit.presentationTimeUs)
        assertTrue(accessUnit.isKeyFrame)
        val actual = ByteArray(accessUnit.size)
        decoderInput.position(0)
        decoderInput.get(actual)
        assertArrayEquals(expected, actual)
    }

    private fun testFormat(): VideoEncoderOutputFormat = VideoEncoderOutputFormat(
        codec = VideoCodec.Avc,
        mimeType = VideoCodec.Avc.mimeType,
        width = 320,
        height = 240,
        frameRate = 30,
        bitrateBps = 1_000_000,
        profile = null,
        level = null,
        outputReorderDepth = 0,
        reportedLatencyFrames = 0,
        codecSpecificData = listOf(
            byteArrayOf(0x67, 0x42, 0x00),
            byteArrayOf(0x68.toByte(), 0xCE.toByte()),
        ),
    )
}
