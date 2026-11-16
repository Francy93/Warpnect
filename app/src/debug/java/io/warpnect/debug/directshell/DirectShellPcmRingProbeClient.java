package io.warpnect.debug.directshell;

import android.os.Debug;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated loopback control peer for the DEBUG-only DirectShell PCM-ring spike. */
final class DirectShellPcmRingProbeClient implements AutoCloseable {
    private final DirectShellProbeIpv4Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final byte[] secret;
    private long nextRequestId = 1L;
    private boolean microphoneStream;
    private int slowMicrophoneCallSamples;
    private int microphoneCalls;
    private final String[] timingSamples = new String[68];
    private int timingSampleCount;
    private final AtomicBoolean closed = new AtomicBoolean();

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
        if (microphoneStream || closed.get()) throw new IOException("control_channel_not_available");
        long requestId = nextRequestId++;
        boolean traceMicrophone = command == DirectShellProbeProtocol.Command.MICROPHONE_FEED;
        long startedNs = traceMicrophone ? System.nanoTime() : 0;
        long startedCpuNs = traceMicrophone ? Debug.threadCpuTimeNanos() : -1;
        DirectShellProbeProtocol.writeRequest(output, requestId, command, payload, secret);
        long sentNs = traceMicrophone ? System.nanoTime() : 0;
        long sentCpuNs = traceMicrophone ? Debug.threadCpuTimeNanos() : -1;
        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(input, secret);
        long receivedNs = traceMicrophone ? System.nanoTime() : 0;
        long receivedCpuNs = traceMicrophone ? Debug.threadCpuTimeNanos() : -1;
        if (response.requestId != requestId) {
            throw new IOException("DirectShell PCM ring probe response request ID mismatch");
        }
        if (traceMicrophone && (microphoneCalls++ < 4 ||
                (receivedNs - startedNs >= 20_000_000L && slowMicrophoneCallSamples++ < 64))) {
            try {
                timingSamples[timingSampleCount++] = "REMOTE_MIC_CONTROL_TIMING request_id=" + requestId +
                        " started_ns=" + startedNs + " sent_ns=" + sentNs + " received_ns=" + receivedNs +
                        " send_ns=" + (sentNs - startedNs) + " response_wait_auth_ns=" + (receivedNs - sentNs) +
                        " send_thread_cpu_ns=" + cpuDelta(startedCpuNs, sentCpuNs) +
                        " response_thread_cpu_ns=" + cpuDelta(sentCpuNs, receivedCpuNs);
            } catch (RuntimeException ignored) {
                // Diagnostic failure must not change the authenticated control result.
            }
        }
        return new Response(requestId, response.status, new String(response.payload, StandardCharsets.UTF_8));
    }

    synchronized long beginMicrophoneStream() throws IOException {
        if (closed.get() || microphoneStream) throw new IOException("invalid_stream_owner");
        socket.enableMicrophoneWrites();
        microphoneStream = true;
        return nextRequestId;
    }

    void sendMicrophone(long requestId, byte[] payload) throws IOException {
        if (closed.get() || !microphoneStream) throw new IOException("stream_closed");
        DirectShellProbeProtocol.writeRequest(output, requestId,
                DirectShellProbeProtocol.Command.MICROPHONE_FEED, payload, secret);
    }

    Response readMicrophone(long expectedId) throws IOException {
        DirectShellProbeProtocol.Response response;
        try {
            response = DirectShellProbeProtocol.readResponse(input, secret);
        } catch (DirectShellProbeProtocol.ProtocolException invalid) {
            throw new IOException("invalid_authenticated_result", invalid);
        }
        if (response.requestId != expectedId || response.status != DirectShellProbeProtocol.Status.OK) {
            throw new IOException("invalid_stream_result");
        }
        return new Response(response.requestId, response.status, new String(response.payload, StandardCharsets.UTF_8));
    }

    Response readMicrophoneTotals(long previous) throws IOException {
        DirectShellProbeProtocol.Response response;
        try { response = DirectShellProbeProtocol.readResponse(input, secret); }
        catch (DirectShellProbeProtocol.ProtocolException invalid) { throw new IOException("invalid_authenticated_result", invalid); }
        if (response.requestId <= previous || response.status != DirectShellProbeProtocol.Status.OK) {
            throw new IOException("invalid_stream_snapshot_order");
        }
        return new Response(response.requestId, response.status, new String(response.payload, StandardCharsets.UTF_8));
    }

    private static long cpuDelta(long before, long after) {
        return before >= 0 && after >= before ? after - before : -1;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        socket.close();
        java.util.Arrays.fill(secret, (byte) 0);
        for (int i = 0; i < timingSampleCount; i++) {
            try {
                Log.i("WarpnectRemoteMic", timingSamples[i]);
            } catch (RuntimeException ignored) {
                // Deferred diagnostics do not determine the channel teardown result.
            }
        }
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
