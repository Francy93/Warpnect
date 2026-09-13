package io.warpnect.debug.directshell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicLong;

/** DEBUG-only normal-app controller for one authenticated shell-audio probe command. */
public final class DirectShellSystemAudioProbeActivity extends Activity {
    private static final String TAG = "WarpnectDirectShellAudio";

    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_SECRET_BASE64 = "secretBase64";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RUN_ID = "runId";
    public static final String EXTRA_SAMPLE_DURATION_MILLIS = "sampleDurationMillis";

    public static final String MODE_PREPARE = "prepare";
    public static final String MODE_START = "start";
    public static final String MODE_SAMPLE = "sample";
    public static final String MODE_STOP = "stop";
    public static final String MODE_DIAGNOSTICS = "diagnostics";
    public static final String MODE_SHUTDOWN = "shutdown";

    private final AtomicLong generation = new AtomicLong();
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setText("Warpnect DirectShell SystemAudio probe");
        setContentView(status);
        runCommand();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        runCommand();
    }

    private void runCommand() {
        long currentGeneration = generation.incrementAndGet();
        Intent requestIntent = new Intent(getIntent());
        new Thread(
                () -> execute(currentGeneration, requestIntent),
                "WarpnectDirectShellAudioClient"
        ).start();
    }

    private void execute(long currentGeneration, Intent intent) {
        DirectShellSystemAudioProbeRequest.Result result =
                DirectShellSystemAudioProbeRequest.execute(intent);
        DirectShellSystemAudioProbeReceiver.logResult(result);
        complete(currentGeneration, result.runId, result.status, result.command);
    }

    private void complete(long currentGeneration, String runId, String result, String command) {
        String message = "run_id=" + runId + " DIRECT_SHELL_AUDIO_CLIENT_RESULT command=" +
                command + " result=" + result;
        Log.i(TAG, message);
        runOnUiThread(
                () -> {
                    if (currentGeneration != generation.get()) return;
                    status.setText(message);
                    status.postDelayed(
                            () -> {
                                if (currentGeneration == generation.get()) finish();
                            },
                            300L
                    );
                }
        );
    }

}
