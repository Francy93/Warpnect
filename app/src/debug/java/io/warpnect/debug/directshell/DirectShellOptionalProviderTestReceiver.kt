package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.warpnect.platform.privileged.planning.DebugDirectShellCapabilitySuppression
import io.warpnect.platform.privileged.planning.PrivilegedCapability

/**
 * DEBUG-only one-shot control for the composite-provider hardware test.
 *
 * It changes DirectShell eligibility for the next Host admission only. It never supplies a
 * substitute capability and is absent from release packaging.
 */
class DirectShellOptionalProviderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SUPPRESS_INPUT -> {
                val armed = DebugDirectShellCapabilitySuppression.arm(PrivilegedCapability.INPUT)
                Log.i(TAG, "DIRECT_SHELL_OPTIONAL_PROVIDER_TEST mode=suppress_input armed=$armed")
            }

            MODE_CLEAR -> {
                DebugDirectShellCapabilitySuppression.clear()
                Log.i(TAG, "DIRECT_SHELL_OPTIONAL_PROVIDER_TEST mode=clear")
            }

            else -> Log.w(TAG, "DIRECT_SHELL_OPTIONAL_PROVIDER_TEST mode=invalid")
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_SUPPRESS_INPUT = "SUPPRESS_INPUT"
        const val MODE_CLEAR = "CLEAR"
        private const val TAG = "WarpnectPrivilegedPlan"
    }
}
