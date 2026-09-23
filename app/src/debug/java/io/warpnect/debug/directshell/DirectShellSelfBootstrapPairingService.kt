package io.warpnect.debug.directshell

import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Short-lived DEBUG foreground service for one user-approved Wireless Debugging pairing attempt.
 *
 * A foreground service keeps the mDNS discovery and TLS pairing alive while Android Settings stays
 * foregrounded; it exits after one bounded bootstrap attempt and retains no pairing code.
 */
class DirectShellSelfBootstrapPairingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairingCode = when (intent?.action) {
            DirectShellSelfBootstrapNotification.ACTION_SUBMIT_PAIRING_CODE ->
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(DirectShellSelfBootstrapNotification.EXTRA_PAIRING_CODE)
                    ?.toString()
                    ?.let(::normalizePairingCode)
                    ?: return invalidPairingCode(startId)
            DirectShellSelfBootstrapNotification.ACTION_RETRY_BOOTSTRAP -> null
            else -> {
                DirectShellSelfBootstrapNotification.showRetry(this, "Azione di pairing non valida.")
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        try {
            startForeground(
                DirectShellSelfBootstrapNotification.NOTIFICATION_ID,
                DirectShellSelfBootstrapNotification.foregroundNotification(
                    this,
                    "Pairing e bootstrap DirectShell in corso.",
                ),
            )
        } catch (error: IllegalStateException) {
            DirectShellSelfBootstrapNotification.showRetry(
                this,
                "FAIL_FOREGROUND_SERVICE_NOT_ALLOWED: ${error.javaClass.simpleName}",
            )
            Log.w(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=FOREGROUND_SERVICE result=DENIED", error)
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (error: SecurityException) {
            DirectShellSelfBootstrapNotification.showRetry(
                this,
                "FAIL_FOREGROUND_SERVICE_PERMISSION: ${error.javaClass.simpleName}",
            )
            Log.w(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=FOREGROUND_SERVICE result=DENIED", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Thread(
            {
                val controller = DirectShellSelfBootstrapController(this)
                try {
                    val result = pairingCode?.let(controller::pairAndBootstrapDiscovered)
                        ?: controller.bootstrapPreviouslyAuthorized()
                    val text = "${result.classification}: ${result.detail}"
                    if (result.successful) {
                        DirectShellSelfBootstrapNotification.showResult(this, text)
                    } else {
                        DirectShellSelfBootstrapNotification.showRetry(this, text)
                    }
                    Log.i(
                        TAG,
                        "DIRECT_SHELL_SELF_BOOTSTRAP result=${result.classification} detail=${sanitize(result.detail)}",
                    )
                } finally {
                    controller.close()
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                }
            },
            "WarpnectDirectShellPairing",
        ).start()
        return START_NOT_STICKY
    }

    private fun invalidPairingCode(startId: Int): Int {
        DirectShellSelfBootstrapNotification.showRetry(this, "Codice di pairing non valido.")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun normalizePairingCode(value: String): String? = runCatching {
        DirectShellSelfBootstrapContract.pairingCode(value)
    }.getOrNull()

    private fun sanitize(value: String): String = value
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(1_000)

    private companion object {
        const val TAG = "WarpnectDirectShellBootstrap"
    }
}
