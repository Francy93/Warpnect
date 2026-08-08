package io.warpnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.warpnect.platform.capture.AndroidVideoCaptureController
import io.warpnect.platform.video.encoder.AndroidMediaCodecVideoEncoder
import io.warpnect.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val orchestrator: CoreOrchestrator by lazy {
        CoreOrchestrator(
            transmitterVideoCaptureController = AndroidVideoCaptureController(applicationContext),
            transmitterVideoEncoderController = AndroidMediaCodecVideoEncoder(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WarpnectApp(orchestrator = orchestrator)
        }
    }

    override fun onDestroy() {
        orchestrator.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun WarpnectApp(orchestrator: CoreOrchestrator) {
    val role by orchestrator.role.collectAsState()

    MaterialTheme {
        Surface {
            MainScreen(
                role = role,
                onIdleSelected = orchestrator::enterIdle,
                onReceiverSelected = orchestrator::enterReceiverMode,
                onTransmitterSelected = orchestrator::enterTransmitterMode,
                modifier = Modifier,
            )
        }
    }
}
