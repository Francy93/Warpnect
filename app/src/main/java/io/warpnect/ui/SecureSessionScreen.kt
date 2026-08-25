package io.warpnect.ui

import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.warpnect.platform.input.capture.WarpnectInputCaptureView
import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.integration.SecureSessionApplicationController
import io.warpnect.session.integration.SecureSessionCoordinatorState

/** Minimal RFC-005I product surface. No manual IP address or UDP-port values are accepted here. */
@Composable
fun SecureSessionScreen(
    controller: SecureSessionApplicationController,
    onClientViewsAttached: (SurfaceView, WarpnectInputCaptureView) -> Unit,
    onClientViewsDetached: (SurfaceView, WarpnectInputCaptureView) -> Unit,
    onDeveloperManual: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hosts by remember { mutableStateOf(emptyList<DiscoveredPresence>()) }
    val snapshot by controller.snapshot.collectAsState()
    val active = snapshot.active

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Warpnect", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when (active?.state) {
                SecureSessionCoordinatorState.Running -> "Running"
                SecureSessionCoordinatorState.Recovering -> "Recovering"
                SecureSessionCoordinatorState.Failed -> "Failed"
                else -> "Ready"
            },
            fontSize = 20.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshot.activeRole == SessionRole.Host) {
                Button(onClick = controller::stopHost) { Text("Disable Host") }
            } else {
                Button(onClick = { controller.startHost() }) { Text("Enable Host") }
            }
            OutlinedButton(onClick = {
                controller.startClientDiscovery()
                hosts = controller.discoveredHosts()
            }) {
                Text("Find Hosts")
            }
        }
        if (snapshot.activeRole == SessionRole.Client) {
            ClientPipelineSurfaces(onClientViewsAttached, onClientViewsDetached)
            OutlinedButton(onClick = { hosts = controller.discoveredHosts() }) {
                Text("Refresh")
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(hosts, key = { it.presenceId.encodedValue() }) { presence ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(presence.displayAlias?.value ?: "Nearby Host")
                        Button(onClick = { controller.connect(presence) }) { Text("Connect") }
                    }
                }
            }
            if (active?.state == SecureSessionCoordinatorState.PairingRequired) {
                Button(onClick = controller::beginExplicitPairing) { Text("Pair") }
            }
        }
        if (active?.pairingVerificationPrompt != null) {
            Button(onClick = controller::approvePairing) { Text("Confirm Pairing") }
        }
        if (snapshot.activeRole == SessionRole.Client) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(onClick = controller::disconnect, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
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

@Composable
private fun ClientPipelineSurfaces(
    onAttached: (SurfaceView, WarpnectInputCaptureView) -> Unit,
    onDetached: (SurfaceView, WarpnectInputCaptureView) -> Unit,
) {
    val context = LocalContext.current
    val renderSurface = remember { SurfaceView(context) }
    val inputSurface = remember { WarpnectInputCaptureView(context) }
    DisposableEffect(renderSurface, inputSurface) {
        onAttached(renderSurface, inputSurface)
        onDispose { onDetached(renderSurface, inputSurface) }
    }
    AndroidView(
        factory = { renderSurface },
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
    )
    AndroidView(
        factory = { inputSurface },
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
    )
}
