package io.warpnect.platform.privileged.readiness

import android.content.Context
import android.content.Intent

/** No qualified Release setup backend exists. Never advertise an actionable entry. */
internal object HostPairingSetupAdapter {
    @Suppress("UNUSED_PARAMETER")
    fun createIntent(context: Context): Intent? = null
}
