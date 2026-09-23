package io.warpnect.platform.discovery

/**
 * Serializes framework service resolutions while retaining bounded discovery breadth.
 *
 * Older Android NSD implementations allow only one active resolve operation per client. A
 * cancelled active operation therefore keeps its slot until its terminal callback arrives.
 */
internal class SerializedServiceResolutionQueue<T>(
    private val maxPending: Int,
) {
    init {
        require(maxPending > 0)
    }

    internal class Work<T> internal constructor(
        val key: String,
        val value: T,
    ) {
        internal var cancelled: Boolean = false
    }

    internal sealed interface EnqueueResult<out T> {
        data object Duplicate : EnqueueResult<Nothing>

        data object CapacityReached : EnqueueResult<Nothing>

        data object Queued : EnqueueResult<Nothing>

        data class Start<T>(val work: Work<T>) : EnqueueResult<T>
    }

    internal sealed interface Cancellation<out T> {
        data object NotFound : Cancellation<Nothing>

        data object Queued : Cancellation<Nothing>

        data class Active<T>(val work: Work<T>) : Cancellation<T>
    }

    private val queued = ArrayDeque<Work<T>>()
    private var active: Work<T>? = null

    fun enqueue(key: String, value: T): EnqueueResult<T> {
        if (active?.key == key || queued.any { it.key == key }) return EnqueueResult.Duplicate
        if (pendingCount >= maxPending) return EnqueueResult.CapacityReached

        queued.addLast(Work(key, value))
        return startNextIfIdle()
    }

    fun cancel(key: String): Cancellation<T> {
        val current = active
        if (current?.key == key) {
            current.cancelled = true
            return Cancellation.Active(current)
        }

        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key == key) {
                iterator.remove()
                return Cancellation.Queued
            }
        }
        return Cancellation.NotFound
    }

    /** Cancels queued work and retains any active framework operation until it terminates. */
    fun cancelAll(): Work<T>? {
        queued.clear()
        return active?.also { it.cancelled = true }
    }

    fun complete(work: Work<T>): Work<T>? {
        if (active !== work) return null
        active = null
        return startNextIfIdleOrNull()
    }

    fun isActive(work: Work<T>): Boolean = active === work

    val pendingCount: Int
        get() = queued.size + if (active == null) 0 else 1

    private fun startNextIfIdle(): EnqueueResult<T> {
        val next = startNextIfIdleOrNull()
        return if (next == null) EnqueueResult.Queued else EnqueueResult.Start(next)
    }

    private fun startNextIfIdleOrNull(): Work<T>? {
        if (active != null || queued.isEmpty()) return null
        return queued.removeFirst().also { active = it }
    }
}
