package io.warpnect.debug.directshell

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
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
import io.warpnect.HostPairingSetupLaunchExtras
import io.warpnect.R

/**
 * DEBUG-only setup screen for same-device Wireless Debugging.
 *
 * Pairing code entry intentionally lives in a notification RemoteInput action so the user can keep
 * Android's “Pair device with pairing code” screen open. Pairing and the bounded self-bootstrap
 * sequence proceed automatically after that one code is submitted.
 */
class DirectShellSelfBootstrapProbeActivity : Activity() {
    private lateinit var status: TextView
    private var permissionGate = PairingSetupPermissionGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionGate = PairingSetupPermissionGate(savedInstanceState?.getBoolean("permissionsRequested") ?: false)
        setContentView(createContent())
        preparePairingNotification()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permissionsRequested", permissionGate.requestIssued)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            authorization()?.let { grant ->
                runCatching { DirectShellPairingAuthorization.forApp(this).dismiss(grant) }
            }
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) preparePairingNotification()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new Host setup Intent carries its own authorization; lifecycle callbacks cannot mint one.
        preparePairingNotification(explicitRetry = true)
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
                    preparePairingNotification(explicitRetry = true)
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    } catch (_: ActivityNotFoundException) {
                        status.setText(R.string.directshell_pairing_settings_unavailable)
                    } catch (_: SecurityException) {
                        status.setText(R.string.directshell_pairing_settings_denied)
                    }
                }
            },
        )
        content.addView(
            Button(this@DirectShellSelfBootstrapProbeActivity).apply {
                text = "Mostra notifica pairing"
                setOnClickListener { preparePairingNotification(explicitRetry = true) }
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

    private fun preparePairingNotification(explicitRetry: Boolean = false) {
        val missing = requiredRuntimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        when (permissionGate.action(missing.isNotEmpty(), explicitRetry)) {
            PairingSetupPermissionAction.REQUEST_PERMISSIONS -> {
                status.text = "È richiesta l'autorizzazione per mostrare la notifica e cercare Wireless Debugging."
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                return
            }
            PairingSetupPermissionAction.DENIED -> {
                status.setText(R.string.directshell_pairing_permissions_denied)
                return
            }
            PairingSetupPermissionAction.SHOW_NOTIFICATION -> Unit
        }
        val grant = authorization()
        if (grant == null || !runCatching { DirectShellPairingAuthorization.forApp(this).present(grant) }.getOrDefault(false)) {
            status.text = "Autorizzazione assente, scaduta o gia usata. Riapri la configurazione da Host."
            return
        }
        DirectShellSelfBootstrapNotification.showWaiting(this, grant.token)
        status.text = "Notifica pronta. Nelle Opzioni sviluppatore apri Wireless debugging > " +
            "Pair device with pairing code, poi inserisci il codice nella notifica Warpnect."
        Log.i(TAG, "DIRECT_SHELL_SELF_BOOTSTRAP stage=READY result=PAIRING_NOTIFICATION_READY")
    }

    private fun authorization(): DirectShellPairingAuthorization.Grant? =
        intent.getStringExtra(HostPairingSetupLaunchExtras.TOKEN)?.let { token ->
            DirectShellPairingAuthorization.Grant(
                token,
                intent.getIntExtra(HostPairingSetupLaunchExtras.BOOT, -1),
                intent.getLongExtra(HostPairingSetupLaunchExtras.ISSUED_AT, -1),
            )
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
