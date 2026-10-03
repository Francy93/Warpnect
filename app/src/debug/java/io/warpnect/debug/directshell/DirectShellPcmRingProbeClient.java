package io.warpnect.debug.directshell;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Authenticated loopback control peer for the DEBUG-only DirectShell PCM-ring spike. */
final class DirectShellPcmRingProbeClient implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] secret;
    private long nextRequestId = 1L;

    private DirectShellPcmRingProbeClient(DirectShellProbeIpv4Socket socket, byte[] secret) {
        this.socket = socket;
        this.input = socket.input();
        this.output = socket.output();
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
    }

    static DirectShellPcmRingProbeClient connectLoopback(int port, byte[] secret) throws IOException {
        return new DirectShellPcmRingProbeClient(DirectShellProbeIpv4Socket.connect(port), secret);
    }

    Response call(DirectShellProbeProtocol.Command command)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        return call(command, new byte[0]);
    }

    synchronized Response call(DirectShellProbeProtocol.Command command, byte[] payload)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        long requestId = nextRequestId++;
        DirectShellProbeProtocol.writeRequest(output, requestId, command, payload, secret);
        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(input, secret);
        if (response.requestId != requestId) {
            throw new IOException("DirectShell PCM ring probe response request ID mismatch");
        }
        return new Response(requestId, response.status, new String(response.payload, StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
        socket.close();
        java.util.Arrays.fill(secret, (byte) 0);
    }

    static final class Response {
        final long requestId;
        final DirectShellProbeProtocol.Status status;
        final String payload;

        Response(long requestId, DirectShellProbeProtocol.Status status, String payload) {
            this.requestId = requestId;
            this.status = status;
            this.payload = payload;
        }
    }
}
