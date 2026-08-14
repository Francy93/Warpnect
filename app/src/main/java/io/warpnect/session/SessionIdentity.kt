package io.warpnect.session

/**
 * Opaque, non-cryptographic 128-bit device identity.
 *
 * The all-zero value is reserved for unset/invalid identity and cannot be constructed through the
 * public factory. It is not a network endpoint, Android device ID, or trust assertion.
 */
@ConsistentCopyVisibility
data class DeviceId private constructor(
    val high: ULong,
    val low: ULong,
) {
    override fun toString(): String = "DeviceId(${high.fixedWidthHex()}${low.fixedWidthHex()})"

    companion object {
        fun fromParts(high: ULong, low: ULong): DeviceId? = if (high == 0uL && low == 0uL) null else DeviceId(high, low)

        fun requireValid(high: ULong, low: ULong): DeviceId =
            requireNotNull(fromParts(high, low)) { "DeviceId cannot use the reserved all-zero value" }
    }
}

/**
 * Opaque 128-bit logical session identity.
 *
 * A SessionId identifies one live session instance. It is distinct from DeviceId and is neither a
 * credential nor an authentication result.
 */
@ConsistentCopyVisibility
data class SessionId private constructor(
    val high: ULong,
    val low: ULong,
) {
    override fun toString(): String = "SessionId(${high.fixedWidthHex()}${low.fixedWidthHex()})"

    companion object {
        fun fromParts(high: ULong, low: ULong): SessionId? =
            if (high == 0uL && low == 0uL) null else SessionId(high, low)

        fun requireValid(high: ULong, low: ULong): SessionId =
            requireNotNull(fromParts(high, low)) { "SessionId cannot use the reserved all-zero value" }
    }
}

/** Local session-incarnation marker. It is unrelated to SCL packet sequence numbers. */
@ConsistentCopyVisibility
data class SessionGeneration private constructor(
    val value: UInt,
) {
    companion object {
        val Initial: SessionGeneration = SessionGeneration(1u)

        fun from(value: UInt): SessionGeneration? = if (value == 0u) null else SessionGeneration(value)

        fun requireValid(value: UInt): SessionGeneration =
            requireNotNull(from(value)) { "SessionGeneration cannot use zero" }
    }
}

/** Session-local logical channel identity. Zero is reserved. */
@ConsistentCopyVisibility
data class ChannelId private constructor(
    val value: UInt,
) {
    companion object {
        fun from(value: UInt): ChannelId? = if (value == 0u) null else ChannelId(value)

        fun requireValid(value: UInt): ChannelId = requireNotNull(from(value)) { "ChannelId cannot use zero" }
    }
}

/** Session-local path identity. Zero is reserved. */
@ConsistentCopyVisibility
data class PathId private constructor(
    val value: UInt,
) {
    companion object {
        fun from(value: UInt): PathId? = if (value == 0u) null else PathId(value)

        fun requireValid(value: UInt): PathId = requireNotNull(from(value)) { "PathId cannot use zero" }
    }
}

/** Stable local ordering for Host-owned Client sessions. It is never an Android player/device ID. */
@ConsistentCopyVisibility
data class ParticipantIndex private constructor(
    val value: Int,
) {
    companion object {
        fun from(value: Int): ParticipantIndex? = if (value in 0 until SessionBounds.HARD_MAX_CONCURRENT_CLIENTS) {
            ParticipantIndex(value)
        } else {
            null
        }

        fun requireValid(value: Int): ParticipantIndex = requireNotNull(from(value)) {
            "ParticipantIndex must be below ${SessionBounds.HARD_MAX_CONCURRENT_CLIENTS}"
        }
    }
}

/** Reference to a Warpnect device from the local device's point of view. */
data class PeerReference(
    val deviceId: DeviceId,
)

private fun ULong.fixedWidthHex(): String = toString(16).padStart(16, '0')
