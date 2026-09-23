package io.warpnect.debug.directshell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * DEBUG-only setup screen for same-device Wireless Debugging.
 *
 * Pairing code entry intentionally lives in a notification RemoteInput action so the user can keep
 * Android's “Pair device with pairing code” screen open. Pairing and the bounded self-bootstrap
 * sequence proceed automatically after that one code is submitted.
 */
class DirectShellSelfBootstrapProbeActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
        preparePairingNotification()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) preparePairingNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop reuses this setup surface after a completed attempt; refresh its action
        // notification instead of leaving the previous result notification without controls.
        preparePairingNotification()
    }

    private fun createContent(): ScrollView = ScrollView(this).apply {
        val content = LinearLayout(this@DirectShellSelfBootstrapProbeActivity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        content.addView(
            TextView(this@DirectShellSelfBootstrapProbeActivity).apply {
                text = "Warpnect DirectShell self-bootstrap probe\n" +
                    "Il solo campo necessario è nella notifica: codice di pairing a sei cifre."
            },
        )
        content.addView(
            Button(this@DirectShellSelfBootstrapProbeActivity).apply {
                text = "Apri opzioni sviluppatore"
                setOnClickListener {
                    preparePairingNotification()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
            },
        )
        content.addView(
            Button(this@DirectShellSelfBootstrapProbeActivity).apply {
                text = "Mostra notifica pairing"
                setOnClickListener { preparePairingNotification() }
            },
        )
        content.addView(
            Button(this@DirectShellSelfBootstrapProbeActivity).apply {
                text = "Esegui smoke Input silenzioso"
                setOnClickListener { runInputSmoke() }
            },
        )
        status = TextView(this@DirectShellSelfBootstrapProbeActivity).apply {
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        content.addView(status)
        addView(content, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private fun preparePairingNotification() {
        val missing = requiredRuntimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            status.text = "È richiesta l'autorizzazione per mostrare la notifica e cercare Wireless Debugging."
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        DirectShellSelfBootstrapNotification.showWaiting(this)
        status.text = "Notifica pronta. Nelle Opzioni sviluppatore apri Wireless debugging > " +
            "Pair device with pairing code, poi inserisci il codice nella notifica Warpnect."
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=READY result=PAIRING_NOTIFICATION_READY")
    }

    private fun runInputSmoke() {
        status.text = "Avvio smoke Input DirectShell con l'autorizzazione Wireless Debugging esistente..."
        Thread(
            {
                DirectShellSelfBootstrapController(applicationContext).use { controller ->
                    val result = controller.bootstrapPreviouslyAuthorizedInputSmoke()
                    val text = "${result.classification}: ${result.detail}"
                    Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP_INPUT_SMOKE $text")
                    DirectShellSelfBootstrapNotification.showResult(applicationContext, text)
                    runOnUiThread { status.text = text }
                }
            },
            "WarpnectDirectShellSelfBootstrapInputSmoke",
        ).start()
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellBootstrap"
        const val REQUEST_PERMISSIONS = 6_501
    }
}
