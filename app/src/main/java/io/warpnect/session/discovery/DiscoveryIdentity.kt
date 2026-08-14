package io.warpnect.session.discovery

import java.security.SecureRandom

/**
 * Opaque 128-bit identity for one unauthenticated discoverability epoch.
 *
 * This is intentionally distinct from [io.warpnect.session.DeviceId]. It is random, non-secret,
 * non-persistent, and must never be treated as a trust or authentication assertion.
 */
@ConsistentCopyVisibility
data class DiscoveryPresenceId private constructor(
    val high: ULong,
    val low: ULong,
) {
    fun encodedValue(): String = high.fixedWidthHex() + low.fixedWidthHex()

    fun shortValue(): String = encodedValue().take(SHORT_VALUE_LENGTH)

    override fun toString(): String = "DiscoveryPresenceId(${encodedValue()})"

    companion object {
        const val ENCODED_LENGTH = 32
        const val SHORT_VALUE_LENGTH = 12

        fun fromParts(high: ULong, low: ULong): DiscoveryPresenceId? =
            if (high == 0uL && low == 0uL) null else DiscoveryPresenceId(high, low)

        fun requireValid(high: ULong, low: ULong): DiscoveryPresenceId = requireNotNull(fromParts(high, low)) {
            "DiscoveryPresenceId cannot use the reserved all-zero value"
        }

        internal fun fromEncodedValue(value: String): DiscoveryPresenceId? {
            if (value.length != ENCODED_LENGTH || value.any { !it.isHexDigit() }) return null
            val high = value.substring(0, 16).toULongOrNull(16) ?: return null
            val low = value.substring(16).toULongOrNull(16) ?: return null
            return fromParts(high, low)
        }
    }
}

/** Generates random discovery-only identifiers without making them credentials. */
fun interface DiscoveryPresenceIdGenerator {
    fun next(): DiscoveryPresenceId
}

class SecureRandomDiscoveryPresenceIdGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) : DiscoveryPresenceIdGenerator {
    override fun next(): DiscoveryPresenceId {
        while (true) {
            val presenceId = DiscoveryPresenceId.fromParts(
                secureRandom.nextLong().toULong(),
                secureRandom.nextLong().toULong(),
            )
            if (presenceId != null) return presenceId
        }
    }
}

/**
 * Opaque route handle valid only for one [LocalDiscoveryController] generation.
 *
 * It is deliberately not a RFC-005A PathId: discovery routes are only untrusted candidates.
 */
@ConsistentCopyVisibility
data class DiscoveryRouteToken internal constructor(
    internal val controllerGeneration: Long,
    internal val value: Int,
) {
    override fun toString(): String = "DiscoveryRouteToken($controllerGeneration:$value)"
}

/** Internal-only bridge between a platform backend and future path establishment. */
@JvmInline
value class DiscoveryOpaqueRouteLocator internal constructor(
    internal val value: String,
) {
    override fun toString(): String = "DiscoveryOpaqueRouteLocator"
}

/** Presentation metadata only. It is never identity and is not derived from the Android device name. */
@ConsistentCopyVisibility
data class DiscoveryDisplayAlias private constructor(
    val value: String,
) {
    companion object {
        const val MAX_UTF8_BYTES = 63
        val DEFAULT_HOST: DiscoveryDisplayAlias = requireValid("Warpnect Host")

        fun from(value: String): DiscoveryDisplayAlias? {
            val normalized = value.trim()
            if (normalized.isEmpty() || !normalized.isWellFormedUtf16()) return null
            if (normalized.any { it.code < 0x20 || it.code == 0x7f }) return null
            if (normalized.toByteArray(Charsets.UTF_8).size > MAX_UTF8_BYTES) return null
            return DiscoveryDisplayAlias(normalized)
        }

        fun requireValid(value: String): DiscoveryDisplayAlias =
            requireNotNull(from(value)) { "Discovery display alias is invalid" }
    }
}

private fun ULong.fixedWidthHex(): String = toString(16).padStart(16, '0')

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val value = this[index]
        when {
            value.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            value.isLowSurrogate() -> return false
            else -> index += 1
        }
    }
    return true
}
