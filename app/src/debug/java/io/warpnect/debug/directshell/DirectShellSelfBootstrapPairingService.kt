package io.warpnect.debug.directshell

import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Short-lived DEBUG foreground service for one user-approved Wireless Debugging pairing attempt.
 *
 * A foreground service keeps the mDNS discovery and TLS pairing alive while Android Settings stays
 * foregrounded; it exits after one bounded bootstrap attempt and retains no pairing code.
 */
class DirectShellSelfBootstrapPairingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var operation: DirectShellPairingAuthorization.Lease? = null
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val submit = intent?.action == DirectShellSelfBootstrapNotification.ACTION_SUBMIT_PAIRING_CODE
        val retry = intent?.action == DirectShellSelfBootstrapNotification.ACTION_RETRY_BOOTSTRAP
        if (intent == null || (!submit && !retry)) return reject(startId)
        val token = DirectShellSelfBootstrapNotification.authorizationToken(this, intent) ?: return reject(startId)
        val authorization = DirectShellPairingAuthorization.forApp(this)
        val lease = runCatching { authorization.claim(token) }.getOrNull() ?: return reject(startId)
        operation = lease
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=AUTHORIZATION result=CLAIMED")
        val pairingCode = if (submit) {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(DirectShellSelfBootstrapNotification.EXTRA_PAIRING_CODE)
                ?.toString()
                ?.let(::normalizePairingCode)
        } else {
            null
        }
        if (submit && pairingCode == null) {
            complete(authorization, lease, "FAIL_INVALID_PAIRING_CODE")
            return START_NOT_STICKY
        }
        try {
            startForeground(
                DirectShellSelfBootstrapNotification.NOTIFICATION_ID,
                DirectShellSelfBootstrapNotification.foregroundNotification(
                    this,
                    "Pairing e bootstrap DirectShell in corso.",
                ),
            )
        } catch (_: RuntimeException) {
            complete(authorization, lease, "FAIL_FOREGROUND_SERVICE_DENIED")
            return START_NOT_STICKY
        }
        try {
            Thread({
                var result = "FAIL_BOOTSTRAP_WORKER"
                try {
                    result = DirectShellSelfBootstrapController(this).use { controller ->
                        (pairingCode?.let(controller::pairAndBootstrapDiscovered)
                            ?: controller.bootstrapPreviouslyAuthorized()).classification
                    }
                } catch (_: Exception) {
                    // Do not emit provider exception text or pairing input from this boundary.
                } finally {
                    val classification = result
                    main.post { complete(authorization, lease, classification) }
                }
            }, "WarpnectDirectShellPairing").start()
        } catch (_: RuntimeException) {
            complete(authorization, lease, "FAIL_BOOTSTRAP_WORKER_START")
        }
        return START_NOT_STICKY
    }

    private fun reject(startId: Int): Int {
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=AUTHORIZATION result=REJECTED")
        if (operation == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun complete(
        authorization: DirectShellPairingAuthorization,
        lease: DirectShellPairingAuthorization.Lease,
        classification: String,
    ) {
        if (!authorization.finish(lease) || destroyed || operation !== lease) return
        operation = null
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP result=$classification")
        try {
            stopForeground(STOP_FOREGROUND_DETACH)
            DirectShellSelfBootstrapNotification.showResult(this, classification)
        } finally {
            // Rejected deliveries may have a newer startId, but none can own this service.
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    private fun normalizePairingCode(value: String): String? = runCatching {
        DirectShellSelfBootstrapContract.pairingCode(value)
    }.getOrNull()

    private companion object {
        const val TAG = "WarpnectDirectShellBootstrap"
    }
}
