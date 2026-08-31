package io.warpnect.platform.session.integration

import android.os.Handler
import android.os.HandlerThread
import io.warpnect.session.integration.SessionControlDispatcher

/**
 * One application-scoped serialized owner for existing bounded Phase 5 control deadlines and
 * cold user-initiated Session control work. It does not poll Android networks or run media work;
 * transports and Android callbacks continue to deliver events directly to their existing owners.
 */
class AndroidSessionControlScheduler(
    private val onAdvance: () -> Unit,
) : SessionControlDispatcher, AutoCloseable {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var closed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            onAdvance()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        handler.post(tick)
    }

    override fun dispatch(action: () -> Unit): Boolean {
        if (closed) return false
        return handler.post {
            if (!closed) action()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(tick)
        thread.quitSafely()
    }

    private companion object {
        const val THREAD_NAME = "WarpnectSessionControl"
        const val TICK_MS = 100L
    }
}
