package io.warpnect.debug.directshell;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Shell-side, single-owner connection. Ordinary feature requests use other sockets. */
final class DirectShellOwnerLease implements AutoCloseable {
    private DirectShellProbeIpv4Socket owner;
    private boolean closed;

    synchronized boolean attached() {
        return owner != null && !closed;
    }

    synchronized boolean attach(
            DirectShellProbeIpv4Socket socket,
            DirectShellProbeProtocol.Request request,
            DataOutputStream output,
            byte[] secret,
            long generation,
            Runnable onLost
    ) throws IOException {
        if (closed || owner != null || !matchesGeneration(request.payload, generation)) {
            DirectShellProbeProtocol.writeResponse(output, request.requestId,
                    DirectShellProbeProtocol.Status.BAD_STATE,
                    "owner_generation_or_state_rejected".getBytes(StandardCharsets.US_ASCII), secret);
            return false;
        }
        DirectShellProbeProtocol.writeResponse(output, request.requestId,
                DirectShellProbeProtocol.Status.OK,
                "OWNER_ATTACHED".getBytes(StandardCharsets.US_ASCII), secret);
        owner = socket;
        Thread monitor = new Thread(() -> monitor(socket, onLost), "WarpnectDirectShellOwnerMonitor");
        monitor.setDaemon(true);
        monitor.start();
        return true;
    }

    private void monitor(DirectShellProbeIpv4Socket socket, Runnable onLost) {
        try {
            while (attached()) {
                if (socket.awaitOwnerEnd(1_000)) break;
            }
        } catch (IOException ignored) {
            // A broken owner channel has the same safety outcome as EOF.
        }
        boolean lost;
        synchronized (this) {
            lost = !closed && owner == socket;
            if (lost) owner = null;
        }
        if (lost) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // The helper is already terminating.
            }
            onLost.run();
        }
    }

    static boolean bootstrapExpired(long nowMs, long lastAuthenticatedMs, long timeoutMs, boolean owned) {
        return !owned && nowMs - lastAuthenticatedMs >= timeoutMs;
    }

    static boolean matchesGeneration(byte[] payload, long generation) {
        return generation > 0 && payload.length == Long.BYTES &&
                ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong() == generation;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        DirectShellProbeIpv4Socket socket = owner;
        owner = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Shutdown remains idempotent.
            }
        }
    }
}
