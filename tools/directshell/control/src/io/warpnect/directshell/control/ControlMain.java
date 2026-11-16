package io.warpnect.directshell.control;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Offline-packaged entry point. Nothing in the application invokes it. */
public final class ControlMain {
    private static final int MAX_DEX_BYTES = 2 * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            // Never echo launch input or exception paths.
            System.err.println("CONTROL_STOPPED");
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        long cutoff = SystemClock.elapsedRealtime() + 15_000;
        if (Process.myUid() != 2000 || args.length != 2 || !args[0].matches("[1-9][0-9]{0,18}") ||
                !args[1].matches("[a-f0-9]{64}")) throw new IOException("control_launch");
        long generation = Long.parseLong(args[0]);
        String declaredClassPath = System.getenv("CLASSPATH");
        if (declaredClassPath == null || !declaredClassPath.startsWith("/") || declaredClassPath.contains(":")) {
            throw new IOException("control_classpath");
        }
        File dex = new File(declaredClassPath);
        if (!declaredClassPath.endsWith(".dex") || !dex.getCanonicalPath().equals(declaredClassPath) || !dex.isFile()) {
            throw new IOException("control_artifact_path");
        }
        String digest = digest(dex, cutoff);
        if (!digest.equals(args[1])) throw new IOException("control_digest");
        byte[] identity = ControlEndpoint.identity(Process.myUid(), Process.myPid(), Build.VERSION.SDK_INT,
                generation, digest);
        byte[] secret = new byte[ControlEndpoint.MAC_SIZE];
        byte[] key = null;
        try {
            receiveSecret(secret, cutoff);
            key = ControlEndpoint.sessionKey(secret, generation, digest);
            Arrays.fill(secret, (byte) 0);
            ControlEndpoint.serve(key, generation, identity, SystemClock::elapsedRealtime, cutoff,
                    port -> System.out.println("CONTROL_READY port=" + port + " pid=" + Process.myPid()));
        } finally {
            Arrays.fill(secret, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static String digest(File dex, long cutoff) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        int size = 0;
        try (FileInputStream input = new FileInputStream(dex)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_DEX_BYTES || SystemClock.elapsedRealtime() >= cutoff) {
                    throw new IOException("control_artifact_bound");
                }
                hash.update(buffer, 0, count);
            }
        }
        if (size == 0) throw new IOException("control_empty_artifact");
        byte[] bytes = hash.digest();
        StringBuilder out = new StringBuilder(64);
        for (byte value : bytes) out.append(HEX[(value & 0xff) >>> 4]).append(HEX[value & 15]);
        return out.toString();
    }

    private static void receiveSecret(byte[] value, long cutoff) throws Exception {
        StructPollfd fd = new StructPollfd();
        fd.fd = FileDescriptor.in;
        fd.events = (short) OsConstants.POLLIN;
        StructPollfd[] fds = { fd };
        try {
            for (int offset = 0; offset < value.length;) {
                long remaining = cutoff - SystemClock.elapsedRealtime();
                if (remaining <= 0 || Os.poll(fds, (int) remaining) <= 0 ||
                        (fd.revents & OsConstants.POLLIN) == 0) throw new IOException("control_secret_timeout");
                int count = Os.read(fd.fd, value, offset, value.length - offset);
                if (count <= 0) throw new IOException("control_secret_eof");
                offset += count;
            }
        } finally {
            Os.close(fd.fd);
        }
    }

    private ControlMain() {}
}
