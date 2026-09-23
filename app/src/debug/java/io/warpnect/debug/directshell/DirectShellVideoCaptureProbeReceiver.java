package io.warpnect.debug.directshell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.IOException;
import java.util.Base64;

/** DEBUG-only app controller for the isolated DirectShell display-capture probe. */
public final class DirectShellVideoCaptureProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "WarpnectDirectShellVideo";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pendingResult = goAsync();
        Intent request = new Intent(intent);
        new Thread(() -> {
            try {
                logResult(execute(request));
            } finally {
                pendingResult.finish();
            }
        }, "WarpnectDirectShellVideoReceiver").start();
    }

    private static Result execute(Intent intent) {
        String runId = safeRunId(intent.getStringExtra("runId"));
        String mode = intent.getStringExtra("mode");
        int port = intent.getIntExtra("port", -1);
        long generationId = intent.getLongExtra("generationId", -1L);
        int displayId = intent.getIntExtra("displayId", 0);
        int width = intent.getIntExtra("width", 1280);
        int height = intent.getIntExtra("height", 720);
        boolean followRotation = intent.getBooleanExtra("followRotation", true);
        String encodedSecret = intent.getStringExtra("secretBase64");
        if (mode == null || generationId <= 0L) return Result.invalid(runId, mode, "invalid_mode_or_generation");
        byte[] secret;
        try {
            secret = DirectShellProbeProtocol.requireSecret(
                    Base64.getUrlDecoder().decode(encodedSecret == null ? "" : encodedSecret)
            );
        } catch (IllegalArgumentException exception) {
            return Result.invalid(runId, mode, "invalid_secret");
        }
        try {
            if ("arm".equals(mode)) {
                DirectShellVideoCaptureProbeSession.Result result =
                        DirectShellVideoCaptureProbeSession.arm(generationId, secret);
                return new Result(runId, mode, result.getSuccess() ? "OK" : "FAIL", result.getPayload());
            }
            if ("metrics".equals(mode)) {
                DirectShellVideoCaptureProbeSession.Result result =
                        DirectShellVideoCaptureProbeSession.metrics(generationId);
                return new Result(runId, mode, result.getSuccess() ? "OK" : "FAIL", result.getPayload());
            }
            if ("finish".equals(mode)) {
                DirectShellVideoCaptureProbeSession.Result result =
                        DirectShellVideoCaptureProbeSession.finish(generationId);
                return new Result(runId, mode, result.getSuccess() ? "OK" : "FAIL", result.getPayload());
            }
            if (port < 1024 || port > 65535) return Result.invalid(runId, mode, "invalid_port");
            DirectShellProbeProtocol.Command command = commandFor(mode);
            if (command == null) return Result.invalid(runId, mode, "invalid_command");
            String payload = (command == DirectShellProbeProtocol.Command.VIDEO_START ||
                    command == DirectShellProbeProtocol.Command.VIDEO_UPDATE)
                    ? capturePayload(displayId, width, height, followRotation)
                    : "";
            try (DirectShellVideoCaptureProbeClient client =
                         DirectShellVideoCaptureProbeClient.connectLoopback(port, secret)) {
                DirectShellVideoCaptureProbeClient.Response response = client.call(command, payload);
                return new Result(runId, mode, response.status.name(), response.payload);
            }
        } catch (IOException | DirectShellProbeProtocol.ProtocolException | RuntimeException exception) {
            return new Result(runId, mode, "TRANSPORT_FAILURE_" + exception.getClass().getSimpleName(), "none");
        }
    }

    private static DirectShellProbeProtocol.Command commandFor(String mode) {
        if ("qualify".equals(mode)) return DirectShellProbeProtocol.Command.VIDEO_QUALIFY;
        if ("start".equals(mode)) return DirectShellProbeProtocol.Command.VIDEO_START;
        if ("update".equals(mode)) return DirectShellProbeProtocol.Command.VIDEO_UPDATE;
        if ("stop".equals(mode)) return DirectShellProbeProtocol.Command.VIDEO_STOP;
        if ("diagnostics".equals(mode)) return DirectShellProbeProtocol.Command.GET_VIDEO_DIAGNOSTICS;
        if ("shutdown".equals(mode)) return DirectShellProbeProtocol.Command.SHUTDOWN;
        return null;
    }

    private static String capturePayload(int displayId, int width, int height, boolean followRotation) {
        if (displayId < 0 || width <= 0 || height <= 0) throw new IllegalArgumentException("invalid_capture_dimensions");
        return "display_id=" + displayId + ";width=" + width + ";height=" + height +
                ";follow_rotation=" + followRotation;
    }

    private static void logResult(Result result) {
        Log.i(TAG, "run_id=" + result.runId + " DIRECT_SHELL_VIDEO_CLIENT_DIAGNOSTICS " +
                sanitize(result.payload));
        Log.i(TAG, "run_id=" + result.runId + " DIRECT_SHELL_VIDEO_CLIENT_RESULT command=" +
                result.mode + " result=" + result.status);
    }

    private static String safeRunId(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,96}") ? value : "none";
    }

    private static String sanitize(String value) {
        String safe = value == null ? "none" : value
                .replace('\n', ';')
                .replace('\r', '_')
                .replace('\u0000', '_');
        return safe.length() <= 3_000 ? safe : safe.substring(0, 3_000) + "...";
    }

    private static final class Result {
        final String runId;
        final String mode;
        final String status;
        final String payload;

        Result(String runId, String mode, String status, String payload) {
            this.runId = runId;
            this.mode = mode == null ? "none" : mode;
            this.status = status;
            this.payload = payload;
        }

        static Result invalid(String runId, String mode, String detail) {
            return new Result(runId, mode, "INVALID_ARGUMENT", detail);
        }
    }
}
