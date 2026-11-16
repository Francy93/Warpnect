package io.warpnect

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.warpnect.diagnostics.ui.DiagnosticsRuntimeSummary
import io.warpnect.diagnostics.ui.DiagnosticsUiController
import io.warpnect.platform.diagnostics.AndroidDiagnosticsUiClock
import io.warpnect.platform.discovery.AndroidDiscoveryEnvironmentProvider
import io.warpnect.platform.privileged.readiness.HostPairingSetupAdapter
import io.warpnect.platform.privileged.readiness.HostPairingSetupLaunchResult
import io.warpnect.session.SessionRole
import io.warpnect.ui.DiagnosticsScreen
import io.warpnect.ui.MainScreen
import io.warpnect.ui.SecureSessionScreen

internal object HostPairingSetupLaunchExtras {
    const val TOKEN = "io.warpnect.host_pairing_setup.TOKEN"
    const val BOOT = "io.warpnect.host_pairing_setup.BOOT"
    const val ISSUED_AT = "io.warpnect.host_pairing_setup.ISSUED_AT"
}

class MainActivity : ComponentActivity() {
    private val composition: io.warpnect.platform.session.integration.AndroidSecureSessionComposition?
        get() = (application as WarpnectApplication).secureSessionComposition
    private var debugInputTargetEventsRemaining = MAX_DEBUG_INPUT_TARGET_EVENTS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WarpnectApp(composition = composition)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (BuildConfig.DEBUG && event.actionMasked == MotionEvent.ACTION_DOWN && debugInputTargetEventsRemaining > 0) {
            debugInputTargetEventsRemaining -= 1
            runCatching { Log.i(INPUT_TARGET_TAG, "INPUT_SESSION_MAIN_TARGET_TOUCH_OBSERVED") }
        }
        return super.dispatchTouchEvent(event)
    }

    private companion object {
        const val INPUT_TARGET_TAG = "WarpnectInputSessionTarget"
        const val MAX_DEBUG_INPUT_TARGET_EVENTS = 12
    }
}

