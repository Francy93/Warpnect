package io.warpnect.platform.session.integration

import android.os.SystemClock
import io.warpnect.session.lifecycle.SessionLifecycleMonotonicClock
import io.warpnect.session.setup.SessionSetupMonotonicClock

/** Shared Android BOOTTIME-domain clock for handoffs between RFC-005G setup and RFC-005H lifecycle. */
internal class AndroidSessionMonotonicClock(
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : SessionSetupMonotonicClock, SessionLifecycleMonotonicClock {
    override fun nowMs(): Long = elapsedRealtime()
}
