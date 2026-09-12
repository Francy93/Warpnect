package io.warpnect.debug.directshell;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/** Normal-app peer for the DEBUG-only DirectShell bootstrap probe. */
final class DirectShellProbeClient implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] secret;

    private DirectShellProbeClient(DirectShellProbeIpv4Socket socket, byte[] secret) {
        this.socket = socket;
        this.input = socket.input();
        this.output = socket.output();
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
    }

    static DirectShellProbeClient connectLoopback(int port, byte[] secret) throws IOException {
        return new DirectShellProbeClient(DirectShellProbeIpv4Socket.connect(port), secret);
    }

    static NegativeAuthResult verifyUnauthenticatedRequestRejected(int port, byte[] expectedSecret)
            throws IOException {
        byte[] wrongSecret = DirectShellProbeProtocol.requireSecret(expectedSecret);
        wrongSecret[0] ^= 0x01;
        try (DirectShellProbeClient client = connectLoopback(port, wrongSecret)) {
            DirectShellProbeProtocol.writeRequest(
                    client.output,
                    1L,
                    DirectShellProbeProtocol.Command.GET_RUNTIME_INFO,
                    new byte[0],
                    client.secret
            );
            try {
                client.readResponse(1L);
                return NegativeAuthResult.UNEXPECTED_RESPONSE;
            } catch (EOFException | SocketException | DirectShellProbeProtocol.ProtocolException expected) {
                return NegativeAuthResult.REJECTED;
            }
        }
    }

    ProbeRunResult runAuthenticatedSequence(boolean requestShutdown)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        DirectShellProbeProtocol.Response ping = request(
                1L,
                DirectShellProbeProtocol.Command.PING
        );
        requireOk(ping, "ping");
        DirectShellProbeProtocol.Response runtime = request(
                2L,
                DirectShellProbeProtocol.Command.GET_RUNTIME_INFO
        );
        requireOk(runtime, "runtime_info");
        long shutdownId = 0L;
        if (requestShutdown) {
            DirectShellProbeProtocol.Response shutdown = request(
                    3L,
                    DirectShellProbeProtocol.Command.SHUTDOWN
            );
            requireOk(shutdown, "shutdown");
            shutdownId = shutdown.requestId;
        }
        return new ProbeRunResult(
                ping.requestId,
                runtime.requestId,
                shutdownId,
                new String(runtime.payload, StandardCharsets.UTF_8)
        );
    }

    private DirectShellProbeProtocol.Response request(long requestId, DirectShellProbeProtocol.Command command)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        DirectShellProbeProtocol.writeRequest(output, requestId, command, new byte[0], secret);
        return readResponse(requestId);
    }

    private DirectShellProbeProtocol.Response readResponse(long expectedRequestId)
            throws IOException, DirectShellProbeProtocol.ProtocolException {
        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(input, secret);
        if (response.requestId != expectedRequestId) {
            throw new IOException("DirectShell probe response request ID mismatch");
        }
        return response;
    }

    private static void requireOk(DirectShellProbeProtocol.Response response, String operation)
            throws IOException {
        if (response.status != DirectShellProbeProtocol.Status.OK) {
            throw new IOException("DirectShell probe " + operation + " returned " + response.status.name());
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    enum NegativeAuthResult {
        REJECTED,
        UNEXPECTED_RESPONSE,
    }

    static final class ProbeRunResult {
        final long pingRequestId;
        final long runtimeInfoRequestId;
        final long shutdownRequestId;
        final String runtimeInfo;

        ProbeRunResult(long pingRequestId, long runtimeInfoRequestId, long shutdownRequestId, String runtimeInfo) {
            this.pingRequestId = pingRequestId;
            this.runtimeInfoRequestId = runtimeInfoRequestId;
            this.shutdownRequestId = shutdownRequestId;
            this.runtimeInfo = runtimeInfo;
        }
    }
}
