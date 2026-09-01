package io.warpnect.platform.capture.experimental

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.warpnect.BuildConfig
import java.io.File
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
    private var userServiceConnection: ServiceConnection? = null
    private var codecServiceConnection: ServiceConnection? = null
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
                    if (probe == ExperimentalDisplayMirrorProbeKind.SplitProcessLegacyFrame) {
                        runSplitProcessLegacyFrame()
                    } else {
                        runUserServiceProbe(probe)
                    }
                }
            }
            result.putString(KEY_CLIENT_REVISION, CLIENT_REVISION)
            persistResult(runId, probe, result)
            logResult(runId, probe, result)
            unbindServices()
            finish()
        }
    }

    override fun onDestroy() {
        unbindServices()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runUserServiceProbe(probe: ExperimentalDisplayMirrorProbeKind): Bundle {
        val service = bindUserService() ?: return Bundle().apply {
            putString(KEY_CLIENT_FAILURE, "UserServiceBindFailed")
        }
        return runCatching { service.runProbe(probe.code) }
            .getOrElse { throwable ->
                Bundle().apply { putString(KEY_CLIENT_FAILURE, remoteFailureReason(throwable)) }
            }
    }

    private suspend fun runSplitProcessLegacyFrame(): Bundle {
        val mirrorService = bindUserService() ?: return Bundle().apply {
            putString(KEY_CLIENT_FAILURE, "UserServiceBindFailed")
        }
        val codecService = bindCodecService() ?: return Bundle().apply {
            putString(KEY_CLIENT_FAILURE, "CodecServiceBindFailed")
        }
        var result = Bundle()
        try {
            result = codecService.runSplitProcessLegacyFrame(
                mirrorService.asBinder(),
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.R,
            )
        } catch (throwable: Throwable) {
            result = Bundle().apply {
                putString(KEY_CLIENT_FAILURE, codecServiceFailureReason(throwable))
            }
        } finally {
            // The app-owned codec process may abort natively; the Activity retains the mirror binder.
            val mirrorCleanup = runCatching { mirrorService.stopLegacyMirror() }
                .getOrNull()
                ?.getBoolean(KEY_CLEANUP_SUCCEEDED, false) == true
            val codecCleanup = !result.containsKey(KEY_CLEANUP_SUCCEEDED) ||
                result.getBoolean(KEY_CLEANUP_SUCCEEDED, false)
            result.putBoolean(KEY_CLEANUP_SUCCEEDED, codecCleanup && mirrorCleanup)
            unbindCodecService()
            unbindUserService()
        }
        return result
    }

    private suspend fun bindUserService(): IExperimentalDisplayMirrorService? = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    userServiceConnection = this
                    if (continuation.isActive) {
                        continuation.resume(IExperimentalDisplayMirrorService.Stub.asInterface(service))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    userServiceConnection = null
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            userServiceConnection = serviceConnection
            continuation.invokeOnCancellation { unbindUserService() }
            runCatching { Shizuku.bindUserService(args, serviceConnection) }
                .onFailure {
                    userServiceConnection = null
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }

    private suspend fun bindCodecService(): IExperimentalAppCodecService? = withTimeoutOrNull(BIND_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    codecServiceConnection = this
                    if (continuation.isActive) {
                        continuation.resume(IExperimentalAppCodecService.Stub.asInterface(service))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            codecServiceConnection = serviceConnection
            continuation.invokeOnCancellation { unbindCodecService() }
            val intent = Intent(
                this@ExperimentalDisplayMirrorActivity,
                ExperimentalAppCodecService::class.java,
            )
            if (!bindService(intent, serviceConnection, BIND_AUTO_CREATE)) {
                codecServiceConnection = null
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun unbindUserService() {
        userServiceConnection?.let { current ->
            runCatching { Shizuku.unbindUserService(args, current, true) }
        }
        userServiceConnection = null
    }

    private fun unbindCodecService() {
        codecServiceConnection?.let { current -> runCatching { unbindService(current) } }
        codecServiceConnection = null
    }

    private fun unbindServices() {
        unbindCodecService()
        unbindUserService()
    }

    private fun isShizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun remoteFailureReason(throwable: Throwable): String = when (throwable) {
        is android.os.DeadObjectException -> "UserServiceDied"
        is android.os.TransactionTooLargeException -> "UserServiceResultTooLarge"
        is android.os.RemoteException -> "UserServiceRemoteException"
        is SecurityException -> "UserServicePermissionDenied"
        else -> "ProbeRemoteFailure"
    }

    private fun codecServiceFailureReason(throwable: Throwable): String = when (throwable) {
        is android.os.DeadObjectException -> "CodecOwnerProcessDied"
        is android.os.RemoteException -> "CodecServiceRemoteException"
        else -> "CodecServiceFailure"
    }

    private fun logResult(runId: String, probe: ExperimentalDisplayMirrorProbeKind, result: Bundle) {
        val fields = safeFields(result)
        val event = listOf(
            "event=capture_experiment_result",
            "run=$runId",
            "probe=${probe.name}",
        ) + fields
        Log.i(TAG, event.joinToString(" "))
    }

    /** The harness reads this private, safe-only result instead of polling device-wide logcat. */
    private fun persistResult(runId: String, probe: ExperimentalDisplayMirrorProbeKind, result: Bundle) {
        val lines = listOf(
            "event=capture_experiment_result",
            "run=$runId",
            "probe=${probe.name}",
        ) + safeFields(result)
        val directory = File(cacheDir, RESULT_DIRECTORY)
        check(directory.exists() || directory.mkdirs())
        File(directory, "$runId.txt").writeText(lines.joinToString("\n"))
    }

    private fun safeFields(result: Bundle): List<String> = SAFE_KEYS.mapNotNull { key ->
        if (result.containsKey(key)) "$key=${result.get(key)}" else null
    }

    @VisibleForTesting
    internal fun isSafeRunId(value: String): Boolean = value.matches(RUN_ID_PATTERN)

    private companion object {
        const val TAG = "WarpnectCaptureExperiment"
        const val EXTRA_RUN_ID = "io.warpnect.capture.experiment.RUN_ID"
        const val EXTRA_PROBE_KIND = "io.warpnect.capture.experiment.PROBE_KIND"

        // Shizuku uses this value to recreate a UserService after its debug bytecode changes.
        const val SERVICE_VERSION = 20
        const val SERVICE_TAG = "capture-experiment-v2-reflection"
        const val CLIENT_REVISION = "activity-v2-split-process-mirror-1"
        const val BIND_TIMEOUT_MS = 5_000L
        const val RESULT_DIRECTORY = "capture-experiment"
        val RUN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")
        val SAFE_KEYS = listOf(
            "uid",
            "identity_mode",
            "selinux_context",
            "probe_revision",
            "display_manager_service_available",
            "display_0_available",
            "modern_mirror_method_shapes",
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
            "mirror_created",
            "capture_started",
            "encoder_stage",
            "encoder_configured",
            "encoder_started",
            "first_real_frame_encoded",
            "first_frame_elapsed_ms",
            "release_succeeded",
            "failure",
            "failure_stage",
            "failure_origin",
            "avc_encoder_count",
            "avc_encoder_inventory",
            "video_encoder_available",
            "video_encoder_reason",
            "video_selected_codec",
            "video_probe_failure",
            "video_metadata_only",
            "input_api_resolved",
            "input_async_supported",
            "input_display_supported",
            "input_available",
            "input_reason",
            "legacy_surfacecontrol_class_available",
            "legacy_display_manager_global_class_available",
            "legacy_display_info_class_available",
            "legacy_create_display_available",
            "legacy_destroy_display_available",
            "legacy_set_display_surface_available",
            "legacy_set_display_projection_available",
            "legacy_set_display_layer_stack_available",
            "legacy_get_instance_available",
            "legacy_get_display_info_available",
            "legacy_logical_width_field_available",
            "legacy_logical_height_field_available",
            "legacy_rotation_field_available",
            "legacy_method_parameter_count",
            "legacy_argument_count",
            "legacy_argument_count_match",
            "legacy_argument_types_match",
            "legacy_arg_0_assignable",
            "legacy_arg_1_assignable",
            "legacy_create_display_stage",
            "legacy_reflection_invocation_accepted",
            "legacy_create_display_outcome",
            "legacy_direct_token_returned",
            "legacy_direct_destroy_succeeded",
            "legacy_configuration_attempted",
            "legacy_configuration_create_outcome",
            "legacy_configuration_display_info_available",
            "legacy_configuration_surface_result",
            "legacy_configuration_layer_stack_result",
            "legacy_configuration_projection_result",
            "legacy_configuration_release_succeeded",
            "legacy_compatibility_methods_resolved",
            "legacy_compatibility_arguments_assignable",
            "legacy_compatibility_surface_valid",
            "legacy_compatibility_surface_released",
            "legacy_compatibility_matrix",
            "legacy_compatibility_release_succeeded",
            "legacy_vbr_advertised",
            "legacy_vbr_codec",
            "legacy_vbr_secure",
            "legacy_vbr_mirror_outcome",
            "capture_backend_frame_proof_only",
            "codec_owner_process",
            "codec_owner_uid",
            "mirror_owner_process",
            "mirror_owner_uid",
            "app_codec_configured",
            "app_codec_input_surface_created",
            "app_codec_started",
            "surface_transfer_accepted",
            "legacy_mirror_outcome",
            "cleanup_succeeded",
            "legacy_surfacecontrol_available",
            "legacy_resolution_error",
            "legacy_start_error",
            "legacy_display_created",
            "legacy_surface_attached",
            "legacy_release_succeeded",
            "legacy_first_real_frame_encoded",
            "legacy_first_frame_elapsed_ms",
            KEY_CLIENT_FAILURE,
            KEY_CLIENT_REVISION,
        )
        const val KEY_CLIENT_FAILURE = "client_failure"
        const val KEY_CLIENT_REVISION = "client_revision"
        const val KEY_CLEANUP_SUCCEEDED = "cleanup_succeeded"
    }
}
