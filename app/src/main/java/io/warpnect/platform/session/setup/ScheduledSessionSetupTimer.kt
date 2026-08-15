package io.warpnect.platform.session.setup

import io.warpnect.session.setup.SessionSetupTimer
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One-shot timer adapter intended for the shared Phase 5 serialized control scheduler. */
class ScheduledSessionSetupTimer(
    private val scheduler: ScheduledExecutorService,
) : SessionSetupTimer {
    override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
        val future = scheduler.schedule(task, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
