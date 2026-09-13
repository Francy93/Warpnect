package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.util.Base64;

/** DEBUG-only normal-app controller for the DirectShell SharedMemory descriptor experiment. */
@TargetApi(Build.VERSION_CODES.S)
public final class DirectShellFdProbeActivity extends Activity {
    static final String EXTRA_PORT = "port";
    static final String EXTRA_SECRET_BASE64 = "secretBase64";
    static final String EXTRA_GENERATION_ID = "generationId";
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_RUN_ID = "runId";

    static final String MODE_ARM = "arm";
    static final String MODE_NEGATIVE_NORMAL_CALLER = "negative-normal-caller";
    static final String MODE_SERVER_BIND = "server-bind";
    static final String MODE_SERVER_VERIFY_A = "server-verify-a";
    static final String MODE_CLOSE_ORIGINAL = "close-original";
    static final String MODE_SERVER_WRITE_B = "server-write-b";
    static final String MODE_VERIFY_B = "verify-b";
    static final String MODE_WRITE_C = "write-c";
    static final String MODE_SERVER_VERIFY_C = "server-verify-c";
    static final String MODE_SERVER_NEGATIVE_STALE = "server-negative-stale";
    static final String MODE_SERVER_NEGATIVE_MAGIC = "server-negative-magic";
    static final String MODE_SERVER_NEGATIVE_SIZE = "server-negative-size";
    static final String MODE_SERVER_NEGATIVE_REPLAY = "server-negative-replay";
    static final String MODE_SERVER_NEGATIVE_PROOF = "server-negative-proof";
    static final String MODE_SERVER_CLOSE = "server-close";
    static final String MODE_FINISH = "finish";
    static final String MODE_SHUTDOWN = "shutdown";

    private static final String TAG = "WarpnectDirectShellFd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dispatch();
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatch();
        finish();
    }

    private void dispatch() {
        Intent request = new Intent(getIntent());
        new Thread(
                () -> complete(execute(request)),
                "WarpnectDirectShellFdController"
        ).start();
    }

    private Result execute(Intent intent) {
        String runId = safeRunId(intent.getStringExtra(EXTRA_RUN_ID));
        String mode = intent.getStringExtra(EXTRA_MODE);
        long generationId = intent.getLongExtra(EXTRA_GENERATION_ID, -1L);
        String encodedSecret = intent.getStringExtra(EXTRA_SECRET_BASE64);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        if (mode == null || generationId <= 0L || encodedSecret == null) {
            return Result.failure(runId, mode, "INVALID_ARGUMENT");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(runId, mode, "UNSUPPORTED_PLATFORM_API31_REQUIRED");
        }
        byte[] secret;
        try {
            secret = DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(encodedSecret));
        } catch (IllegalArgumentException exception) {
            return Result.failure(runId, mode, "INVALID_SECRET");
        }
        try {
            switch (mode) {
                case MODE_ARM:
                    DirectShellFdProbeSession.Result armed = DirectShellFdProbeSession.instance().arm(
                            generationId,
                            secret
                    );
                    return result(runId, mode, armed);
                case MODE_NEGATIVE_NORMAL_CALLER:
                    String negative = DirectShellFdProbeLocalProviderClient.verifyNormalAppCallerRejected(
                            this,
                            generationId,
                            secret
                    );
                    return "CALLER_UID_REJECTED".equals(negative)
                            ? Result.success(runId, mode, negative)
                            : Result.failure(runId, mode, negative);
                case MODE_CLOSE_ORIGINAL:
                    return result(runId, mode, DirectShellFdProbeSession.instance().closeOriginal(generationId));
                case MODE_VERIFY_B:
                    return result(runId, mode, DirectShellFdProbeSession.instance().verifyShellB(generationId));
                case MODE_WRITE_C:
                    return result(runId, mode, DirectShellFdProbeSession.instance().writeAppC(generationId));
                case MODE_FINISH:
                    DirectShellFdProbeSession.Result finished = DirectShellFdProbeSession.instance().finish(generationId);
                    return result(runId, mode, finished);
                case MODE_SERVER_BIND:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_BIND_SHARED_MEMORY, true);
                case MODE_SERVER_VERIFY_A:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_VERIFY_APP_A, true);
                case MODE_SERVER_WRITE_B:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_WRITE_SHELL_B, true);
                case MODE_SERVER_VERIFY_C:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_VERIFY_APP_C, true);
                case MODE_SERVER_NEGATIVE_STALE:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_REQUEST_STALE_GENERATION, true);
                case MODE_SERVER_NEGATIVE_MAGIC:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_REQUEST_INVALID_MAGIC, true);
                case MODE_SERVER_NEGATIVE_SIZE:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_REQUEST_OVERSIZED_METADATA, true);
                case MODE_SERVER_NEGATIVE_REPLAY:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_REQUEST_REPLAY, true);
                case MODE_SERVER_NEGATIVE_PROOF:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_REQUEST_INVALID_PROOF, true);
                case MODE_SERVER_CLOSE:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.FD_CLOSE_SHARED_MEMORY, true);
                case MODE_SHUTDOWN:
                    return serverCommand(runId, mode, port, secret,
                            DirectShellProbeProtocol.Command.SHUTDOWN, true);
                default:
                    return Result.failure(runId, mode, "INVALID_MODE");
            }
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    private Result serverCommand(
            String runId,
            String mode,
            int port,
            byte[] secret,
            DirectShellProbeProtocol.Command command,
            boolean requireOk
    ) {
        if (port < 1024 || port > 65535) return Result.failure(runId, mode, "INVALID_PORT");
        try (DirectShellFdProbeClient client = DirectShellFdProbeClient.connectLoopback(port, secret)) {
            DirectShellFdProbeClient.Response response = client.call(command);
            boolean success = !requireOk || response.status == DirectShellProbeProtocol.Status.OK;
            return success
                    ? Result.success(runId, mode, response.status.name() + " " + response.payload)
                    : Result.failure(runId, mode, response.status.name() + " " + response.payload);
        } catch (Exception exception) {
            return Result.failure(runId, mode, "CONTROL_FAILURE_" + exception.getClass().getSimpleName());
        }
    }

    private Result result(String runId, String mode, DirectShellFdProbeSession.Result result) {
        return result.success ? Result.success(runId, mode, result.payload) : Result.failure(runId, mode, result.payload);
    }

    private void complete(Result result) {
        String message = "run_id=" + result.runId + " DIRECT_SHELL_FD_CLIENT_RESULT command=" +
                safe(result.command) + " result=" + (result.success ? "OK" : "FAIL") +
                " payload=" + safe(result.payload);
        Log.i(TAG, message);
    }

    private static String safeRunId(String runId) {
        return runId != null && runId.matches("[A-Za-z0-9_.-]{1,96}") ? runId : "none";
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', ';').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 2_500 ? sanitized : sanitized.substring(0, 2_500) + "...";
    }

    private static final class Result {
        final String runId;
        final String command;
        final boolean success;
        final String payload;

        private Result(String runId, String command, boolean success, String payload) {
            this.runId = runId;
            this.command = command;
            this.success = success;
            this.payload = payload;
        }

        static Result success(String runId, String command, String payload) {
            return new Result(runId, command, true, payload);
        }

        static Result failure(String runId, String command, String payload) {
            return new Result(runId, command, false, payload);
        }
    }
}
