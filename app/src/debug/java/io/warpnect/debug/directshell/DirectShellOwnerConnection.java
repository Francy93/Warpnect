package io.warpnect.debug.directshell;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Normal-app endpoint of the authenticated Host-owner connection. */
final class DirectShellOwnerConnection implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean lost = new AtomicBoolean(false);

    private DirectShellOwnerConnection(DirectShellProbeIpv4Socket socket) {
        this.socket = socket;
    }

    static DirectShellOwnerConnection connect(int port, long generation, byte[] launchSecret)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        byte[] secret = DirectShellProbeProtocol.requireSecret(launchSecret);
        DirectShellProbeIpv4Socket socket = null;
        try {
            socket = DirectShellProbeIpv4Socket.connect(port);
            byte[] payload = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN)
                    .putLong(generation).array();
            DirectShellProbeProtocol.writeRequest(socket.output(), 1L,
                    DirectShellProbeProtocol.Command.HOST_OWNER_ATTACH, payload, secret);
            DirectShellProbeProtocol.Response response =
                    DirectShellProbeProtocol.readResponse(socket.input(), secret);
            if (response.requestId != 1L || response.status != DirectShellProbeProtocol.Status.OK ||
                    !"OWNER_ATTACHED".equals(new String(response.payload, StandardCharsets.US_ASCII))) {
                throw new IOException("DirectShell Host owner attach rejected");
            }
            return new DirectShellOwnerConnection(socket);
        } catch (IOException | DirectShellProbeProtocol.ProtocolException exception) {
            if (socket != null) socket.close();
            throw exception;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    void monitor(Runnable onLost) {
        Thread thread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    if (socket.awaitOwnerEnd(1_000)) break;
                }
            } catch (IOException ignored) {
                // Loss of the owner channel makes the current runtime unavailable.
            }
            if (!closed.get()) {
                lost.set(true);
                onLost.run();
            }
        }, "WarpnectDirectShellOwnerWatch");
        thread.setDaemon(true);
        thread.start();
    }

    void suppressLoss() {
        closed.set(true);
    }

    boolean alive() {
        return !closed.get() && !lost.get();
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing an already-lost helper is idempotent.
        }
    }
}
