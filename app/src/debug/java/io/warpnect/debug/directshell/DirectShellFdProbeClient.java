package io.warpnect.debug.directshell;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Authenticated loopback control peer for the FD handoff experiment only. */
final class DirectShellFdProbeClient implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] secret;
    private long nextRequestId = 1L;

    private DirectShellFdProbeClient(DirectShellProbeIpv4Socket socket, byte[] secret) {
        this.socket = socket;
        this.input = socket.input();
        this.output = socket.output();
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
    }

    static DirectShellFdProbeClient connectLoopback(int port, byte[] secret) throws IOException {
        return new DirectShellFdProbeClient(DirectShellProbeIpv4Socket.connect(port), secret);
    }

    Response call(DirectShellProbeProtocol.Command command)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        long requestId = nextRequestId++;
        DirectShellProbeProtocol.writeRequest(output, requestId, command, new byte[0], secret);
        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(input, secret);
        if (response.requestId != requestId) {
            throw new IOException("DirectShell FD probe response request ID mismatch");
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
