package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.IOException
import java.util.Arrays
import java.util.Base64

/**
 * DEBUG-only normal-process controller that leaves the deterministic tone activity undisturbed.
 */
class DirectShellPcmRingProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val request = Intent(intent)
        Thread(
            {
                try {
                    logResult(execute(context.applicationContext, request))
                } finally {
                    pending.finish()
                }
            },
            "WarpnectDirectShellPcmRingController",
        ).start()
    }

    private fun execute(context: Context, intent: Intent): Result {
        val mode = intent.getStringExtra(EXTRA_MODE)
        val runId = safeRunId(intent.getStringExtra(EXTRA_RUN_ID))
        val generationId = intent.getLongExtra(EXTRA_GENERATION_ID, -1L)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val encodedSecret = intent.getStringExtra(EXTRA_SECRET_BASE64)
        if (mode == null || generationId <= 0L || encodedSecret == null) {
            return Result.failure(runId, mode ?: "none", "INVALID_ARGUMENT")
        }
        val secret = try {
            DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(encodedSecret))
        } catch (_: IllegalArgumentException) {
            return Result.failure(runId, mode, "INVALID_SECRET")
        }
        try {
            return when (mode) {
                MODE_ARM -> sessionResult(runId, mode, DirectShellPcmRingProbeSession.arm(generationId, secret))
                MODE_NEGATIVE_NORMAL_CALLER -> sessionResult(
                    runId,
                    mode,
                    DirectShellPcmRingProbeSession.verifyNormalCallerRejected(context, generationId),
                )
                MODE_RESET_METRICS -> sessionResult(
                    runId,
                    mode,
                    DirectShellPcmRingProbeSession.resetMetrics(generationId),
                )
                MODE_METRICS -> sessionResult(
                    runId,
                    mode,
                    DirectShellPcmRingProbeSession.metrics(generationId),
                )
                MODE_FINISH -> sessionResult(
                    runId,
                    mode,
                    DirectShellPcmRingProbeSession.finish(generationId),
                )
                MODE_SERVER_BIND -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_BIND,
                )
                MODE_SERVER_SIGNAL_TEST -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_SIGNAL_TEST,
                )
                MODE_SERVER_AUDIO_PREPARE -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_AUDIO_PREPARE,
                )
                MODE_SERVER_AUDIO_START -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_AUDIO_START,
                )
                MODE_SERVER_AUDIO_STOP -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_AUDIO_STOP,
                )
                MODE_SERVER_DIAGNOSTICS -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.GET_PCM_RING_DIAGNOSTICS,
                )
                MODE_SERVER_CLOSE -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_CLOSE,
                )
                MODE_SERVER_NEGATIVE_STALE -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_REQUEST_STALE_GENERATION,
                )
                MODE_SERVER_NEGATIVE_MAGIC -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_REQUEST_INVALID_MAGIC,
                )
                MODE_SERVER_NEGATIVE_SIZE -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_REQUEST_OVERSIZED_METADATA,
                )
                MODE_SERVER_NEGATIVE_REPLAY -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_REQUEST_REPLAY,
                )
                MODE_SERVER_NEGATIVE_PROOF -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.PCM_RING_REQUEST_INVALID_PROOF,
                )
                MODE_SHUTDOWN -> serverCommand(
                    runId,
                    mode,
                    port,
                    secret,
                    DirectShellProbeProtocol.Command.SHUTDOWN,
                )
                else -> Result.failure(runId, mode, "INVALID_MODE")
            }
        } finally {
            Arrays.fill(secret, 0.toByte())
        }
    }

    private fun sessionResult(runId: String, mode: String, result: DirectShellPcmRingProbeSession.Result): Result {
        return if (result.success) {
            Result.success(runId, mode, result.payload)
        } else {
            Result.failure(runId, mode, result.payload)
        }
    }

    private fun serverCommand(
        runId: String,
        mode: String,
        port: Int,
        secret: ByteArray,
        command: DirectShellProbeProtocol.Command,
    ): Result {
        if (port !in 1024..65535) return Result.failure(runId, mode, "INVALID_PORT")
        return try {
            DirectShellPcmRingProbeClient.connectLoopback(port, secret).use { client ->
                val response = client.call(command)
                if (response.status == DirectShellProbeProtocol.Status.OK) {
                    Result.success(runId, mode, response.status.name + " " + response.payload)
                } else {
                    Result.failure(runId, mode, response.status.name + " " + response.payload)
                }
            }
        } catch (exception: IOException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${exception.javaClass.simpleName}")
        } catch (exception: DirectShellProbeProtocol.ProtocolException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${exception.javaClass.simpleName}")
        } catch (exception: RuntimeException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${exception.javaClass.simpleName}")
        }
    }

    private fun logResult(result: Result) {
        Log.i(TAG, "run_id=${result.runId} DIRECT_SHELL_PCM_RING_CLIENT_DIAGNOSTICS ${sanitize(result.payload)}")
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_PCM_RING_CLIENT_RESULT command=${result.command} " +
                "result=${if (result.success) "OK" else "FAIL"}",
        )
    }

    private fun safeRunId(runId: String?): String =
        runId?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,96}")) } ?: "none"

    private fun sanitize(payload: String): String = payload
        .replace('\n', ';')
        .replace('\r', '_')
        .replace('\u0000', '_')
        .take(3_500)

    private data class Result(
        val runId: String,
        val command: String,
        val success: Boolean,
        val payload: String,
    ) {
        companion object {
            fun success(runId: String, command: String, payload: String) = Result(runId, command, true, payload)

            fun failure(runId: String, command: String, payload: String) = Result(runId, command, false, payload)
        }
    }

    private companion object {
        const val TAG = "WarpnectDirectShellRing"

        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET_BASE64 = "secretBase64"
        const val EXTRA_GENERATION_ID = "generationId"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUN_ID = "runId"

        const val MODE_ARM = "arm"
        const val MODE_NEGATIVE_NORMAL_CALLER = "negative-normal-caller"
        const val MODE_RESET_METRICS = "reset-metrics"
        const val MODE_METRICS = "metrics"
        const val MODE_FINISH = "finish"
        const val MODE_SERVER_BIND = "server-bind"
        const val MODE_SERVER_SIGNAL_TEST = "server-signal-test"
        const val MODE_SERVER_AUDIO_PREPARE = "server-audio-prepare"
        const val MODE_SERVER_AUDIO_START = "server-audio-start"
        const val MODE_SERVER_AUDIO_STOP = "server-audio-stop"
        const val MODE_SERVER_DIAGNOSTICS = "server-diagnostics"
        const val MODE_SERVER_CLOSE = "server-close"
        const val MODE_SERVER_NEGATIVE_STALE = "server-negative-stale"
        const val MODE_SERVER_NEGATIVE_MAGIC = "server-negative-magic"
        const val MODE_SERVER_NEGATIVE_SIZE = "server-negative-size"
        const val MODE_SERVER_NEGATIVE_REPLAY = "server-negative-replay"
        const val MODE_SERVER_NEGATIVE_PROOF = "server-negative-proof"
        const val MODE_SHUTDOWN = "shutdown"
    }
}
