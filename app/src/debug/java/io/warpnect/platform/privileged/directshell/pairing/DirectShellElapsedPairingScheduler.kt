package io.warpnect.platform.privileged.directshell.pairing

/** Nonblocking, never inline. This queue is only a wake hint, not the deadline authority. */
internal interface DirectShellPairingTimerQueue {
    fun post(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** Elapsed-time deadlines over an uptime-based queue. P1 independently reevaluates on resume. */
internal class DirectShellElapsedPairingScheduler(
    private val clock: DirectShellPairingClock,
    private val queue: DirectShellPairingTimerQueue,
) : DirectShellPairingScheduler {
    override fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer =
        Entry(deadlineMs, action).also { queue.post(it, remaining(deadlineMs)) }

    private fun remaining(deadlineMs: Long): Long {
        val now = clock.elapsedRealtimeMs()
        return if (deadlineMs <= now) 0 else deadlineMs - now
    }

    private inner class Entry(val deadlineMs: Long, private var action: (() -> Unit)?) :
        Runnable, DirectShellPairingTimer {
        private val gate = Any()

        override fun run() {
            val delivery = synchronized(gate) {
                val current = action ?: return
                queue.remove(this)
                val left = remaining(deadlineMs)
                if (left > 0) {
                    queue.post(this, left)
                    return
                }
                action = null
                current
            }
            delivery()
        }

        override fun cancel() {
            synchronized(gate) {
                action = null
                queue.remove(this)
            }
        }
    }
}
