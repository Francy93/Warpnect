package io.warpnect.video.render

import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.MainActivity
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.VideoRenderDebugObserver
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import io.warpnect.video.decoder.VideoDecoderFrameRenderedEvent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoRenderControllerInstrumentationTest {
    private var controller: AndroidVideoRenderController? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        controller?.close()
    }

    @Test
    fun decoderWorkerGeometryRequestsLayoutOnTheViewThread() {
        val recordRequests = AtomicBoolean(false)
        val requested = CountDownLatch(1)
        val wrongThread = AtomicBoolean(false)
        val workerError = AtomicReference<Throwable?>()
        val renderController = AndroidVideoRenderController()
        controller = renderController
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            val view = object : SurfaceView(activity) {
                override fun requestLayout() {
                    if (recordRequests.get()) {
                        if (Looper.myLooper() != Looper.getMainLooper()) wrongThread.set(true)
                        requested.countDown()
                    }
                    super.requestLayout()
                }
            }
            activity.setContentView(FrameLayout(activity).apply { addView(view) })
            renderController.attach(view)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        recordRequests.set(true)
        val worker = Thread {
            try {
                renderController.setVideoGeometry(640, 480)
            } catch (failure: Throwable) {
                workerError.set(failure)
            }
        }
        worker.start()
        worker.join(2_000)
        assertTrue("Geometry worker must finish", !worker.isAlive)
        assertEquals("No worker-thread View access", null, workerError.get())
        assertTrue("UI layout request must arrive", requested.await(3, TimeUnit.SECONDS))
        assertTrue("Layout must run on the owner thread", !wrongThread.get())
        assertEquals(640, renderController.snapshot().videoWidth)
    }

    @Test
    fun queuedGeometryDoesNotTouchADetachedView() {
        val queued = AtomicReference<Runnable?>()
        val capturePost = AtomicBoolean(false)
        val recordRequests = AtomicBoolean(false)
        val requested = AtomicBoolean(false)
        val renderController = AndroidVideoRenderController()
        controller = renderController
        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        lateinit var view: SurfaceView
        launched.onActivity { activity ->
            view = object : SurfaceView(activity) {
                override fun post(action: Runnable): Boolean = if (capturePost.get()) {
                    queued.set(action)
                    true
                } else {
                    super.post(action)
                }

                override fun requestLayout() {
                    if (recordRequests.get()) requested.set(true)
                    super.requestLayout()
                }
            }
            renderController.attach(view)
            capturePost.set(true)
            renderController.setVideoGeometry(640, 480)
            renderController.detach(view)
            recordRequests.set(true)
            assertNotNull("Layout action was dispatched", queued.get())
            queued.get()?.run()
            assertTrue("Detached owner must not receive queued layout", !requested.get())
        }
    }

    @Test
    fun surfaceViewPublishesAvailableAndDestroyedTargets() {
        val availableLatch = CountDownLatch(1)
        val destroyedLatch = CountDownLatch(1)
        val listener = object : VideoRenderTargetListener {
            override fun onRenderTargetAvailable(target: VideoRenderTarget) {
                assertTrue(target.surface.isValid)
                assertEquals(1L, target.surfaceGeneration)
                availableLatch.countDown()
            }

            override fun onRenderTargetDestroyed(surfaceGeneration: Long) {
                assertEquals(1L, surfaceGeneration)
                destroyedLatch.countDown()
            }
        }
        val renderController = AndroidVideoRenderController(targetListener = listener)
        controller = renderController

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            renderController.setVideoGeometry(320, 240)
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

        assertTrue("Surface was not created", availableLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(renderController.currentTarget())
        val frameRate = renderController.setPreferredFrameRate(30f)
        assertTrue("frame-rate preference rejected on api=${Build.VERSION.SDK_INT}", frameRate.isSuccess)

        launched.close()
        scenario = null
        assertTrue("Surface was not destroyed", destroyedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(VideoRenderState.SurfaceDestroyed, renderController.snapshot().state)
    }

    @Test
    fun attachesToAnAlreadyCreatedSurfaceView() {
        val availableLatch = CountDownLatch(1)
        val surfaceCreatedLatch = CountDownLatch(1)
        val renderController = AndroidVideoRenderController(
            targetListener = object : VideoRenderTargetListener {
                override fun onRenderTargetAvailable(target: VideoRenderTarget) {
                    availableLatch.countDown()
                }
            },
        )
        controller = renderController
        lateinit var renderSurface: WarpnectVideoSurfaceView

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            renderSurface = WarpnectVideoSurfaceView(activity).apply {
                holder.addCallback(
                    object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                            surfaceCreatedLatch.countDown()
                        }

                        override fun surfaceChanged(
                            holder: android.view.SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
                    },
                )
            }
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(
                        renderSurface,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
            )
        }

        assertTrue("Surface was not created before attachment", surfaceCreatedLatch.await(5, TimeUnit.SECONDS))
        launched.onActivity { renderSurface.attachController(renderController) }
        assertTrue("Existing SurfaceView target was not published", availableLatch.await(5, TimeUnit.SECONDS))
        assertNotNull(renderController.currentTarget())
    }

    @Test
    fun debugObserverCorrelatesRecreatedTargetWithItsReboundDecoderFrame() {
        val firstAvailable = CountDownLatch(1)
        val secondAvailable = CountDownLatch(1)
        val secondFrame = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val renderController = AndroidVideoRenderController(
            debugObserver = object : VideoRenderDebugObserver {
                override fun onRenderTargetAvailable(surfaceGeneration: Long) {
                    events += "available:$surfaceGeneration"
                    if (surfaceGeneration == 1L) firstAvailable.countDown()
                    if (surfaceGeneration == 2L) secondAvailable.countDown()
                }

                override fun onRenderTargetDestroyed(surfaceGeneration: Long) {
                    events += "destroyed:$surfaceGeneration"
                }

                override fun onDecoderPrepared(surfaceGeneration: Long) {
                    events += "prepared:$surfaceGeneration"
                }

                override fun onRemoteCodecRenderCallback(surfaceGeneration: Long) {
                    events += "codec-callback:$surfaceGeneration"
                    if (surfaceGeneration == 2L) secondFrame.countDown()
                }
            },
        )
        controller = renderController
        lateinit var container: FrameLayout
        lateinit var firstSurface: WarpnectVideoSurfaceView

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            container = FrameLayout(activity)
            firstSurface = WarpnectVideoSurfaceView(activity).apply { attachController(renderController) }
            container.addView(
                firstSurface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            activity.setContentView(container)
        }

        assertTrue("First target was not published", firstAvailable.await(5, TimeUnit.SECONDS))
        launched.onActivity {
            renderController.onDecoderPreparedForSurfaceGeneration(1L)
            renderController.decodedVideoSink.onFrameRendered(VideoDecoderFrameRenderedEvent(0L, 0L))
            renderController.detach(firstSurface)
            container.removeView(firstSurface)
            val secondSurface = WarpnectVideoSurfaceView(container.context).apply { attachController(renderController) }
            container.addView(
                secondSurface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        assertTrue("Recreated target was not published", secondAvailable.await(5, TimeUnit.SECONDS))
        launched.onActivity {
            renderController.onDecoderPreparedForSurfaceGeneration(2L)
            renderController.decodedVideoSink.onFrameRendered(VideoDecoderFrameRenderedEvent(1L, 1L))
        }
        assertTrue("Second-generation codec callback was not observed", secondFrame.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                "available:1",
                "prepared:1",
                "codec-callback:1",
                "destroyed:1",
                "available:2",
                "prepared:2",
                "codec-callback:2",
            ),
            events.toList(),
        )
    }
}
