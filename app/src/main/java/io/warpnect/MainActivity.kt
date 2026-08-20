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
    var developerManual by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface {
            if (composition == null) {
                SecureSessionUnavailableScreen(onDeveloperManual = { developerManual = true })
            } else if (developerManual) {
                val role by composition.coreOrchestrator.role.collectAsState()
                MainScreen(
                    role = role,
                    onIdleSelected = composition.coreOrchestrator::enterIdle,
                    onReceiverSelected = composition.coreOrchestrator::enterReceiverMode,
                    onTransmitterSelected = composition.coreOrchestrator::enterTransmitterMode,
                    onBackToSecureSession = { developerManual = false },
                    modifier = Modifier,
                )
            } else {
                SecureSessionScreen(
                    controller = composition.applicationController,
                    onClientViewsAttached = composition.uiResources::attachClientViews,
                    onClientViewsDetached = composition.uiResources::clearClientViews,
                    onDeveloperManual = { developerManual = true },
                    modifier = Modifier,
                )
            }
        }
    }
}

@Composable
private fun SecureSessionUnavailableScreen(onDeveloperManual: () -> Unit) {
    androidx.compose.foundation.layout.Column {
        Text("Warpnect secure Session services are unavailable")
        OutlinedButton(onClick = onDeveloperManual) { Text("Developer Manual") }
    }
}
