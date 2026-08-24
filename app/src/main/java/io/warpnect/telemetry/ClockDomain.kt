package io.warpnect.telemetry

/** Explicit local timestamp domains prevent accidental BOOTTIME/MONOTONIC subtraction. */
enum class ClockDomainId {
    AndroidMonotonic,
    AndroidBootTime,
    AndroidUptime,
    NativeSteady,
}

@JvmInline
value class MonotonicTimestampNs(
    val value: Long,
) {
    init {
        require(value >= 0L) { "Monotonic timestamps must be non-negative" }
    }
}

@JvmInline
value class MonotonicTimestampUs(
    val value: Long,
) {
    init {
        require(value >= 0L) { "Monotonic timestamps must be non-negative" }
    }
}

data class ClockDomainTimestampNs(
    val domain: ClockDomainId,
    val timestamp: MonotonicTimestampNs,
)

/** A subtraction is valid only when both observations have the exact same clock domain. */
fun ClockDomainTimestampNs.elapsedSince(start: ClockDomainTimestampNs): Long? =
    if (domain == start.domain && timestamp.value >= start.timestamp.value) {
        timestamp.value - start.timestamp.value
    } else {
        null
    }
