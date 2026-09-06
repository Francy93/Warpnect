package io.warpnect.platform.session.integration

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.MainActivity
import io.warpnect.platform.video.render.AndroidVideoRenderController
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSessionUiResourcesInstrumentationTest {
    private var scenario: ActivityScenario<MainActivity>? = null
    private var renderer: AndroidVideoRenderController? = null
    private var rendererBinding: AutoCloseable? = null

    @After
    fun tearDown() {
        rendererBinding?.close()
        scenario?.close()
        renderer?.close()
    }

    @Test
    fun exposesAttachedRendererViewportGeometryToClientInput() {
        val resources = AndroidSessionUiResources()
        val controller = AndroidVideoRenderController()
        renderer = controller
        rendererBinding = requireNotNull(resources.bindClientVideoRenderer(controller))
        lateinit var surface: WarpnectVideoSurfaceView

        val launched = ActivityScenario.launch(MainActivity::class.java)
        scenario = launched
        launched.onActivity { activity ->
            surface = WarpnectVideoSurfaceView(activity)
            activity.setContentView(
                FrameLayout(activity).apply {
                    addView(surface, FrameLayout.LayoutParams(1080, 720))
                },
            )
            resources.attachClientRenderSurface(surface)
            controller.setVideoGeometry(1280, 720)
            surface.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            )
        }

        val geometry = resources.clientViewportGeometry()
        assertTrue(geometry.isUsable())
        assertEquals(1080, geometry.surfaceWidthPx)
        assertEquals(720, geometry.surfaceHeightPx)
        assertEquals(1280, geometry.videoWidthPx)
        assertEquals(720, geometry.videoHeightPx)

        launched.onActivity { resources.clearClientRenderSurface(surface) }
        assertFalse(resources.clientViewportGeometry().isUsable())
    }
}
