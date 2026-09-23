package io.warpnect.debug.directshell;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Reads one generation-scoped DirectShell secret and removes its handoff file.
 *
 * <p>This remains DEBUG-only and deliberately accepts only the path produced by the closed
 * self-ADB launch contract. It is not a general file-reading utility.</p>
 */
final class DirectShellSecretFile {
    private static final String PATH_PATTERN =
            "/data/local/tmp/warpnect-directshell-secret-[0-9a-f]{1,16}\\.bin";

    private DirectShellSecretFile() {}

    static byte[] readAndDelete(String path) {
        if (path == null || !path.matches(PATH_PATTERN)) {
            throw new IllegalArgumentException("invalid_secret_file_path");
        }
        File file = new File(path);
        byte[] raw = new byte[DirectShellProbeProtocol.SECRET_BYTES];
        boolean deleted = false;
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < raw.length) {
                int count = input.read(raw, offset, raw.length - offset);
                if (count < 0) throw new IllegalArgumentException("invalid_secret_file_length");
                offset += count;
            }
            if (input.read() != -1) throw new IllegalArgumentException("invalid_secret_file_length");
            if (!file.delete()) throw new IllegalArgumentException("secret_file_cleanup_failed");
            deleted = true;
            return DirectShellProbeProtocol.requireSecret(raw);
        } catch (IOException exception) {
            throw new IllegalArgumentException("secret_file_unavailable");
        } finally {
            if (!deleted) {
                // The app-owned controller also removes this one known generation path on failure.
                file.delete();
            }
            Arrays.fill(raw, (byte) 0);
        }
    }
}
