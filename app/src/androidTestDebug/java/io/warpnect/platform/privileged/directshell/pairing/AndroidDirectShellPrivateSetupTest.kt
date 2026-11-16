package io.warpnect.platform.privileged.directshell.pairing

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailability
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeAvailabilityState
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeFailureReason
import io.warpnect.platform.privileged.readiness.PrivilegedRuntimeStateSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-owned UI and platform delivery only. No storage capability, transport or peer. */
class AndroidDirectShellPrivateSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as Application
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
    private val attempts = AtomicInteger()
    private val readiness = AtomicLong(1)
    private val advance = AtomicLong()

    @Test
    fun foregroundSyntheticEntryLifecycle() = withOwner { owner, current ->
        val before = launchCode()
        await { current.get() === before && ready(before) }
        assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
        main {
            assertTrue(before.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals("\u25cb".repeat(6), mask(before))
            press(before, "arm-code")
            press(before, "digit-4")
            assertEquals("\u25cf" + "\u25cb".repeat(5), mask(before))
        }
        rotate(before)
        await { current.get() !== before && current.get()?.let(::ready) == true }
        val fresh = checkNotNull(current.get())
        assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
        main {
            assertEquals("\u25cb".repeat(6), mask(fresh))
            assertFalse(fresh.findViewById<View>(android.R.id.content).findViewWithTag<View>("submit-code").isEnabled)
            press(fresh, "arm-code")
            press(fresh, "digit-5")
            assertTrue(fresh.moveTaskToBack(true))
        }
        await { owner.state.value.phase == DirectShellPairingPhase.CANCELLED }
        main { assertEquals("\u25cb".repeat(6), mask(fresh)) }
        main { context.startActivity(codeIntent()) }
        await { current.get()?.let(::ready) == true }
        val returning = checkNotNull(current.get())
        assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
        main {
            press(returning, "arm-code")
            press(returning, "digit-6")
            press(returning, "cancel-code")
            assertEquals("\u25cb".repeat(6), mask(returning))
            press(returning, "arm-code")
            press(returning, "digit-7")
            advance.addAndGet(DirectShellPairingCoordinator.CONSENT_MS)
            owner.onResume()
        }
        await {
            owner.state.value.phase == DirectShellPairingPhase.EXPIRED &&
                !enabled(returning, "cancel-code")
        }
        main {
            assertEquals("\u25cb".repeat(6), mask(returning))
            press(returning, "arm-code")
            fillSynthetic(returning)
            readiness.incrementAndGet()
            press(returning, "submit-code")
            assertEquals("\u25cb".repeat(6), mask(returning))
        }
        assertEquals(0, attempts.get())
        assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
        main {
            press(returning, "arm-code")
            fillSynthetic(returning)
            assertEquals("\u25cf".repeat(6), mask(returning))
            press(returning, "submit-code")
            assertFalse(submitView(returning).isEnabled)
            // Exercise the listener as well as the disabled view state.
            submitView(returning).performClick()
            assertEquals("\u25cb".repeat(6), mask(returning))
        }
        await { attempts.get() == 1 && owner.state.value.drained }
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(owner.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)
        marker("P3_FOREGROUND lifecycle=verified one_dispatch=true secure=true sdk=${Build.VERSION.SDK_INT}")
    }

    @Test
    fun terminateFixtureDuringForegroundEntry() = withOwner { owner, current ->
        val activity = launchCode()
        await { current.get() === activity && ready(activity) }
        main {
            press(activity, "arm-code")
            press(activity, "digit-8")
            assertEquals("\u25cf" + "\u25cb".repeat(5), mask(activity))
        }
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
        assertEquals(0, attempts.get())
        marker("P3_FOREGROUND_DROP armed=verified pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}")
        SystemClock.sleep(150)
        Process.killProcess(Process.myPid())
        error("fixture_process_did_not_terminate")
    }

    @Test
    fun foregroundOwnerCannotRestoreAfterProcessDeath() {
        verifyFixture()
        main { assertSame(null, DirectShellPrivateAndroidSetup.current) }
        withOwner { owner, current ->
            val reopened = launchCode()
            await { current.get() === reopened && ready(reopened) }
            main {
                assertEquals("\u25cb".repeat(6), mask(reopened))
                assertFalse(submitView(reopened).isEnabled)
            }
            assertEquals(0, attempts.get())
            assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
            marker("P3_FOREGROUND_REOPEN inert=true pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}")
        }
    }

    @Test
    fun settingsHandoffObservation() = withOwner { owner, current ->
        val original = launchCode()
        await { current.get() === original && ready(original) }
        marker("P3_SETTINGS ready=true sdk=${Build.VERSION.SDK_INT}")
        val until = SystemClock.elapsedRealtime() + 165_000L
        var multiWindow = false
        var focusLost = false
        while (SystemClock.elapsedRealtime() < until) {
            var closed = false
            main {
                val foreground = current.get() ?: original
                multiWindow = multiWindow || foreground.isInMultiWindowMode
                focusLost = focusLost || !foreground.hasWindowFocus()
                closed = foreground.isFinishing
            }
            if (closed) break
            SystemClock.sleep(50)
        }
        assertEquals(0, attempts.get())
        assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
        marker("P3_SETTINGS no_dispatch=true focus_lost=$focusLost multi_window=$multiWindow")
    }

    @Test
    fun api31SingleUnlockedSystemUiReply() = withOwner { owner, current ->
        check(Build.VERSION.SDK_INT == 31)
        val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
        check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked)
        val activity = launch()
        await { current.get() === activity && hasFocus(activity) }
        val cutoff = SystemClock.elapsedRealtime() + DirectShellPairingCoordinator.CONSENT_MS
        click(activity)
        val armed = notice()
        val action = armed.notification.actions.single()
        assertEquals(context.packageName, action.actionIntent.creatorPackage)
        assertTrue(action.isAuthenticationRequired)
        assertEquals(1, action.remoteInputs.size)
        marker("P3_UNLOCKED_REPLY ready=true")
        var appHistory = false
        while (attempts.get() == 0 && SystemClock.elapsedRealtime() < cutoff &&
            owner.state.value.phase == DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE
        ) {
            check(!keyguard.isDeviceLocked && !keyguard.isKeyguardLocked)
            appHistory = appHistory || manager.activeNotifications
                .singleOrNull { it.key == armed.key }?.notification?.extras
                ?.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.isNotEmpty() == true
            SystemClock.sleep(20)
        }
        if (attempts.get() == 0 && owner.state.value.phase == DirectShellPairingPhase.DISCOVERING) {
            await { attempts.get() > 0 || owner.state.value.drained }
        }
        if (attempts.get() != 1) {
            marker("P3_UNLOCKED_REPLY delivered=false phase=${owner.state.value.phase}")
            return@withOwner
        }
        await { owner.state.value.drained && manager.activeNotifications.isEmpty() }
        marker("P3_UNLOCKED_REPLY delivered=true own_notice_removed=true app_history=$appHistory")
        SystemClock.sleep(12_000)
        assertEquals(1, attempts.get())
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun api31ManualSystemUiLockAndReply() = withOwner { owner, current ->
        check(Build.VERSION.SDK_INT == 31)
        val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
        check(keyguard.isDeviceSecure && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {
            "fixture_requires_existing_secure_unlocked_device"
        }
        val activity = launch()
        await { current.get() === activity }
        val cutoff = SystemClock.elapsedRealtime() + DirectShellPairingCoordinator.CONSENT_MS
        click(activity)
        val armed = notice()
        val action = armed.notification.actions.single()
        assertEquals(context.packageName, action.actionIntent.creatorPackage)
        assertEquals(Process.myUid(), action.actionIntent.creatorUid)
        assertEquals(Notification.VISIBILITY_SECRET, armed.notification.visibility)
        assertFalse(action.actionIntent.isImmutable)
        assertTrue(action.isAuthenticationRequired)
        assertFalse(action.allowGeneratedReplies)
        marker("P3_MANUAL armed=true auth_required=true visibility_secret=true")

        var locked = false
        var unlockedAfterLock = false
        var ownHistoryObserved = false
        while (attempts.get() == 0 && SystemClock.elapsedRealtime() < cutoff &&
            owner.state.value.phase == DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE
        ) {
            if (keyguard.isDeviceLocked && keyguard.isKeyguardLocked && !locked) {
                locked = true
                marker("P3_MANUAL device_locked=true")
            }
            if (locked && !unlockedAfterLock && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked) {
                unlockedAfterLock = true
                marker("P3_MANUAL unlocked_after_lock=true")
            }
            ownHistoryObserved = ownHistoryObserved || manager.activeNotifications
                .singleOrNull { it.key == armed.key }?.notification?.extras
                ?.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)?.isNotEmpty() == true
            SystemClock.sleep(20)
        }
        marker("P3_MANUAL app_visible_history=$ownHistoryObserved")
        marker("P3_MANUAL phase_at_observation_end=${owner.state.value.phase}")
        check(locked && unlockedAfterLock) { "fixture_lock_unlock_not_observed" }
        check(attempts.get() == 1) { "fixture_single_systemui_reply_not_observed" }
        await { owner.state.value.drained && manager.activeNotifications.isEmpty() }
        assertFalse(send(action))
        assertEquals(1, attempts.get())
        assertFalse(owner.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)
        marker("P3_MANUAL delivered_once=true retired_reply_rejected=true app_notice_removed=true")
    }

    @Test
    fun platformRotationReplyExpiryAndDismissal() = withOwner { owner, current ->
        check(manager.areNotificationsEnabled()) { "fixture_notifications_unavailable" }
        val before = launch()
        await { current.get() === before }
        assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
        click(before)
        val pending = notice()
        rotate(before)
        val after = checkNotNull(current.get())
        assertNotSame(before, after)
        click(after)
        assertEquals(pending.key, notice().key)
        assertEquals(1L, owner.state.value.originReadinessGeneration)

        val dismiss = checkNotNull(pending.notification.deleteIntent)
        val action = pending.notification.actions.single()
        val reply = action.actionIntent
        assertEquals(context.packageName, reply.creatorPackage)
        assertEquals(android.os.Process.myUid(), reply.creatorUid)
        assertEquals(context.packageName, dismiss.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) {
            assertFalse(reply.isImmutable)
            assertTrue(dismiss.isImmutable)
        }
        assertTrue(send(action))
        await { attempts.get() == 1 && owner.state.value.drained && manager.activeNotifications.isEmpty() }
        assertFalse(send(action))
        assertEquals(1, attempts.get())
        assertFalse(owner.state.value.phase == DirectShellPairingPhase.PAIRED_NEEDS_EXPLICIT_HOST_RETRY)

        click(after)
        val next = notice()
        assertFalse(pending.key == next.key)
        next.notification.deleteIntent.send()
        await { owner.state.value.phase == DirectShellPairingPhase.CANCELLED && manager.activeNotifications.isEmpty() }
        assertFalse(send(next.notification.actions.single()))

        click(after)
        val stale = notice().notification.actions.single()
        readiness.incrementAndGet()
        assertTrue(send(stale))
        await { manager.activeNotifications.isEmpty() }
        assertEquals(1, attempts.get())
        assertEquals(DirectShellPairingPhase.CANCELLED, owner.state.value.phase)
        main { after.finish() }
        await { after.isDestroyed && owner.state.value.phase == DirectShellPairingPhase.CANCELLED }

        val fresh = launch()
        await { current.get() === fresh }
        click(fresh)
        val expired = notice().notification.actions.single()
        advance.addAndGet(DirectShellPairingCoordinator.CONSENT_MS)
        main { owner.onResume() }
        await { owner.state.value.phase == DirectShellPairingPhase.EXPIRED && manager.activeNotifications.isEmpty() }
        assertFalse(send(expired))
        assertEquals(1, attempts.get())
        marker("P3_GATE rotation_reply_dismiss_stale_expiry=verified sdk=${Build.VERSION.SDK_INT}")
    }

    @Test
    fun notificationDeniedInFreshProcess() {
        verifyFixture()
        main { assertSame(null, DirectShellPrivateAndroidSetup.current) }
        val channelId = "directshell_private_pairing"
        check(manager.areNotificationsEnabled()) { "fixture_app_already_denied" }
        check(manager.getNotificationChannel(channelId) == null) { "fixture_channel_already_present" }
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Fixture Blocked", NotificationManager.IMPORTANCE_NONE),
        )
        check(manager.getNotificationChannel(channelId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            "fixture_blocked_channel_not_observed"
        }
        withOwner { owner, current ->
            val activity = launch()
            await { current.get() === activity }
            click(activity)
            instrumentation.waitForIdleSync()
            assertEquals(DirectShellPairingPhase.IDLE, owner.state.value.phase)
            assertTrue(manager.activeNotifications.isEmpty())
            assertEquals(0, attempts.get())
            marker("P3_GATE blocked_channel_no_consent=verified fresh_process_empty=true sdk=${Build.VERSION.SDK_INT}")
        }
    }

    @Test
    fun terminateFixtureWithAnArmedReply() = withOwner { owner, current ->
        val activity = launch()
        await { current.get() === activity }
        click(activity)
        val armed = notice()
        assertEquals(context.packageName, armed.notification.actions.single().actionIntent.creatorPackage)
        assertEquals(DirectShellPairingPhase.AWAITING_CONSENT_AND_CODE, owner.state.value.phase)
        assertEquals(0, attempts.get())
        marker("P3_DROP armed=verified pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}")
        SystemClock.sleep(150)
        Process.killProcess(Process.myPid())
        error("fixture_process_did_not_terminate")
    }

    @Test
    fun oldReplyCannotRestoreOwnerAfterProcessDeath() {
        verifyFixture()
        main { assertSame(null, DirectShellPrivateAndroidSetup.current) }
        val notes = manager.activeNotifications
        check(notes.size <= 1) { "unowned_notification_inventory" }
        val old = notes.singleOrNull()
        val disposition = if (old == null) {
            "absent"
        } else {
            val expired = old.notification.actions.single()
            assertEquals(context.packageName, expired.actionIntent.creatorPackage)
            val delivered = CountDownLatch(1)
            try {
                if (send(expired) { _, _, _, _, _ -> delivered.countDown() }) {
                    check(delivered.await(3, TimeUnit.SECONDS)) { "stale_reply_not_dispatched" }
                    "dispatched_inert"
                } else {
                    "cancelled"
                }
            } finally {
                manager.cancel(old.tag, old.id)
            }
        }
        instrumentation.waitForIdleSync()
        main { assertSame(null, DirectShellPrivateAndroidSetup.current) }
        assertEquals(0, attempts.get())
        await { manager.activeNotifications.isEmpty() }
        marker("P3_GATE after_drop=$disposition owner_absent=true pid=${Process.myPid()} sdk=${Build.VERSION.SDK_INT}")
        marker("P3_GATE cleanup=verified")
    }

    private fun withOwner(run: (AndroidDirectShellPairingOwner, AtomicReference<Activity?>) -> Unit) {
        verifyFixture()
        val current = AtomicReference<Activity?>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is DirectShellPrivatePairingActivity || activity is DirectShellPrivateCodeActivity) {
                    current.set(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lateinit var owner: AndroidDirectShellPairingOwner
        lateinit var carrier: DirectShellPrivateAndroidSetup
        main {
            app.registerActivityLifecycleCallbacks(callbacks)
            owner = owner()
            carrier = DirectShellPrivateAndroidSetup.install(context, owner, readiness::get)
        }
        var primary: Throwable? = null
        try {
            run(owner, current)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                close(owner, carrier, current, callbacks)
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                primary.addSuppressed(cleanup)
            }
        }
    }

    private fun close(
        owner: AndroidDirectShellPairingOwner,
        carrier: DirectShellPrivateAndroidSetup,
        current: AtomicReference<Activity?>,
        callbacks: Application.ActivityLifecycleCallbacks,
    ) {
        main {
            current.get()?.finish()
            carrier.close()
            owner.close()
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
        assertTrue(owner.awaitObserverTermination())
        await { manager.activeNotifications.isEmpty() && current.get()?.isDestroyed != false }
        marker("P3_GATE cleanup=verified")
    }

    private fun owner(): AndroidDirectShellPairingOwner {
        val handler = Handler(Looper.getMainLooper())
        val queue = object : DirectShellPairingTimerQueue {
            override fun post(task: Runnable, delayMs: Long) {
                check(handler.postDelayed(task, delayMs))
            }

            override fun remove(task: Runnable) = handler.removeCallbacks(task)
        }
        return AndroidDirectShellPairingOwner(
            ::readiness,
            { DirectShellPairingEnvironment(true, true, 1, 0) },
            { SystemClock.elapsedRealtime() + advance.get() },
            queue,
        ) { clock, scheduler ->
            DirectShellSyntheticPairingComposition(
                clock,
                scheduler,
                { error("platform_test_no_storage") },
                { error("platform_test_no_identity") },
                { error("platform_test_no_endpoint") },
                checkpoint = { stage ->
                    if (stage == DirectShellSyntheticPairingComposition.Stage.BEFORE_RECOVERY) {
                        attempts.incrementAndGet()
                        error("platform_delivery_only")
                    }
                },
            )
        }
    }

    private fun readiness(): HostReadinessUiState {
        val generation = readiness.get()
        val reason = PrivilegedRuntimeFailureReason.ADB_PAIRING_REQUIRED
        val runtime = PrivilegedRuntimeStateSnapshot(
            generation,
            1,
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            ),
            PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_SHELL,
                PrivilegedRuntimeAvailabilityState.AUTHORIZATION_REQUIRED,
                reason,
            ),
            PrivilegedRuntimeAvailability(PrivilegedRuntimeKind.SHIZUKU, PrivilegedRuntimeAvailabilityState.UNKNOWN),
        )
        return HostReadinessUiState(
            HostReadinessState.AuthorizationRequired(generation, runtime, reason),
            hostActionVisible = true,
        )
    }

    private fun launch(): Activity = instrumentation.startActivitySync(
        Intent(context, DirectShellPrivatePairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun launchCode(): Activity = instrumentation.startActivitySync(codeIntent())

    private fun codeIntent() = Intent(context, DirectShellPrivateCodeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    private fun button(activity: Activity, tag: String): Button =
        activity.findViewById<View>(android.R.id.content).findViewWithTag(tag)

    private fun submitView(activity: Activity) = button(activity, "submit-code")

    private fun enabled(activity: Activity, tag: String): Boolean {
        var enabled = false
        main { enabled = button(activity, tag).isEnabled }
        return enabled
    }

    private fun hasFocus(activity: Activity): Boolean {
        var focus = false
        main { focus = activity.hasWindowFocus() }
        return focus
    }

    private fun ready(activity: Activity) = enabled(activity, "arm-code")

    private fun press(activity: Activity, tag: String) {
        val view = button(activity, tag)
        check(view.isEnabled && view.performClick()) { "fixture_button_unavailable:$tag" }
    }

    private fun mask(activity: Activity): String =
        activity.findViewById<View>(android.R.id.content).findViewWithTag<TextView>("code-mask").text.toString()

    private fun fillSynthetic(activity: Activity) {
        for (digit in 1..6) press(activity, "digit-$digit")
    }

    private fun rotate(activity: Activity) {
        val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val to = if (portrait) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        main { activity.requestedOrientation = to }
        await { activity.isDestroyed }
        instrumentation.waitForIdleSync()
    }

    private fun click(activity: Activity) = main {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue((content.getChildAt(0) as Button).performClick())
    }

    private fun send(action: android.app.Notification.Action, finished: PendingIntent.OnFinished? = null): Boolean {
        val remote = action.remoteInputs.single()
        val intent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(remote),
            intent,
            Bundle().apply {
                putCharSequence(remote.resultKey, CharArray(6) { ('2'.code + it).toChar() }.concatToString())
            },
        )
        return try {
            action.actionIntent.send(context, 0, intent, finished, null)
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private fun notice(): StatusBarNotification {
        await { manager.activeNotifications.size == 1 }
        return manager.activeNotifications.single()
    }

    private fun verifyFixture() {
        check(context.packageName == "io.warpnect.privatesetupgate")
        check(Build.VERSION.SDK_INT == 30 || Build.VERSION.SDK_INT == 31)
        check(android.os.Process.myUid() >= 10000)
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(done: () -> Boolean) {
        val cutoff = SystemClock.elapsedRealtime() + 5_000L
        while (!done()) {
            check(SystemClock.elapsedRealtime() < cutoff) { "platform_observation_timeout" }
            SystemClock.sleep(20)
        }
    }

    private fun marker(message: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$message\n") })
    }
}
