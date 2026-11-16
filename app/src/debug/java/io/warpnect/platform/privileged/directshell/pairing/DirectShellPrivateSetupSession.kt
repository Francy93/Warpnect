package io.warpnect.platform.privileged.directshell.pairing

/** Unregistered process-local UI state. Android adapters must dispatch events on the owner UI thread. */
internal class DirectShellPrivateSetupSession(private val owner: AndroidDirectShellPairingOwner) : AutoCloseable {
    class Attachment internal constructor()
    class NotificationAction internal constructor()
    class ForegroundAction internal constructor()

    data class NotificationAccess(
        val sdk: Int,
        val appEnabled: Boolean,
        val channelEnabled: Boolean,
        val postNotificationsGranted: Boolean,
        val deviceUnlocked: Boolean,
    ) {
        val allowed: Boolean
            get() = sdk >= 30 && appEnabled && channelEnabled && deviceUnlocked &&
                (sdk < 33 || postNotificationsGranted)
    }

    private class Attempt(
        val ticket: DirectShellPairingTicket,
        var action: NotificationAction?,
        var code: AndroidDirectShellPairingOwner.CodeDelivery?,
        var foreground: ForegroundAction? = null,
    )

    private val uiThread = Thread.currentThread()
    private var activity: Attachment? = null
    private var foregroundScreen: Attachment? = null
    private var attempt: Attempt? = null
    private var closed = false

    /** Attach or rotate. Neither action issues an enrollment ticket or restores a code. */
    fun attach(): Attachment {
        requireUiThread()
        check(!closed)
        foregroundScreen?.let(::pause)
        return Attachment().also { activity = it }
    }

    fun detach(attachment: Attachment) {
        requireUiThread()
        pause(attachment)
        if (activity === attachment) activity = null
    }

    fun resume(attachment: Attachment) {
        requireUiThread()
        if (activity !== attachment || closed) return
        foregroundScreen = attachment
        owner.onResume()
        clearIfDrained()
    }

    /** Only an unsubmitted foreground entry is retired on visibility loss; existing work still owns drain. */
    fun pause(attachment: Attachment) {
        requireUiThread()
        if (foregroundScreen !== attachment) return
        foregroundScreen = null
        if (attempt?.foreground != null) cancelCurrent()
    }

    /** Invoke only from an explicit current UI action, never from Activity/permission return. */
    fun begin(attachment: Attachment, generation: Long, access: NotificationAccess): NotificationAction? {
        requireUiThread()
        if (closed || activity !== attachment || !access.allowed) return null
        val current = issue(attachment, generation) ?: return null
        return NotificationAction().also { current.action = it }
    }

    /** Explicit alternative for an already resumed private screen, not a permission or intent callback. */
    fun beginForeground(attachment: Attachment, generation: Long, unlocked: Boolean): ForegroundAction? {
        requireUiThread()
        if (closed || foregroundScreen !== attachment || !unlocked) return null
        val current = issue(attachment, generation) ?: return null
        if (foregroundScreen !== attachment) {
            cancelCurrent()
            return null
        }
        return ForegroundAction().also { current.foreground = it }
    }

    fun ownsForeground(attachment: Attachment, action: ForegroundAction): Boolean {
        requireUiThread()
        clearIfDrained()
        return !closed && foregroundScreen === attachment && attempt?.foreground === action
    }

    fun dismissForeground(attachment: Attachment, action: ForegroundAction) {
        requireUiThread()
        if (!closed && activity === attachment && attempt?.foreground === action) cancelCurrent()
    }

    fun receiveForeground(
        attachment: Attachment,
        action: ForegroundAction,
        unlocked: Boolean,
        input: CharArray,
    ): Boolean = try {
        requireUiThread()
        val current = attempt
        if (closed || current == null || current.foreground !== action || foregroundScreen !== attachment) {
            false
        } else if (!unlocked) {
            cancelCurrent()
            false
        } else {
            owner.onResume()
            clearIfDrained()
            if (closed || attempt !== current || current.foreground !== action || foregroundScreen !== attachment) {
                false
            } else {
                current.foreground = null
                deliver(current, input)
            }
        }
    } finally {
        input.fill('\u0000')
    }

    fun receive(action: NotificationAction, access: NotificationAccess, input: CharArray): Boolean = try {
        requireUiThread()
        val current = attempt
        if (closed || current == null || current.action !== action) {
            false
        } else if (!access.allowed) {
            cancelCurrent()
            false
        } else {
            owner.onResume()
            clearIfDrained()
            if (closed || attempt !== current || current.action !== action) {
                false
            } else {
                current.action = null
                deliver(current, input)
            }
        }
    } finally {
        input.fill('\u0000')
    }

    /** A late callback from the old screen is not allowed to cancel a later screen's work. */
    fun cancel(attachment: Attachment) {
        requireUiThread()
        if (activity === attachment && !closed) cancelCurrent()
    }

    /** Notification dismissal can arrive during rotation, without a current screen. */
    fun dismiss(action: NotificationAction) {
        requireUiThread()
        if (!closed && attempt?.action === action) cancelCurrent()
    }

    override fun close() {
        requireUiThread()
        if (closed) return
        closed = true
        activity = null
        foregroundScreen = null
        cancelCurrent()
    }

    private fun issue(attachment: Attachment, generation: Long): Attempt? {
        clearIfDrained()
        if (attempt != null) return null
        val ticket = owner.request(generation) ?: return null
        if (closed || activity !== attachment) {
            owner.cancel(ticket)
            return null
        }
        return Attempt(ticket, null, owner.bindCodeDelivery(ticket)).also { attempt = it }
    }

    private fun deliver(current: Attempt, input: CharArray): Boolean {
        val carrier = current.code
        current.code = null
        val accepted = carrier?.deliver(input) == true
        if (!accepted && attempt === current) cancelCurrent()
        return accepted
    }

    private fun cancelCurrent() {
        val current = attempt ?: return
        current.code?.invalidate()
        current.code = null
        current.action = null
        current.foreground = null
        owner.cancel(current.ticket)
        clearIfDrained()
    }

    private fun clearIfDrained() {
        val state = owner.state.value
        if (state.drained && state.phase != DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE) {
            attempt?.code?.invalidate()
            attempt?.action = null
            attempt?.foreground = null
            attempt = null
        }
    }

    private fun requireUiThread() {
        check(Thread.currentThread() === uiThread) { "pairing_setup_thread" }
    }
}
