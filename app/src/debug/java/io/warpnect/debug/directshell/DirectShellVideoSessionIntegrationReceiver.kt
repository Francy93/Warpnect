package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.warpnect.platform.capture.DebugCaptureGatewayOverrideRegistry
import java.util.Base64

/** Explicit DEBUG-only switch for the DirectShell display-capture Session integration spike. */
class DirectShellVideoSessionIntegrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val request = Intent(intent)
        Thread({
            try {
                log(execute(request))
            } finally {
                pending.finish()
            }
        }, "WarpnectDirectShellVideoSessionReceiver").start()
    }

    private fun execute(intent: Intent): Result {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) } ?: "none"
        return when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_ARM -> arm(runId, intent)
            MODE_STATUS -> status(runId)
            MODE_DISARM -> disarm(runId)
            else -> Result.failure(runId, "invalid", "invalid_mode")
        }
    }

    private fun arm(runId: String, intent: Intent): Result {
        disarm(runId)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val generation = intent.getLongExtra(EXTRA_GENERATION_ID, -1L)
        val secret = runCatching {
            Base64.getUrlDecoder().decode(intent.getStringExtra(EXTRA_SECRET_BASE64).orEmpty())
        }.getOrNull() ?: return Result.failure(runId, MODE_ARM, "invalid_secret")
        val candidate = DirectShellVideoCaptureSource.create(port, generation, secret)
            ?: return Result.failure(runId, MODE_ARM, "invalid_source_arguments")
        val probe = candidate.probe()
        if (!probe.available) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "probe=${probe.error.name} detail=${probe.detail}")
        }
        if (!DebugCaptureGatewayOverrideRegistry.install(candidate)) {
            candidate.clear()
            return Result.failure(runId, MODE_ARM, "debug_override_rejected")
        }
        active = candidate
        return Result.success(
            runId,
            MODE_ARM,
            "backend=DirectShell shizuku_video_capture=false ${candidate.describe()} ${probe.detail}",
        )
    }

    private fun status(runId: String): Result {
        val candidate = active ?: return Result.failure(runId, MODE_STATUS, "not_armed")
        val probe = candidate.probe()
        return if (probe.available) {
            Result.success(runId, MODE_STATUS, "backend=DirectShell ${candidate.describe()} ${probe.detail}")
        } else {
            Result.failure(runId, MODE_STATUS, "probe=${probe.error.name} detail=${probe.detail}")
        }
    }

    private fun disarm(runId: String): Result {
        val candidate = active
        if (candidate != null) {
            DebugCaptureGatewayOverrideRegistry.clear(candidate)
            val shutdown = candidate.shutdown()
            candidate.clear()
            active = null
            return Result.success(runId, MODE_DISARM, "backend=DirectShell $shutdown")
        }
        DebugCaptureGatewayOverrideRegistry.clear()
        return Result.success(runId, MODE_DISARM, "already_disarmed")
    }

    private fun log(result: Result) {
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_VIDEO_SESSION_DIAGNOSTICS " +
                "command=${result.command} ${safe(result.detail)}",
        )
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_VIDEO_SESSION_RESULT command=${result.command} " +
                "result=${if (result.success) "OK" else "FAIL"}",
        )
    }

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
        const val TAG = "WarpnectDirectShellVideo"
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
        var active: DirectShellVideoCaptureSource? = null
    }
}
