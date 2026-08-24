package io.warpnect.telemetry

/**
 * Fixed, allocation-free open-addressing trace state for one component. It deliberately records
 * only incomplete work; callers reduce a completed trace into a histogram immediately.
 */
class LatencyCorrelationTable(
    capacity: Int = DEFAULT_CAPACITY,
    private val maxProbe: Int = MAX_PROBE,
    private val expiryNs: Long = DEFAULT_EXPIRY_NS,
) {
    private val capacity = capacity
    private val keys = LongArray(capacity)
    private val firstNs = LongArray(capacity)
    private val secondNs = LongArray(capacity)
    private val thirdNs = LongArray(capacity)
    private val touchedNs = LongArray(capacity)
    private val occupied = BooleanArray(capacity)
    private var expiredSinceLastRead = 0

    init {
        require(capacity in 1..DEFAULT_CAPACITY && capacity and (capacity - 1) == 0) {
            "Correlation capacity must be a power of two no larger than $DEFAULT_CAPACITY"
        }
        require(maxProbe in 1..capacity) { "Correlation probe bound is invalid" }
        require(expiryNs > 0L) { "Correlation expiry must be positive" }
        firstNs.fill(NOT_RECORDED)
        secondNs.fill(NOT_RECORDED)
        thirdNs.fill(NOT_RECORDED)
        touchedNs.fill(NOT_RECORDED)
    }

    fun start(key: Long, timestampNs: Long): LatencyCorrelationOutcome {
        if (timestampNs < 0L) return LatencyCorrelationOutcome.InvalidDuration
        val base = slotFor(key)
        for (offset in 0 until maxProbe) {
            val index = (base + offset) and (capacity - 1)
            expireIfNeeded(index, timestampNs)
            if (!occupied[index]) {
                occupied[index] = true
                keys[index] = key
                firstNs[index] = timestampNs
                secondNs[index] = NOT_RECORDED
                thirdNs[index] = NOT_RECORDED
                touchedNs[index] = timestampNs
                return LatencyCorrelationOutcome.Started
            }
            if (keys[index] == key) return LatencyCorrelationOutcome.CapacityRejected
        }
        return LatencyCorrelationOutcome.CapacityRejected
    }

    fun markSecond(key: Long, timestampNs: Long): LatencyCorrelationOutcome = markStage(key, timestampNs, 2)

    fun markThird(key: Long, timestampNs: Long): LatencyCorrelationOutcome = markStage(key, timestampNs, 3)

    fun first(key: Long): Long? = lookup(key, minimumStage = 1)

    fun second(key: Long): Long? = lookup(key, minimumStage = 2)

    fun third(key: Long): Long? = lookup(key, minimumStage = 3)

    fun thirdAt(key: Long, nowNs: Long): Long? {
        if (nowNs < 0L) return null
        val index = find(key, nowNs)
        return if (index < 0 || thirdNs[index] == NOT_RECORDED) null else thirdNs[index]
    }

    fun complete(key: Long, timestampNs: Long): LatencyCorrelationOutcome {
        if (timestampNs < 0L) return LatencyCorrelationOutcome.InvalidDuration
        val index = find(key, timestampNs)
        if (index < 0) return LatencyCorrelationOutcome.Unmatched
        if (thirdNs[index] == NOT_RECORDED) return LatencyCorrelationOutcome.Unmatched
        if (timestampNs < thirdNs[index]) {
            clear(index)
            return LatencyCorrelationOutcome.InvalidDuration
        }
        clear(index)
        return LatencyCorrelationOutcome.Completed
    }

    fun drainExpiredCount(): Int = expiredSinceLastRead.also { expiredSinceLastRead = 0 }

    fun clear() {
        occupied.fill(false)
        firstNs.fill(NOT_RECORDED)
        secondNs.fill(NOT_RECORDED)
        thirdNs.fill(NOT_RECORDED)
        touchedNs.fill(NOT_RECORDED)
        expiredSinceLastRead = 0
    }

    private fun markStage(key: Long, timestampNs: Long, stage: Int): LatencyCorrelationOutcome {
        if (timestampNs < 0L) return LatencyCorrelationOutcome.InvalidDuration
        val index = find(key, timestampNs)
        if (index < 0) return LatencyCorrelationOutcome.Unmatched
        val previous = if (stage == 2) firstNs[index] else secondNs[index]
        if (previous == NOT_RECORDED) return LatencyCorrelationOutcome.Unmatched
        if (timestampNs < previous) return LatencyCorrelationOutcome.InvalidDuration
        if (stage == 2) secondNs[index] = timestampNs else thirdNs[index] = timestampNs
        touchedNs[index] = timestampNs
        return LatencyCorrelationOutcome.Matched
    }

    private fun lookup(key: Long, minimumStage: Int): Long? {
        val index = find(key, Long.MAX_VALUE)
        if (index < 0) return null
        return when (minimumStage) {
            1 -> firstNs[index]
            2 -> secondNs[index].takeIf { it != NOT_RECORDED }
            else -> thirdNs[index].takeIf { it != NOT_RECORDED }
        }
    }

    private fun find(key: Long, nowNs: Long): Int {
        val base = slotFor(key)
        for (offset in 0 until maxProbe) {
            val index = (base + offset) and (capacity - 1)
            if (nowNs != Long.MAX_VALUE) expireIfNeeded(index, nowNs)
            if (!occupied[index]) return -1
            if (keys[index] == key) return index
        }
        return -1
    }

    private fun expireIfNeeded(index: Int, nowNs: Long) {
        if (occupied[index] && nowNs >= touchedNs[index] && nowNs - touchedNs[index] > expiryNs) {
            clear(index)
            expiredSinceLastRead += 1
        }
    }

    private fun clear(index: Int) {
        occupied[index] = false
        keys[index] = 0L
        firstNs[index] = NOT_RECORDED
        secondNs[index] = NOT_RECORDED
        thirdNs[index] = NOT_RECORDED
        touchedNs[index] = NOT_RECORDED
    }

    private fun slotFor(key: Long): Int {
        var value = key
        value = value xor (value ushr 33)
        value *= -49064778989728563L
        value = value xor (value ushr 33)
        value *= -4265267296055464877L
        value = value xor (value ushr 33)
        return value.toInt() and (capacity - 1)
    }

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val MAX_PROBE = 8
        const val DEFAULT_EXPIRY_NS = 2_000_000_000L
        private const val NOT_RECORDED = -1L
    }
}

enum class LatencyCorrelationOutcome {
    Started,
    Matched,
    Completed,
    Unmatched,
    CapacityRejected,
    InvalidDuration,
}
