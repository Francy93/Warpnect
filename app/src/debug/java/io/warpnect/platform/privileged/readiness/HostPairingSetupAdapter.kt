package io.warpnect.platform.privileged.readiness

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.warpnect.debug.directshell.DirectShellSelfBootstrapProbeActivity

/** Only the DEBUG source set supplies the existing user-controlled setup backend. */
internal object HostPairingSetupAdapter {
    fun createIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val intent = Intent(context, DirectShellSelfBootstrapProbeActivity::class.java)
        val settings = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        return try {
            val manager = context.packageManager
            intent.takeIf {
                it.resolveActivity(manager) != null && settings.resolveActivity(manager) != null
            }
        } catch (_: SecurityException) {
            null
        }
    }
}
