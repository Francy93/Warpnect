package io.warpnect.debug.directshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.Arrays
import java.util.Base64

/** DEBUG-only normal-process controller for bounded, deterministic DirectShell input actions. */
class DirectShellInputProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val request = Intent(intent)
        Thread(
            {
                try {
                    logResult(execute(request))
                } finally {
                    pending.finish()
                }
            },
            "WarpnectDirectShellInputController",
        ).start()
    }

    private fun execute(intent: Intent): Result {
        val mode = intent.getStringExtra(EXTRA_MODE)
        val runId = safeRunId(intent.getStringExtra(EXTRA_RUN_ID))
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val encodedSecret = intent.getStringExtra(EXTRA_SECRET_BASE64)
        if (mode == null || port !in 1024..65535 || encodedSecret == null) {
            return Result.failure(runId, mode ?: "none", "INVALID_ARGUMENT")
        }
        val secret = try {
            DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(encodedSecret))
        } catch (_: IllegalArgumentException) {
            return Result.failure(runId, mode, "INVALID_SECRET")
        }
        val requestElapsedMs = SystemClock.elapsedRealtime()
        return try {
            DirectShellInputProbeClient.connectLoopback(port, secret).use { client ->
                val response = when (mode) {
                    MODE_PREPARE -> client.call(DirectShellProbeProtocol.Command.INPUT_PREPARE)
                    MODE_KEY -> client.call(DirectShellProbeProtocol.Command.INPUT_KEY_SEQUENCE, "count=1")
                    MODE_TOUCH -> client.call(DirectShellProbeProtocol.Command.INPUT_TOUCH_GESTURE, "count=1")
                    MODE_POINTER -> client.call(DirectShellProbeProtocol.Command.INPUT_POINTER)
                    MODE_JOYSTICK -> client.call(DirectShellProbeProtocol.Command.INPUT_JOYSTICK)
                    MODE_RESET -> client.call(DirectShellProbeProtocol.Command.INPUT_RESET)
                    MODE_KEY_SOAK -> client.call(
                        DirectShellProbeProtocol.Command.INPUT_KEY_SEQUENCE,
                        "count=${DirectShellInputProbeRequest.MAX_SEQUENCE_COUNT}",
                    )
                    MODE_TOUCH_SOAK -> client.call(
                        DirectShellProbeProtocol.Command.INPUT_TOUCH_GESTURE,
                        "count=${DirectShellInputProbeRequest.MAX_SEQUENCE_COUNT}",
                    )
                    MODE_DIAGNOSTICS -> client.call(DirectShellProbeProtocol.Command.GET_INPUT_DIAGNOSTICS)
                    MODE_SHUTDOWN -> client.call(DirectShellProbeProtocol.Command.SHUTDOWN)
                    else -> return Result.failure(runId, mode, "INVALID_MODE")
                }
                val elapsedMs = SystemClock.elapsedRealtime() - requestElapsedMs
                if (response.status == DirectShellProbeProtocol.Status.OK) {
                    Result.success(runId, mode, "request_elapsed_ms=$elapsedMs ${response.payload}")
                } else {
                    Result.failure(runId, mode, "request_elapsed_ms=$elapsedMs ${response.payload}")
                }
            }
        } catch (error: IOException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${error.javaClass.simpleName}")
        } catch (error: DirectShellProbeProtocol.ProtocolException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${error.javaClass.simpleName}")
        } catch (error: RuntimeException) {
            Result.failure(runId, mode, "CONTROL_FAILURE_${error.javaClass.simpleName}")
        } finally {
            Arrays.fill(secret, 0.toByte())
        }
    }

    private fun logResult(result: Result) {
        Log.i(TAG, "run_id=${result.runId} DIRECT_SHELL_INPUT_CLIENT_DIAGNOSTICS ${sanitize(result.payload)}")
        Log.i(
            TAG,
            "run_id=${result.runId} DIRECT_SHELL_INPUT_CLIENT_RESULT command=${result.command} " +
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
        const val TAG = "WarpnectDirectShellInput"

        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET_BASE64 = "secretBase64"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RUN_ID = "runId"

        const val MODE_PREPARE = "prepare"
        const val MODE_KEY = "key"
        const val MODE_TOUCH = "touch"
        const val MODE_POINTER = "pointer"
        const val MODE_JOYSTICK = "joystick"
        const val MODE_RESET = "reset"
        const val MODE_KEY_SOAK = "key-soak"
        const val MODE_TOUCH_SOAK = "touch-soak"
        const val MODE_DIAGNOSTICS = "diagnostics"
        const val MODE_SHUTDOWN = "shutdown"
    }
}
