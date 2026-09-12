package io.warpnect.debug.directshell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEBUG-only normal-app harness for a manually launched app_process probe server.
 *
 * <p>The Activity never starts a shell process. A developer-controlled ADB command starts the
 * server, while this normal application process proves cross-UID local authentication and
 * request ordering.</p>
 */
public final class DirectShellProbeActivity extends Activity {
    private static final String TAG = "WarpnectDirectShell";

    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_SECRET_BASE64 = "secretBase64";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RUN_ID = "runId";
    public static final String EXTRA_REQUEST_SHUTDOWN = "requestShutdown";
    public static final String MODE_AUTHENTICATED = "authenticated";
    public static final String MODE_UNAUTHENTICATED = "unauthenticated";

    private final AtomicLong probeGeneration = new AtomicLong();
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("Warpnect DirectShell bootstrap probe");
        setContentView(status);
        runProbe();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runProbe();
    }

    private void runProbe() {
        long generation = probeGeneration.incrementAndGet();
        int port = getIntent().getIntExtra(EXTRA_PORT, -1);
        String encodedSecret = getIntent().getStringExtra(EXTRA_SECRET_BASE64);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        String runId = getIntent().getStringExtra(EXTRA_RUN_ID);
        boolean requestShutdown = getIntent().getBooleanExtra(EXTRA_REQUEST_SHUTDOWN, true);
        if (port < 1024 || port > 65535 || encodedSecret == null) {
            complete(generation, runId, "DIRECT_SHELL_CLIENT_RESULT result=INVALID_ARGUMENT");
            return;
        }
        byte[] secret;
        try {
            secret = DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(encodedSecret));
        } catch (IllegalArgumentException exception) {
            complete(generation, runId, "DIRECT_SHELL_CLIENT_RESULT result=INVALID_SECRET");
            return;
        }
        String selectedMode = mode == null ? MODE_AUTHENTICATED : mode;
        new Thread(
                () -> executeProbe(generation, runId, port, secret, selectedMode, requestShutdown),
                "WarpnectDirectShellProbeClient"
        ).start();
    }

    private void executeProbe(
            long generation,
            String runId,
            int port,
            byte[] secret,
            String mode,
            boolean requestShutdown
    ) {
        String result;
        if (MODE_UNAUTHENTICATED.equals(mode)) {
            result = runNegativeAuthenticationProbe(port, secret);
        } else if (MODE_AUTHENTICATED.equals(mode)) {
            result = runAuthenticatedProbe(port, secret, requestShutdown);
        } else {
            result = "DIRECT_SHELL_CLIENT_RESULT result=INVALID_MODE";
        }
        complete(generation, runId, result);
    }

    private String runAuthenticatedProbe(int port, byte[] secret, boolean requestShutdown) {
        try (DirectShellProbeClient client = DirectShellProbeClient.connectLoopback(port, secret)) {
            DirectShellProbeClient.ProbeRunResult result = client.runAuthenticatedSequence(requestShutdown);
            Log.i(
                    TAG,
                    "DIRECT_SHELL_CLIENT_RUNTIME_INFO request_id=" + result.runtimeInfoRequestId +
                            " payload=" + sanitizeRuntimeInfo(result.runtimeInfo)
            );
            return "DIRECT_SHELL_CLIENT_RESULT result=SUCCESS ping_id=" + result.pingRequestId +
                    " runtime_id=" + result.runtimeInfoRequestId +
                    " shutdown_id=" + result.shutdownRequestId;
        } catch (IOException | DirectShellProbeProtocol.ProtocolException | RuntimeException exception) {
            return "DIRECT_SHELL_CLIENT_RESULT result=FAILURE type=" +
                    exception.getClass().getSimpleName() + " message=" + safeMessage(exception);
        }
    }

    private String runNegativeAuthenticationProbe(int port, byte[] secret) {
        try {
            DirectShellProbeClient.NegativeAuthResult result =
                    DirectShellProbeClient.verifyUnauthenticatedRequestRejected(port, secret);
            return "DIRECT_SHELL_CLIENT_NEGATIVE_AUTH result=" + result.name();
        } catch (IOException | RuntimeException exception) {
            return "DIRECT_SHELL_CLIENT_NEGATIVE_AUTH result=TRANSPORT_FAILURE type=" +
                    exception.getClass().getSimpleName() + " message=" + safeMessage(exception);
        }
    }

    private void complete(long generation, String runId, String result) {
        Log.i(TAG, "run_id=" + safeRunId(runId) + " " + result);
        runOnUiThread(
                () -> {
                    if (generation != probeGeneration.get()) return;
                    status.setText(result);
                    status.postDelayed(
                            () -> {
                                if (generation == probeGeneration.get()) finish();
                            },
                            300L
                    );
                }
        );
    }

    private static String sanitizeRuntimeInfo(String payload) {
        String safe = payload.replace('\n', ';').replace('\r', '_');
        return safe.length() <= 1_500 ? safe : safe.substring(0, 1_500) + "...";
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null) return "none";
        String safe = message.replace('\n', '_').replace('\r', '_');
        return safe.length() <= 256 ? safe : safe.substring(0, 256) + "...";
    }

    private static String safeRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,96}")) return "none";
        return runId;
    }
}
