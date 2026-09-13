package io.warpnect.debug.directshell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * DEBUG-only background controller that leaves the deterministic playback Activity foregrounded.
 */
public final class DirectShellSystemAudioProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "WarpnectDirectShellAudio";

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pendingResult = goAsync();
        Intent requestIntent = new Intent(intent);
        new Thread(
                () -> {
                    try {
                        DirectShellSystemAudioProbeRequest.Result result =
                                DirectShellSystemAudioProbeRequest.execute(requestIntent);
                        logResult(result);
                    } finally {
                        pendingResult.finish();
                    }
                },
                "WarpnectDirectShellAudioReceiver"
        ).start();
    }

    static void logResult(DirectShellSystemAudioProbeRequest.Result result) {
        Log.i(
                TAG,
                "run_id=" + result.runId + " DIRECT_SHELL_AUDIO_CLIENT_DIAGNOSTICS " +
                        sanitizePayload(result.payload)
        );
        Log.i(
                TAG,
                "run_id=" + result.runId + " DIRECT_SHELL_AUDIO_CLIENT_RESULT command=" +
                        result.command + " result=" + result.status
        );
    }

    private static String sanitizePayload(String payload) {
        String safe = payload == null ? "none" : payload
                .replace('\n', ';')
                .replace('\r', '_')
                .replace('\u0000', '_');
        return safe.length() <= 3_000 ? safe : safe.substring(0, 3_000) + "...";
    }
}
