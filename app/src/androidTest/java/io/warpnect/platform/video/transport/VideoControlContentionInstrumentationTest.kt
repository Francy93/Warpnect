package io.warpnect.platform.video.transport

import android.util.Log
import io.warpnect.video.encoder.VideoCodec
import io.warpnect.video.encoder.VideoEncoderOutputFormat
import io.warpnect.video.transport.VideoTransportConfig
import io.warpnect.video.transport.VideoTransportError
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual JNI/UDP contention only, not a codec or authenticated Session qualification. */
class VideoControlContentionInstrumentationTest {
    @Test
    fun idleControlWaitDoesNotStarveAccessUnitSubmission() = exercise(controlPolling = true)

    @Test
    fun submissionWithoutControlPollingMeetsTheSameCadence() = exercise(controlPolling = false)

    private fun exercise(controlPolling: Boolean) {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { destination ->
            val sender = NativeSclVideoTransportController()
            val failure = AtomicReference<Throwable?>()
            val entered = CountDownLatch(1)
            var control: Thread? = null
            try {
                assertEquals(
                    VideoTransportError.None,
                    sender.open(VideoTransportConfig("127.0.0.1", destination.localPort, 1200, 32)).error,
                )
                assertEquals(
                    VideoTransportError.None,
                    sender.submitStreamConfig(
                        VideoEncoderOutputFormat(
                            VideoCodec.Avc, "video/avc", 1280, 720, 60, null, null, null, null, null,
                            listOf(byteArrayOf(1, 2)),
                        ),
                    ),
                )
                val durationNs = TimeUnit.SECONDS.toNanos(2)
                val start = System.nanoTime()
                control = if (controlPolling) {
                    Thread {
                        try {
                            entered.countDown()
                            while (System.nanoTime() - start < durationNs) {
                                assertEquals(VideoTransportError.None, sender.pumpControl(20_000).error)
                            }
                        } catch (error: Throwable) {
                            failure.set(error)
                        }
                    }.apply { start() }
                } else {
                    null
                }
                if (controlPolling) assertTrue(entered.await(1, TimeUnit.SECONDS))
                Thread.sleep(2)
                val data = ByteBuffer.allocateDirect(16).apply {
                    put(ByteArray(16))
                    flip()
                }
                var submitted = 0
                var maxSubmitNs = 0L
                while (submitted < 120 && System.nanoTime() - start < durationNs) {
                    val begin = System.nanoTime()
                    assertEquals(
                        VideoTransportError.None,
                        sender.submitAccessUnit(data, 0, 16, begin / 1000, true),
                    )
                    maxSubmitNs = maxOf(maxSubmitNs, System.nanoTime() - begin)
                    submitted++
                    val remaining = start + submitted * 16_666_667L - System.nanoTime()
                    if (remaining > 0) LockSupport.parkNanos(remaining)
                }
                control?.join(1_000)
                assertTrue("Bounded control shutdown", control?.isAlive != true)
                failure.get()?.let { throw AssertionError("Control worker failed", it) }
                Log.i(
                    "WarpnectVideoContention",
                    "VIDEO_CONTROL_CONTENTION polling=$controlPolling submitted=$submitted max_submit_ns=$maxSubmitNs",
                )
                assertTrue("Control must not monopolize the sender: $submitted units", submitted >= 100)
                assertTrue("No repeated idle-poll starvation: $maxSubmitNs ns", maxSubmitNs < 100_000_000)
            } finally {
                control?.join(3_000)
                check(control?.isAlive != true) { "Do not release a live native sender" }
                sender.close()
            }
        }
    }
}
