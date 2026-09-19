package io.warpnect.debug.directshell;

import android.os.Build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Reads the platform API from the real app_process runtime rather than a build-time SDK stub.
 *
 * <p>The DirectShell DEX is compiled once but can run under several Android framework releases.
 * The shell-owned {@code getprop} command is available in that environment and avoids reporting
 * a stale SDK value in probe diagnostics or API gates.</p>
 */
final class DirectShellRuntimeApi {
    private static final String SDK_PROPERTY = "ro.build.version.sdk";
    private static final long GETPROP_TIMEOUT_MILLIS = 1_000L;
    private static final Snapshot SNAPSHOT = collect();

    private DirectShellRuntimeApi() {}

    static int sdkInt() {
        return SNAPSHOT.sdkInt;
    }

    static String source() {
        return SNAPSHOT.source;
    }

    static int parseSdkInt(String value) {
        if (value == null) return -1;
        String normalized = value.trim();
        if (normalized.isEmpty()) return -1;
        try {
            int parsed = Integer.parseInt(normalized);
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Snapshot collect() {
        int propertySdk = parseSdkInt(readSdkProperty());
        if (propertySdk > 0) return new Snapshot(propertySdk, SDK_PROPERTY);
        return new Snapshot(Build.VERSION.SDK_INT, "framework_build_fallback");
    }

    private static String readSdkProperty() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", SDK_PROPERTY)
                    .redirectErrorStream(true)
                    .start();
            String value;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(),
                    StandardCharsets.UTF_8
            ))) {
                value = reader.readLine();
            }
            if (!process.waitFor(GETPROP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return value == null ? "" : value;
        } catch (IOException ignored) {
            return "";
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static final class Snapshot {
        final int sdkInt;
        final String source;

        Snapshot(int sdkInt, String source) {
            this.sdkInt = sdkInt;
            this.source = source;
        }
    }
}
