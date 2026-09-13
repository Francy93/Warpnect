package io.warpnect.debug.directshell;

import android.content.Intent;

import java.io.IOException;
import java.util.Base64;

/** Parsed, bounded command for the DEBUG-only DirectShell SystemAudio probe. */
final class DirectShellSystemAudioProbeRequest {
    private DirectShellSystemAudioProbeRequest() {}

    static Result execute(Intent intent) {
        int port = intent.getIntExtra(DirectShellSystemAudioProbeActivity.EXTRA_PORT, -1);
        String encodedSecret = intent.getStringExtra(DirectShellSystemAudioProbeActivity.EXTRA_SECRET_BASE64);
        String mode = intent.getStringExtra(DirectShellSystemAudioProbeActivity.EXTRA_MODE);
        String runId = safeRunId(intent.getStringExtra(DirectShellSystemAudioProbeActivity.EXTRA_RUN_ID));
        int sampleDurationMillis = intent.getIntExtra(
                DirectShellSystemAudioProbeActivity.EXTRA_SAMPLE_DURATION_MILLIS,
                1_500
        );
        if (port < 1024 || port > 65535 || encodedSecret == null) {
            return new Result(runId, "INVALID_ARGUMENT", "none", "none");
        }
        byte[] secret;
        try {
            secret = DirectShellProbeProtocol.requireSecret(Base64.getUrlDecoder().decode(encodedSecret));
        } catch (IllegalArgumentException exception) {
            return new Result(runId, "INVALID_SECRET", "none", "none");
        }
        CommandSelection selection = CommandSelection.from(mode, sampleDurationMillis);
        if (selection == null) {
            return new Result(runId, "INVALID_MODE", "none", "none");
        }
        try (DirectShellSystemAudioProbeClient client =
                     DirectShellSystemAudioProbeClient.connectLoopback(port, secret)) {
            DirectShellSystemAudioProbeClient.Response response = client.call(
                    selection.command,
                    selection.payload
            );
            return new Result(
                    runId,
                    response.status.name(),
                    selection.command.name(),
                    response.payload
            );
        } catch (IOException | DirectShellProbeProtocol.ProtocolException | RuntimeException exception) {
            return new Result(
                    runId,
                    "TRANSPORT_FAILURE_" + exception.getClass().getSimpleName(),
                    selection.command.name(),
                    "none"
            );
        }
    }

    static final class Result {
        final String runId;
        final String status;
        final String command;
        final String payload;

        Result(String runId, String status, String command, String payload) {
            this.runId = runId;
            this.status = status;
            this.command = command;
            this.payload = payload;
        }
    }

    private static final class CommandSelection {
        final DirectShellProbeProtocol.Command command;
        final String payload;

        CommandSelection(DirectShellProbeProtocol.Command command, String payload) {
            this.command = command;
            this.payload = payload;
        }

        static CommandSelection from(String mode, int sampleDurationMillis) {
            if (DirectShellSystemAudioProbeActivity.MODE_PREPARE.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.AUDIO_PREPARE,
                    ""
            );
            if (DirectShellSystemAudioProbeActivity.MODE_START.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.AUDIO_START,
                    ""
            );
            if (DirectShellSystemAudioProbeActivity.MODE_SAMPLE.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.AUDIO_SAMPLE,
                    "duration_ms=" + Math.max(100, Math.min(5_000, sampleDurationMillis))
            );
            if (DirectShellSystemAudioProbeActivity.MODE_STOP.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.AUDIO_STOP,
                    ""
            );
            if (DirectShellSystemAudioProbeActivity.MODE_DIAGNOSTICS.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.GET_AUDIO_DIAGNOSTICS,
                    ""
            );
            if (DirectShellSystemAudioProbeActivity.MODE_SHUTDOWN.equals(mode)) return new CommandSelection(
                    DirectShellProbeProtocol.Command.SHUTDOWN,
                    ""
            );
            return null;
        }
    }

    private static String safeRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9_.-]{1,96}")) return "none";
        return runId;
    }
}
