package io.warpnect.debug.input

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import java.util.concurrent.atomic.AtomicLong

/** Debug-only owned target for a real Session's reverse-input delivery. */
class InputSessionTargetActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Warpnect reverse input target\nEvents: 0" }
        setContentView(status)
        publishReadyTargetWhenLaidOut()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishReadyTargetWhenLaidOut()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) publishReadyTargetWhenLaidOut()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val kind = when (event.action) {
            KeyEvent.ACTION_DOWN -> "KEY_DOWN"
            KeyEvent.ACTION_UP -> "KEY_UP"
            else -> "KEY_OTHER"
        }
        observe(
            kind,
            "key_code=${event.keyCode} scan_code=${event.scanCode} " +
                "target_display_id=${targetDisplayId()} source=${event.source} event_time=${event.eventTime}",
        )
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "TOUCH_DOWN"
            MotionEvent.ACTION_MOVE -> "TOUCH_MOVE"
            MotionEvent.ACTION_UP -> "TOUCH_UP"
            MotionEvent.ACTION_CANCEL -> "TOUCH_CANCEL"
            else -> "TOUCH_OTHER"
        }
        observe(
            kind,
            "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                "event_time=${event.eventTime}",
        )
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = when {
        event.isFromSource(InputDevice.SOURCE_MOUSE) -> {
            observe(
                "POINTER",
                "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                    "event_time=${event.eventTime}",
            )
            true
        }
        event.isFromSource(InputDevice.SOURCE_JOYSTICK) -> {
            observe(
                "JOYSTICK",
                "x=${event.x} y=${event.y} target_display_id=${targetDisplayId()} source=${event.source} " +
                    "event_time=${event.eventTime}",
            )
            true
        }
        else -> super.onGenericMotionEvent(event)
    }

    private fun observe(kind: String, detail: String) {
        val sequence = eventSequence.incrementAndGet()
        InputSessionTargetProbeState.record(kind)
        status.text = "Warpnect reverse input target\nEvents: $sequence\n$kind"
        Log.i(
            TAG,
            "INPUT_SESSION_TARGET_EVENT sequence=$sequence kind=$kind $detail " +
                "received_time=${System.currentTimeMillis()}",
        )
    }

    private fun publishReadyTargetWhenLaidOut() {
        status.post {
            val bounds = Rect()
            if (!status.getGlobalVisibleRect(bounds) || bounds.width() < 3 || bounds.height() < 3) {
                return@post
            }
            val target = InputSessionTargetProbeState.TouchTarget(bounds.centerX(), bounds.centerY())
            InputSessionTargetProbeState.markReady(target)
            Log.i(TAG, "INPUT_SESSION_TARGET_READY x=${target.x} y=${target.y}")
        }
    }

    private fun targetDisplayId(): Int = window.decorView.display?.displayId ?: -1

    private companion object {
        const val TAG = "WarpnectInputSessionTarget"
        val eventSequence = AtomicLong()
    }
}

/**
 * In-process DEBUG observation bridge for bounded DirectShell Input smoke tests.
 *
 * It records event kinds only. It neither captures raw input nor participates in production input
 * delivery.
 */
internal object InputSessionTargetProbeState {
    data class TouchTarget(
        val x: Int,
        val y: Int,
    )

    private val lock = java.lang.Object()
    private var activeGeneration = 0L
    private var readyGeneration = 0L
    private var touchTarget: TouchTarget? = null
    private val eventKinds = linkedSetOf<String>()
    private val orderedEvents = mutableListOf<String>()

    fun begin(): Long = synchronized(lock) {
        activeGeneration += 1L
        readyGeneration = 0L
        touchTarget = null
        eventKinds.clear()
        orderedEvents.clear()
        activeGeneration
    }

    fun markReady(target: TouchTarget) = synchronized(lock) {
        if (activeGeneration > 0L) {
            readyGeneration = activeGeneration
            touchTarget = target
            lock.notifyAll()
        }
    }

    fun record(kind: String) = synchronized(lock) {
        if (activeGeneration > 0L) {
            eventKinds += kind
            if (orderedEvents.size < 256) orderedEvents += kind
            lock.notifyAll()
        }
    }

    fun awaitReady(generation: Long, timeoutMillis: Long): Boolean = synchronized(lock) {
        await(generation, timeoutMillis) { readyGeneration == generation }
        readyGeneration == generation
    }

    fun awaitTouchTarget(generation: Long, timeoutMillis: Long): TouchTarget? = synchronized(lock) {
        await(generation, timeoutMillis) { readyGeneration == generation && touchTarget != null }
        touchTarget.takeIf { readyGeneration == generation }
    }

    fun awaitEvents(generation: Long, expected: Set<String>, timeoutMillis: Long): Set<String> = synchronized(lock) {
        await(generation, timeoutMillis) { expected.all(eventKinds::contains) }
        return expected - eventKinds
    }

    fun awaitOrdered(
        generation: Long,
        expected: List<String>,
        timeoutMillis: Long,
        ignoredKinds: Set<String> = emptySet(),
    ): Boolean = synchronized(lock) {
        fun relevant() = orderedEvents.filterNot { it in ignoredKinds }
        await(generation, timeoutMillis) { relevant().size >= expected.size }
        generation == activeGeneration && relevant().take(expected.size) == expected
    }

    private fun await(generation: Long, timeoutMillis: Long, complete: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (generation == activeGeneration && !complete()) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            try {
                lock.wait(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
