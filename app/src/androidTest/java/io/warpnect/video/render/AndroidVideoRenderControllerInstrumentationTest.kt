package io.warpnect.video.render

import android.graphics.Color
import android.os.Build
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.MainActivity
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
