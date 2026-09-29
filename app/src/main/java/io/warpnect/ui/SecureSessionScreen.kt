package io.warpnect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.warpnect.BuildConfig
import io.warpnect.platform.discovery.AndroidDiscoveryDebugLog
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import io.warpnect.platform.privileged.readiness.HostReadinessPhase
import io.warpnect.platform.privileged.readiness.HostReadinessState
import io.warpnect.platform.privileged.readiness.HostReadinessUiState
import io.warpnect.platform.privileged.readiness.generationOrNull
import io.warpnect.platform.session.integration.DeviceRoleCapability
import io.warpnect.platform.session.integration.hostReadinessFailureMessage
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryError
import io.warpnect.session.discovery.DiscoverySnapshot
import io.warpnect.session.integration.SecureSessionApplicationController
import io.warpnect.session.integration.SecureSessionApplicationSnapshot
import io.warpnect.session.integration.SecureSessionCoordinatorState
import io.warpnect.session.integration.SecureSessionIntegrationError
import kotlinx.coroutines.delay

/** Minimal RFC-005I product surface. No manual IP address or UDP-port values are accepted here. */
@Composable
internal fun SecureSessionScreen(
    controller: SecureSessionApplicationController,
    onEnableHost: () -> Unit,
    onDisableHost: () -> Unit,
    onFindHosts: () -> Unit,
    discoveryPermissionNotice: String?,
    debugMicrophoneRequested: Boolean,
    microphonePermissionNotice: String?,
    onDebugMicrophoneRequested: (Boolean) -> Unit,
    clientVideoRendererBound: Boolean,
    clientVideoStreaming: Boolean,
    clientCapability: DeviceRoleCapability,
    hostCapability: DeviceRoleCapability,
    hostReadinessUiState: HostReadinessUiState,
    onDismissHostReadiness: () -> Unit,
    onClientRenderSurfaceAttached: (WarpnectVideoSurfaceView) -> Unit,
    onClientRenderSurfaceDetached: (WarpnectVideoSurfaceView) -> Unit,
    onClientInputSurfaceAttached: (WarpnectInputCaptureView) -> Unit,
    onClientInputSurfaceDetached: (WarpnectInputCaptureView) -> Unit,
    onDeveloperManual: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.snapshot.collectAsState()
    val uiModel = secureSessionScreenUiModel(snapshot)
    val active = uiModel.active
    val hosts = uiModel.hosts
    val cancellableClientDiscovery = shouldCancelClientDiscovery(uiModel.activeRole, active?.state)
    val clientVideoSurfaceVisible = shouldComposeClientVideoSurface(uiModel.activeRole, clientVideoRendererBound)
    val context = LocalContext.current
    val discoveryDebugLog = remember(context) { AndroidDiscoveryDebugLog(context) }
    val hostPreparationPresentation = hostReadinessPresentation(hostReadinessUiState)

    LaunchedEffect(uiModel.hostCount) {
        discoveryDebugLog.uiStateReceived(uiModel.hostCount)
    }
    LaunchedEffect(uiModel.chooserVisible, uiModel.hostCount) {
        if (uiModel.chooserVisible) discoveryDebugLog.chooserVisible(uiModel.hostCount)
    }

    BackHandler(enabled = cancellableClientDiscovery) {
        controller.cancelClientDiscovery()
    }

    hostPreparationPresentation?.let { presentation ->
        if (presentation.autoDismiss) {
            LaunchedEffect(hostReadinessUiState.readiness.generationOrNull) {
                delay(HOST_READY_NOTICE_DURATION_MS)
                onDismissHostReadiness()
            }
        }
        HostReadinessDialog(
            presentation = presentation,
            onDismiss = onDismissHostReadiness,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (clientVideoSurfaceVisible) {
            ClientVideoSurface(
                onAttached = onClientRenderSurfaceAttached,
                onDetached = onClientRenderSurfaceDetached,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Warpnect", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                sessionStatusText(
                    activeRole = uiModel.activeRole,
                    state = active?.state,
                    error = active?.lastError,
                    clientVideoStreaming = clientVideoStreaming,
                ),
                fontSize = 20.sp,
            )
            Text("Client: ${roleCapabilityText(clientCapability)}")
            Text("Host: ${roleCapabilityText(hostCapability)}")
            discoveryDetail(active?.discovery, discoveryPermissionNotice)?.let { detail -> Text(detail) }
            if (BuildConfig.DEBUG && uiModel.activeRole != SessionRole.Host) {
                val canChangeMicrophone = active?.state == null || active.state in setOf(
                    SecureSessionCoordinatorState.Idle,
                    SecureSessionCoordinatorState.Discovering,
                    SecureSessionCoordinatorState.Closed,
                    SecureSessionCoordinatorState.Failed,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = debugMicrophoneRequested,
                        onCheckedChange = onDebugMicrophoneRequested,
                        enabled = canChangeMicrophone,
                    )
                    Text("Microphone to Host")
                }
                microphonePermissionNotice?.let { Text(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiModel.activeRole == SessionRole.Host) {
                    Button(onClick = onDisableHost) { Text("Disable Host") }
                } else {
                    Button(
                        onClick = onEnableHost,
                        enabled = !hostReadinessUiState.hostActionVisible ||
                            hostPreparationPresentation?.dismissible == true,
                    ) { Text("Enable Host") }
                }
                OutlinedButton(onClick = onFindHosts) {
                    Text("Find Hosts")
                }
            }
            if (uiModel.activeRole == SessionRole.Client) {
                if (cancellableClientDiscovery) {
                    OutlinedButton(
                        onClick = controller::cancelClientDiscovery,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel search")
                    }
                }
                if (uiModel.chooserVisible) {
                    HostChooser(
                        rows = hostChooserRows(hosts),
                        onConnect = { presence -> controller.connect(presence) },
                        debugLog = discoveryDebugLog,
                    )
                } else {
                    Text("No hosts found yet.")
                }
                ClientInputCaptureSurface(onClientInputSurfaceAttached, onClientInputSurfaceDetached)
            }
            active?.pairingVerificationPrompt?.let { prompt ->
                SecurePeerVerification(
                    shortAuthenticationString = prompt.shortAuthenticationString,
                    onConfirm = controller::approvePairing,
                    onReject = controller::rejectPairing,
                )
            }
            if (uiModel.activeRole == SessionRole.Client) {
                Spacer(modifier = Modifier.height(4.dp))
                if (!cancellableClientDiscovery) {
                    OutlinedButton(onClick = controller::disconnect, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect")
                    }
                }
            }
            OutlinedButton(onClick = onDeveloperManual, modifier = Modifier.fillMaxWidth()) {
                Text("Developer Manual")
            }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostics")
            }
        }
    }
}

