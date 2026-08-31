package io.warpnect.platform.session.integration

import io.warpnect.session.lifecycle.SessionLifecycleMonotonicClock
import io.warpnect.session.setup.SessionSetupMonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidSessionMonotonicClockTest {
    @Test
    fun setupAndLifecycleUseAndroidElapsedRealtimeDomain() {
        val clock = AndroidSessionMonotonicClock { 7_500L }
        val setupClock: SessionSetupMonotonicClock = clock
        val lifecycleClock: SessionLifecycleMonotonicClock = clock

        assertEquals(7_500L, setupClock.nowMs())
        assertEquals(7_500L, lifecycleClock.nowMs())
    }
}