@Composable
private fun WarpnectApp(composition: io.warpnect.platform.session.integration.AndroidSecureSessionComposition?) {
    var surface by remember { mutableStateOf(AppSurface.SecureSession) }
    val context = LocalContext.current
    val discoveryEnvironment = remember(context) { AndroidDiscoveryEnvironmentProvider(context) }
    var pendingDiscoveryRole by remember { mutableStateOf<SessionRole?>(null) }
    var discoveryPermissionNotice by remember { mutableStateOf<String?>(null) }
    var debugMicrophoneRequested by remember { mutableStateOf(false) }
    var microphonePermissionNotice by remember { mutableStateOf<String?>(null) }
    var pairingSetupToken by rememberSaveable { mutableStateOf<String?>(null) }
    val pairingSetupIntent = remember(context) { HostPairingSetupAdapter.createIntent(context) }
    val pairingSetupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pairingSetupToken?.let { token -> composition?.hostPairingSetup?.returned(token) }
        pairingSetupToken = null
    }

    val onOpenHostPairingSetup: (Long) -> Unit = { generation ->
        val boot = if (pairingSetupIntent != null) {
            runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }.getOrDefault(-1)
        } else {
            -1
        }
        val issuedAt = SystemClock.elapsedRealtime()
        composition?.hostPairingSetup?.request(
            generation,
            pairingSetupIntent != null && pairingSetupToken == null,
        ) { token ->
            pairingSetupToken = token
            try {
                pairingSetupLauncher.launch(
                    Intent(requireNotNull(pairingSetupIntent))
                        .putExtra(HostPairingSetupLaunchExtras.TOKEN, token)
                        .putExtra(HostPairingSetupLaunchExtras.BOOT, boot)
                        .putExtra(HostPairingSetupLaunchExtras.ISSUED_AT, issuedAt),
                )
                HostPairingSetupLaunchResult.STARTED
            } catch (_: ActivityNotFoundException) {
                pairingSetupToken = null
                HostPairingSetupLaunchResult.UNAVAILABLE
            } catch (_: SecurityException) {
                pairingSetupToken = null
                HostPairingSetupLaunchResult.PERMISSION_DENIED
            } catch (_: IllegalStateException) {
                pairingSetupToken = null
                HostPairingSetupLaunchResult.UNAVAILABLE
            }
        }
    }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        debugMicrophoneRequested = granted && BuildConfig.DEBUG
        composition?.setDebugMicrophoneAudioRequested(debugMicrophoneRequested)
        microphonePermissionNotice = if (granted) null else "Microphone permission denied"
    }

    val onDebugMicrophoneRequested: (Boolean) -> Unit = { requested ->
        if (!requested) {
            debugMicrophoneRequested = false
            microphonePermissionNotice = null
            composition?.setDebugMicrophoneAudioRequested(false)
        } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            debugMicrophoneRequested = true
            microphonePermissionNotice = null
            composition?.setDebugMicrophoneAudioRequested(true)
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val startDiscoveryNow: (SessionRole) -> Unit = { role ->
        when (role) {
            SessionRole.Host -> composition?.startHostFromUi()
            SessionRole.Client -> composition?.applicationController?.startClientDiscovery()
        }
    }
    val discoveryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val role = pendingDiscoveryRole ?: return@rememberLauncherForActivityResult
        pendingDiscoveryRole = null
        val stillMissing = discoveryEnvironment.missingWifiDirectRuntimePermissions()
        discoveryPermissionNotice = if (stillMissing.isEmpty()) {
            null
        } else if (stillMissing.contains("android.permission.NEARBY_WIFI_DEVICES")) {
            "Nearby Wi-Fi permission was not granted. Continuing with LAN discovery; Wi-Fi Direct is unavailable."
        } else {
            "Location permission was not granted. Continuing with LAN discovery; Wi-Fi Direct is unavailable."
        }
        startDiscoveryNow(role)
    }

    fun requestDiscoveryStart(role: SessionRole) {
        val missingPermissions = discoveryEnvironment.missingWifiDirectRuntimePermissions()
        if (missingPermissions.isEmpty()) {
            discoveryPermissionNotice = null
            startDiscoveryNow(role)
        } else {
            pendingDiscoveryRole = role
            discoveryPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    MaterialTheme {
        Surface {
            if (composition == null) {
                SecureSessionUnavailableScreen(onDeveloperManual = { surface = AppSurface.DeveloperManual })
            } else if (surface == AppSurface.DeveloperManual) {
                val role by composition.coreOrchestrator.role.collectAsState()
                MainScreen(
                    role = role,
                    onIdleSelected = composition.coreOrchestrator::enterIdle,
                    onReceiverSelected = composition.coreOrchestrator::enterReceiverMode,
                    onTransmitterSelected = composition.coreOrchestrator::enterTransmitterMode,
                    onBackToSecureSession = { surface = AppSurface.SecureSession },
                    modifier = Modifier,
                )
            } else if (surface == AppSurface.Diagnostics) {
                val controller = remember(composition) {
                    DiagnosticsUiController(
                        telemetryHub = composition.telemetryHub,
                        diagnosticEventHub = composition.diagnosticEventHub,
                        runtimeSummary = {
                            val snapshot = composition.applicationController.snapshot.value
                            DiagnosticsRuntimeSummary(
                                role = snapshot.activeRole?.name,
                                lifecycleState = snapshot.active?.state?.name,
                            )
                        },
                        clock = AndroidDiagnosticsUiClock,
                    )
                }
                DiagnosticsScreen(
                    controller = controller,
                    reportController = composition.reportExportController,
                    onClose = { surface = AppSurface.SecureSession },
                    modifier = Modifier,
                )
            } else {
                val clientVideoRendererBound by composition.uiResources.clientVideoRendererBound.collectAsState()
                val clientVideoStreaming by composition.uiResources.clientVideoStreaming.collectAsState()
                val clientCapability by composition.uiResources.clientCapability.collectAsState()
                val hostCapability by composition.uiResources.hostCapability.collectAsState()
                val hostReadinessUiState by composition.hostReadinessUiState.collectAsState()
                val hostPairingSetupAttempt by composition.hostPairingSetup.attempt.collectAsState()
                SecureSessionScreen(
                    controller = composition.applicationController,
                    onEnableHost = { requestDiscoveryStart(SessionRole.Host) },
                    onDisableHost = composition::stopHostFromUi,
                    onFindHosts = { requestDiscoveryStart(SessionRole.Client) },
                    discoveryPermissionNotice = discoveryPermissionNotice,
                    debugMicrophoneRequested = debugMicrophoneRequested,
                    microphonePermissionNotice = microphonePermissionNotice,
                    onDebugMicrophoneRequested = onDebugMicrophoneRequested,
                    clientVideoRendererBound = clientVideoRendererBound,
                    clientVideoStreaming = clientVideoStreaming,
                    clientCapability = clientCapability,
                    hostCapability = hostCapability,
                    hostReadinessUiState = hostReadinessUiState,
                    onDismissHostReadiness = composition::dismissHostReadinessNotice,
                    hostPairingSetupAvailable = pairingSetupIntent != null && pairingSetupToken == null,
                    hostPairingSetupAttempt = hostPairingSetupAttempt,
                    onOpenHostPairingSetup = onOpenHostPairingSetup,
                    onClientRenderSurfaceAttached = composition.uiResources::attachClientRenderSurface,
                    onClientRenderSurfaceDetached = composition.uiResources::clearClientRenderSurface,
                    onClientInputSurfaceAttached = composition.uiResources::attachClientInputSurface,
                    onClientInputSurfaceDetached = composition.uiResources::clearClientInputSurface,
                    onDeveloperManual = { surface = AppSurface.DeveloperManual },
                    onDiagnostics = { surface = AppSurface.Diagnostics },
                    modifier = Modifier,
                )
            }
        }
    }
}

private enum class AppSurface {
    SecureSession,
    DeveloperManual,
    Diagnostics,
}

@Composable
private fun SecureSessionUnavailableScreen(onDeveloperManual: () -> Unit) {
    androidx.compose.foundation.layout.Column {
        Text("Warpnect secure Session services are unavailable")
        OutlinedButton(onClick = onDeveloperManual) { Text("Developer Manual") }
    }
}
