package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.warpnect.platform.audio.capture.DebugSystemAudioCaptureOverrideRegistry
import java.util.Base64

/**
 * DEBUG-only command surface that arms the explicit DirectShell SystemAudio session source.
 * It never transports PCM and never changes production capability behavior outside this process.
 */
class DirectShellSystemAudioRemoteE2eReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val request = Intent(intent)
        Thread(
            {
                try {
                    log(execute(request))
                } finally {
                    pending.finish()
                }
            },
            "WarpnectDirectShellRemoteE2eControl",
        ).start()
    }

    private fun execute(intent: Intent): Result {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: return Result.failure("none", "invalid", "missing_mode")
        val runId = safeRunId(intent.getStringExtra(EXTRA_RUN_ID))
        return when (mode) {
            MODE_ARM -> arm(runId, intent)
            MODE_STATUS -> status(runId)
            MODE_DISARM -> disarm(runId)
            else -> Result.failure(runId, mode, "invalid_mode")
        }
    }

    private fun arm(runId: String, intent: Intent): Result {
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val generationId = intent.getLongExtra(EXTRA_GENERATION_ID, -1L)
        val encodedSecret = intent.getStringExtra(EXTRA_SECRET_BASE64)
            ?: return Result.failure(runId, MODE_ARM, "missing_secret")
        val secret = try {
            Base64.getUrlDecoder().decode(encodedSecret)
        } catch (_: IllegalArgumentException) {
            return Result.failure(runId, MODE_ARM, "invalid_secret")
        }
        val candidate = DirectShellSystemAudioRemoteE2eSource.create(port, generationId, secret)
            ?: return Result.failure(runId, MODE_ARM, "invalid_configuration")
        val probe = candidate.probe()
        if (!probe.available) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "server_unavailable_${probe.error.name}_${probe.detail}")
        }
        val previous = active
        if (!DebugSystemAudioCaptureOverrideRegistry.install(candidate)) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "debug_override_disabled")
        }
        active = candidate
        previous?.clear()
        return Result.success(runId, MODE_ARM, "armed ${candidate.describe()} ${probe.detail}")
    }

    private fun status(runId: String): Result {
        val current = active ?: return Result.failure(runId, MODE_STATUS, "not_armed")
        val probe = current.probe()
        return if (probe.available) {
            Result.success(runId, MODE_STATUS, "armed ${current.describe()} ${probe.detail}")
        } else {
            Result.failure(runId, MODE_STATUS, "server_unavailable_${probe.error.name}_${probe.detail}")
        }
    }

    private fun disarm(runId: String): Result {
        val current = active
        DebugSystemAudioCaptureOverrideRegistry.clear(current)
        active = null
        current?.clear()
        return Result.success(runId, MODE_DISARM, "disarmed")
    }

    private fun log(result: Result) {
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_REMOTE_E2E_DIAGNOSTICS command=${result.command} " +
                "${safe(result.detail)}",
        )
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_REMOTE_E2E_RESULT command=${result.command} " +
                "result=${if (result.success) "OK" else "FAIL"}",
        )
    }

    private fun safeRunId(value: String?): String =
        value?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) } ?: "none"

    private fun safe(value: String): String = value
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(MAX_LOG_CHARS)

    private data class Result(
        val runId: String,
        val command: String,
        val success: Boolean,
        val detail: String,
    ) {
        companion object {
            fun success(runId: String, command: String, detail: String) = Result(runId, command, true, detail)
            fun failure(runId: String, command: String, detail: String) = Result(runId, command, false, detail)
        }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellE2e"
        const val MAX_LOG_CHARS = 1_200

        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET_BASE64 = "secretBase64"
        const val EXTRA_GENERATION_ID = "generationId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUN_ID = "runId"

        const val MODE_ARM = "arm"
        const val MODE_STATUS = "status"
        const val MODE_DISARM = "disarm"

        @Volatile
        var active: DirectShellSystemAudioRemoteE2eSource? = null
    }
}