internal data class HostReadinessPresentation(
    val title: String,
    val detail: String,
    val dismissible: Boolean,
    val autoDismiss: Boolean = false,
)

/** Pure mapping from structured orchestration state to the minimal Host-preparation surface. */
internal fun hostReadinessPresentation(state: HostReadinessUiState): HostReadinessPresentation? {
    if (!state.hostActionVisible) return null
    return when (val readiness = state.readiness) {
        HostReadinessState.NotStarted,
        is HostReadinessState.Stale,
        -> null

        is HostReadinessState.Checking -> HostReadinessPresentation(
            title = "Preparing Host",
            detail = when (readiness.phase) {
                HostReadinessPhase.CHECKING_DEVICE -> "Checking device..."
                HostReadinessPhase.CHECKING_PRIVILEGED_RUNTIME -> "Checking privileged runtime..."
                HostReadinessPhase.PREPARING_HOST -> "Preparing Host..."
            },
            dismissible = false,
        )

        is HostReadinessState.Ready -> HostReadinessPresentation(
            title = "Host ready",
            detail = "Host capabilities are ready.",
            dismissible = false,
            autoDismiss = true,
        )

        is HostReadinessState.AuthorizationRequired -> HostReadinessPresentation(
            title = "Host setup required",
            detail = hostReadinessFailureMessage(readiness.reason),
            dismissible = true,
        )

        is HostReadinessState.UserActionRequired -> HostReadinessPresentation(
            title = "Host setup required",
            detail = hostReadinessFailureMessage(readiness.reason),
            dismissible = true,
        )

        is HostReadinessState.Unsupported -> HostReadinessPresentation(
            title = "Host mode unavailable",
            detail = hostReadinessFailureMessage(readiness.reason),
            dismissible = true,
        )

        is HostReadinessState.Failed -> HostReadinessPresentation(
            title = "Host mode unavailable",
            detail = hostReadinessFailureMessage(readiness.reason),
            dismissible = true,
        )
    }
}

