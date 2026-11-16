package io.warpnect.debug.directshell;

import java.io.IOException;

/** Authenticated policy identity and consumer window, never PCM or an admission credit. */
final class DirectShellAppMicrophoneState {
    final String registration;
    final long sinceNs;
    final long closeEpoch;

    private DirectShellAppMicrophoneState(String registration, long sinceNs, long closeEpoch) {
        this.registration = registration;
        this.sinceNs = sinceNs;
        this.closeEpoch = closeEpoch;
    }

    static DirectShellAppMicrophoneState parse(String payload, long generation) throws IOException {
        String[] fields = payload.split(";", -1);
        try {
            if (fields.length != 5 || !fields[0].equals("APP_V1") || generation <= 0 ||
                    Long.parseLong(fields[1]) != generation || fields[2].isEmpty() || fields[2].length() > 256 ||
                    fields[2].chars().anyMatch(c -> c < 32) ||
                    Long.parseLong(fields[3]) < 0 || Long.parseLong(fields[4]) < 0) {
                throw new IOException("app_microphone_state_invalid");
            }
            return new DirectShellAppMicrophoneState(fields[2], Long.parseLong(fields[3]), Long.parseLong(fields[4]));
        } catch (NumberFormatException invalid) {
            throw new IOException("app_microphone_state_invalid", invalid);
        }
    }

    void requireContinuation(DirectShellAppMicrophoneState previous) throws IOException {
        if (!registration.equals(previous.registration) || closeEpoch < previous.closeEpoch ||
                (closeEpoch == previous.closeEpoch && previous.sinceNs > 0 && sinceNs != previous.sinceNs)) {
            throw new IOException("app_microphone_policy_or_window_changed");
        }
    }
}
