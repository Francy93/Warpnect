package io.warpnect.platform.session.integration

import android.os.Handler
import android.os.HandlerThread
import io.warpnect.session.integration.SecureSessionApplicationController

/**
 * One application-scoped timer source for existing bounded Phase 5 control deadlines. It does
 * not read sockets, poll Android networks, or run media work; transports and Android callbacks
 * continue to deliver events directly to their existing owners.
 */
class AndroidSessionControlScheduler(
    private val controller: SecureSessionApplicationController,
) : AutoCloseable {
    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var closed = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            controller.advance()
            handler.postDelayed(this, TICK_MS)
        }
    }

    init {
        handler.post(tick)
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