@Composable
private fun HostReadinessDialog(presentation: HostReadinessPresentation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {
            if (presentation.dismissible) onDismiss()
        },
        title = { Text(presentation.title) },
        text = { Text(presentation.detail) },
        confirmButton = {
            if (presentation.dismissible) {
                Button(onClick = onDismiss) { Text("Dismiss") }
            }
        },
    )
}

private const val HOST_READY_NOTICE_DURATION_MS = 1_000L

internal fun roleCapabilityText(capability: DeviceRoleCapability): String = when (capability) {
    DeviceRoleCapability.NotChecked -> "Not checked"
    DeviceRoleCapability.Checking -> "Checking compatibility"
    DeviceRoleCapability.Available -> "Available"
    is DeviceRoleCapability.SetupRequired -> capability.message
    is DeviceRoleCapability.Unavailable -> capability.message
}

/*
 * The renderer must follow actual receiver-pipeline ownership, not the Client discovery state.
 * This small pure predicate is intentionally covered by JVM UI-state tests.
 */
internal fun shouldComposeClientVideoSurface(activeRole: SessionRole?, clientVideoRendererBound: Boolean): Boolean =
    activeRole == SessionRole.Client && clientVideoRendererBound

internal fun shouldCancelClientDiscovery(activeRole: SessionRole?, state: SecureSessionCoordinatorState?): Boolean =
    activeRole == SessionRole.Client && state in setOf(
        SecureSessionCoordinatorState.Discovering,
        SecureSessionCoordinatorState.Failed,
    )

internal data class SecureSessionScreenUiModel(
    val activeRole: SessionRole?,
    val active: io.warpnect.session.integration.SecureSessionCoordinatorSnapshot?,
    val hosts: List<DiscoveredPresence>,
) {
    val hostCount: Int get() = hosts.size
    val chooserVisible: Boolean get() = activeRole == SessionRole.Client && hosts.isNotEmpty()
}

/** Bounded presentation projection. It intentionally preserves every discovered candidate. */
internal data class HostChooserRow(
    val presence: DiscoveredPresence,
    val label: String,
    val detail: String?,
)

internal fun hostChooserRows(hosts: List<DiscoveredPresence>): List<HostChooserRow> {
    val labels = hosts.map { presence -> presence.displayAlias?.value ?: "Nearby Host" }
    val labelCounts = labels.groupingBy { it }.eachCount()
    return hosts.zip(labels).map { (presence, label) ->
        HostChooserRow(
            presence = presence,
            label = label,
            detail = if (labelCounts.getValue(label) > 1) {
                "Host ID ${presence.presenceId.shortValue()}"
            } else {
                null
            },
        )
    }
}

internal const val HOST_CHOOSER_TEST_TAG = "secure_session_host_chooser"
internal const val HOST_ROW_TEST_TAG = "secure_session_host_row"
internal const val HOST_CONNECT_TEST_TAG = "secure_session_host_connect"

/** Compose receives one immutable application snapshot, including its bounded discovered-host view. */
internal fun secureSessionScreenUiModel(snapshot: SecureSessionApplicationSnapshot): SecureSessionScreenUiModel =
    SecureSessionScreenUiModel(
        activeRole = snapshot.activeRole,
        active = snapshot.active,
        hosts = if (snapshot.activeRole == SessionRole.Client) {
            snapshot.active?.discovery?.candidates.orEmpty()
        } else {
            emptyList()
        },
    )

