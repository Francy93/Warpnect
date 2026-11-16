package io.warpnect.platform.privileged.directshell.pairing

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.warpnect.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Registered only in the isolated fixture; the digits never enter an editable View or input connection. */
internal class DirectShellPrivateCodeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val digits = CharArray(6)
    private var length = 0
    private var resumed = false
    private var action: DirectShellPrivateSetupSession.ForegroundAction? = null
    private var setup: DirectShellPrivateAndroidSetup? = null
    private var screen: DirectShellPrivateSetupSession.Attachment? = null
    private lateinit var status: TextView
    private lateinit var open: Button
    private lateinit var submit: Button
    private lateinit var delete: Button
    private lateinit var cancelEntry: Button
    private val keys = mutableListOf<Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val owner = DirectShellPrivateAndroidSetup.current ?: return finish()
        setup = owner
        screen = owner.attach()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveFromParentEnabled = false
            setPadding(16, 8, 16, 8)
        }
        val title = TextView(this).apply {
            setText(R.string.directshell_private_code_title)
            textSize = 22f
        }
        panel.addView(title)
        status = TextView(this).apply { tag = "code-mask" }
        panel.addView(status)
        open = button("arm-code", getString(R.string.directshell_private_code_arm), ::arm)
        panel.addView(open)
        for (row in listOf(1..3, 4..6, 7..9)) {
            panel.addView(LinearLayout(this).apply { row.forEach { addKey(this, it) } })
        }
        val bottomRow = LinearLayout(this).apply {
            addKey(this, 0)
            delete = button("delete-code", getString(R.string.directshell_private_code_delete), ::eraseLast)
            addView(delete, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(bottomRow)
        submit = button("submit-code", getString(R.string.directshell_private_code_confirm), ::confirm)
        cancelEntry = button("cancel-code", getString(android.R.string.cancel)) { retire() }
        panel.addView(submit)
        panel.addView(cancelEntry)
        panel.addView(button("finish-code", getString(R.string.directshell_private_code_close), ::finish))
        val root = ScrollView(this).apply {
            isSaveEnabled = false
            addView(panel)
        }
        setContentView(root)
        render()
        scope.launch {
            owner.state.collect { if (action != null && !ownsEntry()) retire() }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        screen?.let { setup?.resume(it) }
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && action != null) retire()
        render()
    }

    override fun onPause() {
        resumed = false
        retire()
        screen?.let { setup?.pause(it) }
        super.onPause()
    }

    override fun onDestroy() {
        retire()
        scope.cancel()
        screen?.let { setup?.detach(it, finishing = isFinishing && !isChangingConfigurations) }
        screen = null
        setup = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured != 0) {
            retire()
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    private fun arm() {
        val screen = screen ?: return
        if (!interactive() || action != null) return
        action = setup?.beginForeground(screen)
        render()
    }

    private fun confirm() {
        val screen = screen ?: return
        val issued = action ?: return
        if (!ownsEntry()) return retire()
        if (length != digits.size) return
        action = null
        try {
            setup?.submitForeground(screen, issued, digits)
        } finally {
            wipe()
            render()
        }
    }

    private fun eraseLast() {
        if (!ownsEntry()) return retire()
        if (length > 0) digits[--length] = '\u0000'
        render()
    }

    private fun retire() {
        val issued = action
        action = null
        wipe()
        if (issued != null) screen?.let { setup?.dismissForeground(it, issued) }
        render()
    }

    private fun ownsEntry(): Boolean {
        val screen = screen ?: return false
        val issued = action ?: return false
        return interactive() && setup?.ownsForeground(screen, issued) == true
    }

    private fun interactive() = resumed && hasWindowFocus() && !isFinishing

    private fun wipe() {
        digits.fill('\u0000')
        length = 0
    }

    private fun render() {
        if (!::status.isInitialized) return
        val armed = action != null
        open.isEnabled = interactive() && !armed
        keys.forEach { it.isEnabled = armed && length < digits.size }
        delete.isEnabled = armed && length > 0
        submit.isEnabled = armed && length == digits.size
        cancelEntry.isEnabled = armed
        status.text = "\u25cf".repeat(length) + "\u25cb".repeat(digits.size - length)
    }

    private fun addKey(row: LinearLayout, value: Int) {
        val key = button("digit-$value", value.toString()) {
            if (!ownsEntry()) return@button retire()
            if (length < digits.size) digits[length++] = '0' + value
            render()
        }
        keys.add(key)
        row.addView(key, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun button(key: String, label: String, click: () -> Unit) = Button(this).apply {
        tag = key
        text = label
        isSaveEnabled = false
        filterTouchesWhenObscured = true
        setOnClickListener { click() }
    }
}
