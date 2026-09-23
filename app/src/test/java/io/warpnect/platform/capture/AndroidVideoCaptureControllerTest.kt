package io.warpnect.platform.capture

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Test

class AndroidVideoCaptureControllerTest {
    @Test
    fun displayReconfigurationNeverUsesTheUiDispatcher() {
        assertSame(Dispatchers.IO, displayReconfigurationDispatcher)
    }
}
