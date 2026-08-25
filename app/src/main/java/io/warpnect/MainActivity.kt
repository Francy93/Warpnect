package io.warpnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.warpnect.diagnostics.ui.DiagnosticsRuntimeSummary
import io.warpnect.diagnostics.ui.DiagnosticsUiController
import io.warpnect.platform.diagnostics.AndroidDiagnosticsUiClock
import io.warpnect.ui.DiagnosticsScreen
import io.warpnect.ui.MainScreen
import io.warpnect.ui.SecureSessionScreen

class MainActivity : ComponentActivity() {
    private val composition: io.warpnect.platform.session.integration.AndroidSecureSessionComposition?
        get() = (application as WarpnectApplication).secureSessionComposition

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WarpnectApp(composition = composition)
        }
    }
}

@Composable
private fun WarpnectApp(composition: io.warpnect.platform.session.integration.AndroidSecureSessionComposition?) {
    var surface by remember { mutableStateOf(AppSurface.SecureSession) }

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
                SecureSessionScreen(
                    controller = composition.applicationController,
                    onClientViewsAttached = composition.uiResources::attachClientViews,
                    onClientViewsDetached = composition.uiResources::clearClientViews,
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
