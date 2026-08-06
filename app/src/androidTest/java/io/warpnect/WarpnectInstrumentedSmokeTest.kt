package io.warpnect

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarpnectInstrumentedSmokeTest {
    @Test
    fun applicationPackageIsFinalized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertEquals("io.warpnect", context.packageName)
    }
}
