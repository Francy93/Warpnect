package io.warpnect.session.discovery

import io.warpnect.session.SessionRole

enum class DiscoveryAdvertisementError {
    InvalidTxtEncoding,
    MissingSchemaVersion,
    UnsupportedSchemaVersion,
    MissingPresenceId,
    InvalidPresenceId,
    MissingRole,
    InvalidRole,
    MissingAvailability,
    InvalidAvailability,
    InvalidPort,
    TxtTooLarge,
}

sealed interface DiscoveryAdvertisementDecodeResult {
    data class Decoded(
        val advertisement: DiscoveryAdvertisement,
    ) : DiscoveryAdvertisementDecodeResult

    data class Rejected(
        val error: DiscoveryAdvertisementError,
    ) : DiscoveryAdvertisementDecodeResult
}

/** Strict, small DNS-SD TXT codec for untrusted Discovery Presence Schema Version 1 metadata. */
object DiscoveryAdvertisementCodec {
    const val KEY_SCHEMA_VERSION = "dv"
    const val KEY_PRESENCE_ID = "pid"
    const val KEY_ROLE = "role"
    const val KEY_AVAILABILITY = "av"
    const val KEY_ALIAS = "name"
    const val KEY_BOOTSTRAP_PORT = "port"

    fun encode(advertisement: DiscoveryAdvertisement, includeBootstrapPort: Boolean): Map<String, String> {
        require(advertisement.schemaVersion == DiscoveryPresenceSchema.VERSION) {
            "Only Discovery Presence Schema Version ${DiscoveryPresenceSchema.VERSION} can be advertised"
        }
        if (includeBootstrapPort) {
            requireValidPort(
                requireNotNull(advertisement.bootstrapPort) {
                    "A direct DNS-SD advertisement requires a bootstrap contact port"
                },
            )
        }

        val values = linkedMapOf(
            KEY_SCHEMA_VERSION to advertisement.schemaVersion.toString(),
            KEY_PRESENCE_ID to advertisement.presenceId.encodedValue(),
            KEY_ROLE to advertisement.offeredRole.wireValue(),
            KEY_AVAILABILITY to advertisement.availability.advertisedValue,
        )
        advertisement.displayAlias?.let { values[KEY_ALIAS] = it.value }
        if (includeBootstrapPort) {
            values[KEY_BOOTSTRAP_PORT] = requireNotNull(advertisement.bootstrapPort).toString()
        }
        require(txtWireSizeBytes(values) <= DiscoveryPresenceSchema.TXT_MAX_BYTES) {
            "Discovery TXT metadata exceeds ${DiscoveryPresenceSchema.TXT_MAX_BYTES} bytes"
        }
        return values
    }

    fun decode(values: Map<String, String>): DiscoveryAdvertisementDecodeResult {
        if (txtWireSizeBytes(values) > DiscoveryPresenceSchema.TXT_MAX_BYTES) {
            return DiscoveryAdvertisementDecodeResult.Rejected(DiscoveryAdvertisementError.TxtTooLarge)
        }
        val schemaValue = values[KEY_SCHEMA_VERSION]
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.MissingSchemaVersion,
            )
        if (!schemaValue.isStrictDecimal()) {
            return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.UnsupportedSchemaVersion,
            )
        }
        val schemaVersion = schemaValue.toIntOrNull()
        if (schemaVersion != DiscoveryPresenceSchema.VERSION) {
            return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.UnsupportedSchemaVersion,
            )
        }

        val presenceValue = values[KEY_PRESENCE_ID]
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.MissingPresenceId,
            )
        val presenceId = DiscoveryPresenceId.fromEncodedValue(presenceValue)
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.InvalidPresenceId,
            )

        val roleValue = values[KEY_ROLE]
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(DiscoveryAdvertisementError.MissingRole)
        val role = sessionRoleFromWireValue(roleValue)
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(DiscoveryAdvertisementError.InvalidRole)

        val availabilityValue = values[KEY_AVAILABILITY]
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.MissingAvailability,
            )
        val availability = DiscoveryAvailability.fromAdvertisedValue(availabilityValue)
            ?: return DiscoveryAdvertisementDecodeResult.Rejected(
                DiscoveryAdvertisementError.InvalidAvailability,
            )

        val port = values[KEY_BOOTSTRAP_PORT]?.let { portValue ->
            if (!portValue.isStrictDecimal()) {
                return DiscoveryAdvertisementDecodeResult.Rejected(DiscoveryAdvertisementError.InvalidPort)
            }
            portValue.toIntOrNull()?.takeIf(::isValidPort)
                ?: return DiscoveryAdvertisementDecodeResult.Rejected(DiscoveryAdvertisementError.InvalidPort)
        }

        // An invalid optional alias is discarded; required schema fields remain independently valid.
        val alias = values[KEY_ALIAS]?.let(DiscoveryDisplayAlias::from)
        return DiscoveryAdvertisementDecodeResult.Decoded(
            DiscoveryAdvertisement(
                schemaVersion = schemaVersion,
                presenceId = presenceId,
                offeredRole = role,
                availability = availability,
                displayAlias = alias,
                bootstrapPort = port,
            ),
        )
    }

    fun txtWireSizeBytes(values: Map<String, String>): Int = values.entries.sumOf { (key, value) ->
        1 + key.toByteArray(Charsets.UTF_8).size + 1 + value.toByteArray(Charsets.UTF_8).size
    }

    fun serviceInstanceName(presenceId: DiscoveryPresenceId): String = "Warpnect-${presenceId.shortValue()}"
}

private fun SessionRole.wireValue(): String = when (this) {
    SessionRole.Host -> "h"
    SessionRole.Client -> "c"
}

private fun sessionRoleFromWireValue(value: String): SessionRole? = when (value) {
    "h" -> SessionRole.Host
    "c" -> SessionRole.Client
    else -> null
}

private fun String.isStrictDecimal(): Boolean = isNotEmpty() && all { it in '0'..'9' }

private fun requireValidPort(port: Int) {
    require(isValidPort(port)) { "Discovery bootstrap port must be within 1..65535" }
}

internal fun isValidPort(port: Int): Boolean = port in 1..65_535