/** The actual bounded chooser content rendered by [SecureSessionScreen]. */
@Composable
internal fun HostChooser(
    rows: List<HostChooserRow>,
    onConnect: (DiscoveredPresence) -> Unit,
    debugLog: AndroidDiscoveryDebugLog,
) {
    LaunchedEffect(rows) {
        debugLog.chooserModel(rows.size)
        debugLog.hostRowsCreated(rows.size)
    }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(HOST_CHOOSER_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Hosts found")
        rows.forEach { row ->
            key(row.presence.presenceId) {
                LaunchedEffect(row.presence.presenceId) {
                    debugLog.hostRowComposed()
                }
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(HOST_ROW_TEST_TAG),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(row.label)
                        row.detail?.let { detail -> Text(detail, fontSize = 12.sp) }
                    }
                    Button(
                        onClick = { onConnect(row.presence) },
                        modifier = Modifier.testTag(HOST_CONNECT_TEST_TAG),
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

internal fun sessionStatusText(
    activeRole: SessionRole?,
    state: SecureSessionCoordinatorState?,
    error: SecureSessionIntegrationError?,
    clientVideoStreaming: Boolean = false,
): String = when {
    error == SecureSessionIntegrationError.DiscoveryStartFailed -> "Discovery unavailable"
    state == SecureSessionCoordinatorState.Discovering && activeRole == SessionRole.Host -> "Waiting for clients"
    state == SecureSessionCoordinatorState.Discovering && activeRole == SessionRole.Client -> "Searching for hosts"
    state == SecureSessionCoordinatorState.Connecting -> "Connecting"
    state == SecureSessionCoordinatorState.PairingRequired -> "Securing connection"
    state == SecureSessionCoordinatorState.Pairing -> "Verifying secure connection"
    state == SecureSessionCoordinatorState.Running &&
        activeRole == SessionRole.Client &&
        clientVideoStreaming -> "Streaming"
    state == SecureSessionCoordinatorState.Running -> "Running"
    state == SecureSessionCoordinatorState.Recovering -> "Recovering"
    state == SecureSessionCoordinatorState.Failed -> "Failed"
    else -> "Ready"
}

/** The only user decision in RFC-005C is whether the displayed peer verification code matches. */
@Composable
private fun SecurePeerVerification(shortAuthenticationString: String, onConfirm: () -> Unit, onReject: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Verify security code", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(shortAuthenticationString, fontSize = 32.sp)
        Text("Confirm that this code matches on both devices.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onConfirm) { Text("Confirm") }
            OutlinedButton(onClick = onReject) { Text("Reject") }
        }
    }
}

internal fun discoveryDetail(snapshot: DiscoverySnapshot?, permissionNotice: String?): String? = when {
    permissionNotice != null -> permissionNotice
    snapshot?.directBackend?.lastError == DiscoveryError.DirectPermissionRequired ->
        if (snapshot.state == io.warpnect.session.discovery.DiscoveryControllerState.RunningDegraded) {
            "Wi-Fi Direct permission is required; continuing with LAN discovery."
        } else {
            "Wi-Fi Direct permission is required."
        }
    snapshot?.directBackend?.lastError == DiscoveryError.LocationServicesDisabled ->
        if (snapshot.state == io.warpnect.session.discovery.DiscoveryControllerState.RunningDegraded) {
            "Wi-Fi Direct requires Location to be enabled; continuing with LAN discovery."
        } else {
            "Wi-Fi Direct requires Location to be enabled."
        }
    snapshot?.lanBackend?.lastError != null && snapshot.lanBackend.lastError != DiscoveryError.None -> {
        "LAN discovery is unavailable; continuing with Wi-Fi Direct."
    }
    snapshot?.state == io.warpnect.session.discovery.DiscoveryControllerState.RunningDegraded -> {
        "One discovery backend is unavailable."
    }
    else -> null
}

@Composable
private fun ClientVideoSurface(
    onAttached: (WarpnectVideoSurfaceView) -> Unit,
    onDetached: (WarpnectVideoSurfaceView) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val renderSurface = remember { WarpnectVideoSurfaceView(context) }
    DisposableEffect(renderSurface) {
        onAttached(renderSurface)
        onDispose { onDetached(renderSurface) }
    }
    AndroidView(
        factory = { renderSurface },
        modifier = modifier,
    )
}

@Composable
private fun ClientInputCaptureSurface(
    onAttached: (WarpnectInputCaptureView) -> Unit,
    onDetached: (WarpnectInputCaptureView) -> Unit,
) {
    val context = LocalContext.current
    val inputSurface = remember { WarpnectInputCaptureView(context) }
    DisposableEffect(inputSurface) {
        onAttached(inputSurface)
        onDispose { onDetached(inputSurface) }
    }
    AndroidView(
        factory = { inputSurface },
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    )
}
