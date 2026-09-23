package io.warpnect.platform.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.warpnect.session.discovery.DiscoveryDisplayAlias

/**
 * Supplies the optional Host presentation alias from the device name chosen by its owner.
 *
 * This value is sent only as discovery presentation metadata. Presence IDs remain the sole
 * discovery identity, and no trust or routing decision may depend on this string.
 */
object AndroidDiscoveryHostAlias {
    fun from(context: Context): DiscoveryDisplayAlias = fromCandidates(
        deviceName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull(),
        model = Build.MODEL,
    )

    internal fun fromCandidates(deviceName: String?, model: String?): DiscoveryDisplayAlias =
        sequenceOf(deviceName, model)
            .filterNotNull()
            .mapNotNull { candidate -> DiscoveryDisplayAlias.from(candidate) }
            .firstOrNull()
            ?: DiscoveryDisplayAlias.DEFAULT_HOST
}
