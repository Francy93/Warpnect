package io.warpnect.debug.directshell

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View

/**
 * DEBUG-only visual fixture for DirectShell display-mirror validation.
 *
 * State A and State B are deliberately high-contrast and use no audio. A captured encoder hash
 * changing across the two states is supplemental evidence; the later Client screenshots remain the
 * authoritative visible-video proof.
 */
class DirectShellVideoCaptureProbeActivity : Activity() {
    private lateinit var probeView: ProbeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeView = ProbeView()
        setContentView(probeView)
        applyIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent()
    }

    private fun applyIntent() {
        val state = intent.getStringExtra(EXTRA_STATE)?.takeIf { it == STATE_A || it == STATE_B } ?: STATE_A
        probeView.setState(state)
        Log.i(TAG, "DIRECT_SHELL_VIDEO_FIXTURE_STATE state=$state elapsed_ms=${SystemClock.elapsedRealtime()}")
    }

    private inner class ProbeView : View(this@DirectShellVideoCaptureProbeActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var state = STATE_A

        fun setState(value: String) {
            state = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat().coerceAtLeast(1f)
            val height = height.toFloat().coerceAtLeast(1f)
            if (state == STATE_A) {
                canvas.drawColor(Color.WHITE)
                paint.color = Color.BLACK
                canvas.drawRect(width * 0.08f, height * 0.12f, width * 0.92f, height * 0.88f, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(width * 0.5f, height * 0.5f, minOf(width, height) * 0.22f, paint)
                paint.color = Color.rgb(220, 0, 0)
                canvas.drawRect(0f, 0f, width, height * 0.08f, paint)
            } else {
                canvas.drawColor(Color.BLACK)
                val square = minOf(width, height) / 8f
                for (row in 0 until 8) {
                    for (column in 0 until 8) {
                        paint.color = if ((row + column) % 2 == 0) Color.YELLOW else Color.BLUE
                        canvas.drawRect(
                            width * 0.1f + column * square,
                            height * 0.16f + row * square,
                            width * 0.1f + (column + 1) * square,
                            height * 0.16f + (row + 1) * square,
                            paint,
                        )
                    }
                }
                paint.color = Color.GREEN
                canvas.drawRect(width * 0.05f, height * 0.9f, width * 0.95f, height * 0.98f, paint)
            }
        }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellVideo"
        const val EXTRA_STATE = "state"
        const val STATE_A = "a"
        const val STATE_B = "b"
    }
}
