package io.warpnect.debug.directshell;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Ordered normal-app control peer for the DEBUG-only DirectShell display-capture probe. */
final class DirectShellVideoCaptureProbeClient implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] secret;
    private long nextRequestId = 1L;

    private DirectShellVideoCaptureProbeClient(DirectShellProbeIpv4Socket socket, byte[] secret) {
        this.socket = socket;
        this.input = socket.input();
        this.output = socket.output();
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
    }

    static DirectShellVideoCaptureProbeClient connectLoopback(int port, byte[] secret) throws IOException {
        return new DirectShellVideoCaptureProbeClient(DirectShellProbeIpv4Socket.connect(port), secret);
    }

    Response call(DirectShellProbeProtocol.Command command, String payload)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        long requestId = nextRequestId++;
        DirectShellProbeProtocol.writeRequest(
                output,
                requestId,
                command,
                payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8),
                secret
        );
        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(input, secret);
        if (response.requestId != requestId) {
            throw new IOException("DirectShell video probe response request ID mismatch");
        }
        return new Response(response.status, new String(response.payload, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    static final class Response {
        final DirectShellProbeProtocol.Status status;
        final String payload;

        Response(DirectShellProbeProtocol.Status status, String payload) {
            this.status = status;
            this.payload = payload;
        }
    }
}
