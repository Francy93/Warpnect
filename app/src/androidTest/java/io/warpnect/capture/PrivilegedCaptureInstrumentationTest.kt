package io.warpnect.capture

import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.capture.AndroidVideoCaptureController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivilegedCaptureInstrumentationTest {
    private var controller: AndroidVideoCaptureController? = null

    @After
    fun tearDown() {
        controller?.close()
    }

    @Test
    fun privilegedCaptureProducesFirstFrameWhenAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val captureController = AndroidVideoCaptureController(context)
        controller = captureController
        val capabilities = runBlocking { captureController.queryCapabilities() }
        println(
            "Warpnect privileged capture diagnostics: " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "api=${Build.VERSION.SDK_INT} " +
                "privilege=${capabilities.privilegeState} " +
                "backend=${capabilities.backend} " +
                "backendAvailable=${capabilities.backendAvailable} " +
                "display=${capabilities.supportedSourceDisplays.firstOrNull()} " +
                "lastError=${capabilities.lastError}",
        )
        assumeTrue(
            "Privileged capture unavailable: ${capabilities.privilegeState}/${capabilities.lastError}",
            capabilities.privilegeState == CapturePrivilegeState.Ready &&
                capabilities.backendAvailable,
        )

        val frameLatch = CountDownLatch(1)
        val firstFrameAtUs = AtomicLong(-1L)
        val texture = SurfaceTexture(0)
        texture.setDefaultBufferSize(320, 240)
        texture.setOnFrameAvailableListener(
            {
                firstFrameAtUs.compareAndSet(-1L, SystemClock.elapsedRealtimeNanos() / 1_000L)
                frameLatch.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        val surface = Surface(texture)

        try {
            val startRequestedAtUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            val start = runBlocking {
                captureController.start(
                    request = CaptureRequest(
                        sourceDisplayId = Display.DEFAULT_DISPLAY,
                        outputWidth = 320,
                        outputHeight = 240,
                    ),
                    target = surface,
                )
            }
            val runningAtUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            assertTrue("start failed: $start", start.isSuccess)
            assertTrue(
                "privileged capture started but no test-sink frame arrived",
                frameLatch.await(3, TimeUnit.SECONDS),
            )
            val observedFrameAtUs = firstFrameAtUs.get()
            println(
                "Warpnect privileged capture diagnostics: " +
                    "state=${captureController.snapshot().state} " +
                    "setupLatencyUs=${runningAtUs - startRequestedAtUs} " +
                    "firstFrameLatencyUs=${observedFrameAtUs - startRequestedAtUs} " +
                    "snapshot=${captureController.snapshot()}",
            )
            val stop = runBlocking { captureController.stop() }
            println("Warpnect privileged capture diagnostics: stop=$stop")
            assertTrue("stop failed: $stop", stop.isSuccess)
        } finally {
            surface.release()
            texture.release()
        }
    }
}
