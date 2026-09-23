package io.warpnect.debug.directshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build

/** DEBUG-only RemoteInput notification so pairing code entry does not leave Android's settings UI. */
internal object DirectShellSelfBootstrapNotification {
    const val ACTION_SUBMIT_PAIRING_CODE = "io.warpnect.debug.directshell.SUBMIT_PAIRING_CODE"
    const val ACTION_RETRY_BOOTSTRAP = "io.warpnect.debug.directshell.RETRY_BOOTSTRAP"
    const val EXTRA_PAIRING_CODE = "pairing_code"

    fun showWaiting(context: Context) {
        notify(context, waitingNotification(context))
    }

    fun showProgress(context: Context, text: String) {
        notify(context, foregroundNotification(context, text))
    }

    fun showResult(context: Context, text: String) {
        notify(
            context,
            createNotification(
                context,
                "Warpnect Wireless Debugging",
                text,
                ongoing = false,
                withRemoteInput = false,
                withRetry = false,
            ),
        )
    }

    fun showRetry(context: Context, text: String) {
        notify(
            context,
            createNotification(
                context,
                "Warpnect Wireless Debugging",
                text,
                ongoing = false,
                withRemoteInput = true,
                withRetry = true,
            ),
        )
    }

    fun foregroundNotification(context: Context, text: String): Notification = createNotification(
        context,
        "Warpnect Wireless Debugging",
        text,
        ongoing = true,
        withRemoteInput = false,
        withRetry = false,
    )

    private fun waitingNotification(context: Context): Notification = createNotification(
        context,
        "Warpnect Wireless Debugging",
        "Apri Pair device with pairing code nelle Opzioni sviluppatore, " +
            "poi inserisci qui il codice e tocca Invia codice.",
        ongoing = true,
        withRemoteInput = true,
        // A previously paired device can retry the bounded bootstrap without reopening pairing.
        withRetry = true,
    )

    private fun createNotification(
        context: Context,
        title: String,
        text: String,
        ongoing: Boolean,
        withRemoteInput: Boolean,
        withRetry: Boolean,
    ): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (withRetry) builder.addAction(retryBootstrapAction(context))
        if (withRemoteInput) builder.addAction(pairingAction(context))
        return builder.build()
    }

    private fun notify(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun pairingAction(context: Context): Notification.Action {
        val inputBuilder = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel("Codice di pairing a sei cifre")
            .setAllowFreeFormInput(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            inputBuilder.setEditChoicesBeforeSending(RemoteInput.EDIT_CHOICES_BEFORE_SENDING_DISABLED)
        }
        val input = inputBuilder.build()
        val intent = Intent(context, DirectShellSelfBootstrapPairingService::class.java)
            .setAction(ACTION_SUBMIT_PAIRING_CODE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        // The notification itself starts the short-lived FGS. A receiver which subsequently starts
        // a service loses the notification-originated FGS allowance on this Tablet framework.
        val pendingIntent = PendingIntent.getForegroundService(context, 0, intent, flags)
        return Notification.Action.Builder(null, "Invia codice", pendingIntent)
            .addRemoteInput(input)
            .build()
    }

    private fun retryBootstrapAction(context: Context): Notification.Action {
        val intent = Intent(context, DirectShellSelfBootstrapPairingService::class.java)
            .setAction(ACTION_RETRY_BOOTSTRAP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getForegroundService(context, 1, intent, flags)
        return Notification.Action.Builder(null, "Riprova avvio", pendingIntent).build()
    }

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Warpnect Wireless Debugging",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "DEBUG-only user-authorized DirectShell bootstrap probe"
            },
        )
    }

    private const val CHANNEL_ID = "warpnect_directshell_pairing_debug"
    const val NOTIFICATION_ID = 6_501
}
