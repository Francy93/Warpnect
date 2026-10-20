package io.warpnect.video.decoder

import android.os.Handler
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.platform.video.decoder.AndroidMediaCodecVideoDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real decoder Handler queue; no codec, Wi-Fi or partial Host is fabricated. */
@RunWith(AndroidJUnit4::class)
class VideoDecoderNotificationInstrumentationTest {
    @Test
    fun repeatedReadySignalsCannotBuildAnUnboundedCodecHandlerBacklog() {
        val decoder = AndroidMediaCodecVideoDecoder()
        // This is Warpnect's own field, not a hidden Android framework API.
        val handlerField = AndroidMediaCodecVideoDecoder::class.java.getDeclaredField("codecHandler").apply {
            isAccessible = true
        }
        val handler = handlerField.get(decoder) as Handler
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val marker = CountDownLatch(1)
        val dispatches = AtomicInteger()
        handler.looper.setMessageLogging { message ->
            if (message.startsWith(">>>>>")) dispatches.incrementAndGet()
        }
        try {
            assertTrue(
                handler.post {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                },
            )
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val before = dispatches.get()
            repeat(10_000) { decoder.notifyInputAvailable() }
            assertTrue(handler.post { marker.countDown() })
            release.countDown()
            assertTrue("Bounded notification drain", marker.await(5, TimeUnit.SECONDS))
            val drained = dispatches.get() - before
            Log.i("WarpnectVideoContention", "VIDEO_DECODER_WAKEUPS signals=10000 dispatches=$drained")
            assertTrue("Only one pending wakeup plus the marker, actual=$drained", drained <= 2)
            val nextMarker = CountDownLatch(1)
            val beforeNext = dispatches.get()
            decoder.notifyInputAvailable()
            assertTrue(handler.post { nextMarker.countDown() })
            assertTrue(nextMarker.await(2, TimeUnit.SECONDS))
            assertEquals("Drained notifications must rearm for new work", 2, dispatches.get() - beforeNext)
        } finally {
            release.countDown()
            handler.looper.setMessageLogging(null)
            decoder.close()
        }
    }
}
