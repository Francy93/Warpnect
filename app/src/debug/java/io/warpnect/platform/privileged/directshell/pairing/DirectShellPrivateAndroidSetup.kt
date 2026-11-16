package io.warpnect.platform.privileged.directshell.pairing

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import io.warpnect.R
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** DEBUG review component. No manifest registration or normal app entry point. */
internal class DirectShellPrivatePairingActivity : Activity() {
    private var setup: DirectShellPrivateAndroidSetup? = null
    private var screen: DirectShellPrivateSetupSession.Attachment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val active = DirectShellPrivateAndroidSetup.current ?: return finish()
        val attached = active.attach()
        setup = active
        screen = attached
        setContentView(
            Button(this).apply {
                setText(R.string.directshell_private_setup_action)
                setOnClickListener { active.begin(attached) }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        screen?.let { setup?.resume(it) }
    }

    override fun onPause() {
        screen?.let { setup?.pause(it) }
        super.onPause()
    }

    override fun onDestroy() {
        screen?.let { setup?.detach(it, finishing = isFinishing && !isChangingConfigurations) }
        screen = null
        setup = null
        super.onDestroy()
    }
}

/** One explicit in-process carrier. Rotation and Android process recreation cannot restore it. */
internal class DirectShellPrivatePairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DirectShellPrivateAndroidSetup.current?.receive(context, intent)
    }
}

