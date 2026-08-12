package io.warpnect.platform.input.mapping

import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTargetDisplayGeometryProviderInstrumentationTest {
    @Test
    fun primaryDisplayReportsLogicalGeometryAndRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val displayManager = context.getSystemService(DisplayManager::class.java)
        assertNotNull(displayManager)
        val display = requireNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
        val provider = AndroidTargetDisplayGeometryProvider(context)
        try {
            val geometry = requireNotNull(provider.geometryFor(display.displayId))
            assertTrue(geometry.isUsable())
            assertEquals(display.displayId, geometry.displayId)
            assertTrue(geometry.logicalWidthPx > 0)
            assertTrue(geometry.logicalHeightPx > 0)
        } finally {
            provider.close()
        }
    }
}
