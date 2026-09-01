package io.warpnect.platform.capture.experimental

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.warpnect.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Debug-only ADB entry point for isolated Shizuku DisplayManager capture experiments. It reports
 * only safe booleans/enums and immediately finishes after the bounded probe.
 */
class ExperimentalDisplayMirrorActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: ServiceConnection? = null
    private lateinit var args: Shizuku.UserServiceArgs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.takeIf(::isSafeRunId) ?: "invalid"
        val probe = ExperimentalDisplayMirrorProbeKind.fromCode(
            intent.getIntExtra(EXTRA_PROBE_KIND, ExperimentalDisplayMirrorProbeKind.Resolution.code),
        )
        args = Shizuku.UserServiceArgs(
            ComponentName(packageName, ExperimentalDisplayMirrorUserServiceV2::class.java.name),
        )
            .daemon(false)
            .debuggable(BuildConfig.DEBUG)
            .processNameSuffix("capture-experiment-v2")
            .tag(SERVICE_TAG)
            .version(SERVICE_VERSION)
        scope.launch {
            val result = if (!isShizukuReady()) {
                Bundle().apply { putString(KEY_CLIENT_FAILURE, "ShizukuUnavailableOrPermissionRequired") }
            } else {
                withContext(Dispatchers.Default) {
                    val service = bindService() ?: return@withContext Bundle().apply {
                        putString(KEY_CLIENT_FAILURE, "UserServiceBindFailed")
                    }
                    runCatching { service.runProbe(probe.code) }
                        .getOrElse { Bundle().apply { putString(KEY_CLIENT_FAILURE, "ProbeRemoteFailure") } }
                }
            }
            result.putString(KEY_CLIENT_REVISION, CLIENT_REVISION)
            logResult(runId, probe, result)
            unbindService()
            finish()
        }
    }

    override fun onDestroy() {
        unbindService()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun bindService(): IExperimentalDisplayMirrorService? = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connection = this
                    if (continuation.isActive) {
                        continuation.resume(IExperimentalDisplayMirrorService.Stub.asInterface(service))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    connection = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            connection = serviceConnection
            continuation.invokeOnCancellation { unbindService() }
            runCatching { Shizuku.bindUserService(args, serviceConnection) }
                .onFailure {
                    connection = null
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    private fun unbindService() {
        connection?.let { current ->
            runCatching { Shizuku.unbindUserService(args, current, true) }
        }
        connection = null
    }

    private fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun logResult(runId: String, probe: ExperimentalDisplayMirrorProbeKind, result: Bundle) {
        val fields = SAFE_KEYS.mapNotNull { key ->
            when {
                result.containsKey(key) -> "$key=${result.get(key)}"
                else -> null
            }
        }
        val event = listOf(
            "event=capture_experiment_result",
            "run=$runId",
            "probe=${probe.name}",
        ) + fields
        Log.i(TAG, event.joinToString(" "))
    }

    @VisibleForTesting
    internal fun isSafeRunId(value: String): Boolean = value.matches(RUN_ID_PATTERN)

    private companion object {
        const val TAG = "WarpnectCaptureExperiment"
        const val EXTRA_RUN_ID = "io.warpnect.capture.experiment.RUN_ID"
        const val EXTRA_PROBE_KIND = "io.warpnect.capture.experiment.PROBE_KIND"

        // Shizuku uses this value to recreate a UserService after its debug bytecode changes.
        const val SERVICE_VERSION = 9
        const val SERVICE_TAG = "capture-experiment-v2-reflection"
        const val CLIENT_REVISION = "activity-v2-descriptor-1"
        const val BIND_TIMEOUT_MS = 5_000L
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")
        val SAFE_KEYS = listOf(
            "uid",
            "identity_mode",
            "selinux_context",
            "probe_revision",
            "display_manager_service_available",
            "display_0_available",
            "mirror_method_available",
            "expected_signature_available",
            "method_parameter_count",
            "mirror_argument_count",
            "mirror_argument_count_match",
            "mirror_argument_types_match",
            "arg_0_assignable",
            "arg_1_assignable",
            "arg_2_assignable",
            "arg_3_assignable",
            "arg_4_assignable",
            "reflection_stage",
            "reflection_invocation_accepted",
            "create_mirror_display",
            "surface_attached",
            "mirror_lifecycle_succeeded",
            "capture_started",
            "encoder_started",
            "first_real_frame_encoded",
            "release_succeeded",
            "failure",
            "failure_stage",
            "failure_origin",
            KEY_CLIENT_FAILURE,
            KEY_CLIENT_REVISION,
        )
        const val KEY_CLIENT_FAILURE = "client_failure"
        const val KEY_CLIENT_REVISION = "client_revision"
    }
}