/** Owns the presentation/carrier only; the injecting caller retains the existing app owner and drain contract. */
internal class DirectShellPrivateAndroidSetup private constructor(
    private val context: Context,
    private val owner: AndroidDirectShellPairingOwner,
    private val readinessGeneration: () -> Long?,
) : AutoCloseable {
    private class Notice(
        val action: DirectShellPrivateSetupSession.NotificationAction,
        val uri: Uri,
        val reply: PendingIntent,
        val dismiss: PendingIntent,
    )

    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val session = DirectShellPrivateSetupSession(owner)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notice: Notice? = null
    private var closed = false

    init {
        scope.launch {
            owner.state.collect { retireIfNotWaiting() }
        }
    }

    fun attach() = session.attach()

    fun resume(screen: DirectShellPrivateSetupSession.Attachment) {
        if (closed) return
        session.resume(screen)
        if (!access().allowed) notice?.let { abandon(it.action) }
        retireIfNotWaiting()
    }

    fun detach(screen: DirectShellPrivateSetupSession.Attachment, finishing: Boolean) {
        if (closed) return
        if (finishing) session.cancel(screen)
        session.detach(screen)
        retireIfNotWaiting()
    }

    fun pause(screen: DirectShellPrivateSetupSession.Attachment) {
        if (!closed) session.pause(screen)
    }

    val state get() = owner.state

    fun beginForeground(
        screen: DirectShellPrivateSetupSession.Attachment,
    ): DirectShellPrivateSetupSession.ForegroundAction? {
        val generation = readinessGeneration() ?: return null
        return session.beginForeground(screen, generation, deviceUnlocked())
    }

    fun ownsForeground(
        screen: DirectShellPrivateSetupSession.Attachment,
        action: DirectShellPrivateSetupSession.ForegroundAction,
    ) = deviceUnlocked() && state.value.originReadinessGeneration == readinessGeneration() &&
        session.ownsForeground(screen, action)

    fun dismissForeground(
        screen: DirectShellPrivateSetupSession.Attachment,
        action: DirectShellPrivateSetupSession.ForegroundAction,
    ) = session.dismissForeground(screen, action)

    fun submitForeground(
        screen: DirectShellPrivateSetupSession.Attachment,
        action: DirectShellPrivateSetupSession.ForegroundAction,
        code: CharArray,
    ) = session.receiveForeground(screen, action, deviceUnlocked(), code)

    fun begin(screen: DirectShellPrivateSetupSession.Attachment) {
        checkMainThread()
        if (closed || Build.VERSION.SDK_INT < 30 ||
            !manager.areNotificationsEnabled() || !hasNotificationPermission() || !deviceUnlocked()
        ) {
            return
        }
        retireIfNotWaiting()
        if (notice != null) return
        val generation = readinessGeneration() ?: return
        try {
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.directshell_private_setup_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        } catch (_: SecurityException) {
            return
        }
        val action = session.begin(screen, generation, access()) ?: return
        var reply: PendingIntent? = null
        var dismiss: PendingIntent? = null
        var published = false
        try {
            val uri = Uri.Builder().scheme("warpnect-setup").authority(context.packageName)
                .appendPath(UUID.randomUUID().toString()).build()
            val replyFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            reply = pending(REPLY, uri, replyFlag)
            dismiss = pending(DISMISS, uri, PendingIntent.FLAG_IMMUTABLE)
            notice = Notice(action, uri, reply, dismiss)
            val label = context.getString(R.string.directshell_private_setup_code)
            val icon = android.R.drawable.ic_dialog_info
            val builder = Notification.Action.Builder(Icon.createWithResource(context, icon), label, reply)
                .setAllowGeneratedReplies(false)
                .addRemoteInput(RemoteInput.Builder(CODE).setLabel(label).build())
            if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
            val response = builder.build()
            if (!access().allowed || !isWaiting()) return
            manager.notify(
                uri.toString(),
                NOTICE_ID,
                Notification.Builder(context, CHANNEL)
                    .setContentTitle(context.getString(R.string.directshell_private_setup_channel))
                    .setSmallIcon(icon)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setDeleteIntent(dismiss)
                    .setLocalOnly(true)
                    .addAction(response)
                    .build(),
            )
            published = true
        } catch (_: SecurityException) {
            // Permission or channel state can change between admission and posting.
        } finally {
            if (!published) {
                reply?.cancel()
                dismiss?.cancel()
                abandon(action)
            }
        }
    }

    fun receive(receiver: Context, intent: Intent) {
        checkMainThread()
        val current = notice ?: return
        if (receiver.packageName != context.packageName || intent.data != current.uri ||
            intent.component?.className != DirectShellPrivatePairingReplyReceiver::class.java.name
        ) {
            return
        }
        when (intent.action) {
            DISMISS -> abandon(current.action)
            REPLY -> {
                val allowed = access()
                if (!allowed.allowed) return abandon(current.action)
                val value = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)
                if (value == null || value.length != 6 || value.any { it !in '0'..'9' }) return abandon(current.action)
                val code = CharArray(6) { value[it] }
                try {
                    retire()
                    session.receive(current.action, access(), code)
                } finally {
                    code.fill('\u0000')
                }
            }
        }
    }

    override fun close() {
        checkMainThread()
        if (closed) return
        closed = true
        current = null
        scope.cancel()
        try {
            session.close()
        } finally {
            retire()
        }
    }

    private fun pending(action: String, uri: Uri, flags: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DirectShellPrivatePairingReplyReceiver::class.java)
            .setAction(action)
            .setData(uri)
            .setPackage(context.packageName),
        PendingIntent.FLAG_ONE_SHOT or flags,
    )

    private fun hasNotificationPermission() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun deviceUnlocked() = keyguard?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true

    private fun access() = DirectShellPrivateSetupSession.NotificationAccess(
        Build.VERSION.SDK_INT,
        manager.areNotificationsEnabled(),
        manager.getNotificationChannel(CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true,
        hasNotificationPermission(),
        deviceUnlocked(),
    )

    private fun isWaiting() = owner.state.value.phase == DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE

    private fun retireIfNotWaiting() {
        if (!isWaiting()) retire()
    }

    private fun abandon(action: DirectShellPrivateSetupSession.NotificationAction) {
        try {
            session.dismiss(action)
        } finally {
            if (notice?.action === action) retire()
        }
    }

    private fun retire() {
        val previous = notice ?: return
        notice = null
        previous.reply.cancel()
        previous.dismiss.cancel()
        manager.cancel(previous.uri.toString(), NOTICE_ID)
    }

    companion object {
        private const val CHANNEL = "directshell_private_pairing"
        private const val NOTICE_ID = 1
        private const val CODE = "io.warpnect.directshell.private.PAIR_CODE"
        private const val REPLY = "io.warpnect.directshell.private.REPLY"
        private const val DISMISS = "io.warpnect.directshell.private.DISMISS"
        private var installed = false
        var current: DirectShellPrivateAndroidSetup? = null
            private set

        /** One owner for this process lifetime; a closed owner is not replaced while cleanup could be pending. */
        fun install(
            context: Context,
            owner: AndroidDirectShellPairingOwner,
            readinessGeneration: () -> Long?,
        ): DirectShellPrivateAndroidSetup {
            checkMainThread()
            check(!installed) { "pairing_setup_owner_already_installed" }
            return DirectShellPrivateAndroidSetup(context.applicationContext, owner, readinessGeneration).also {
                installed = true
                current = it
            }
        }

        private fun checkMainThread() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "pairing_setup_main_thread" }
        }
    }
}
