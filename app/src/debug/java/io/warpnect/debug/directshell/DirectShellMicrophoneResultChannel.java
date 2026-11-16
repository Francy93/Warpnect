package io.warpnect.debug.directshell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Signed cumulative telemetry, never PCM credits. Own socket and fresh Session nonce. */
final class DirectShellMicrophoneResultChannel implements AutoCloseable {
    private static final int NONCE_BYTES = 16;
    private static final int ATTACH_BYTES = 1 + Long.BYTES + Integer.BYTES + NONCE_BYTES;
    private final DatagramSocket socket;
    private final byte[] secret;
    private final byte[] nonce;
    private final long generation;
    private final InetSocketAddress destination;

    private DirectShellMicrophoneResultChannel(DatagramSocket socket, byte[] secret, byte[] nonce,
            long generation, InetSocketAddress destination) {
        this.socket = socket;
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
        this.nonce = nonce;
        this.generation = generation;
        this.destination = destination;
    }

    static DirectShellMicrophoneResultChannel receiver(byte[] secret, long generation) throws IOException {
        if (generation <= 0) throw new IOException("invalid_result_generation");
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback(), 0));
        socket.setSoTimeout(3000);
        return new DirectShellMicrophoneResultChannel(socket, secret, nonce, generation, null);
    }

    byte[] attachment() {
        return ByteBuffer.allocate(ATTACH_BYTES).order(ByteOrder.BIG_ENDIAN).put((byte) 1)
                .putLong(generation).putInt(socket.getLocalPort()).put(nonce).array();
    }

    static DirectShellMicrophoneResultChannel sender(byte[] attachment, byte[] secret,
            long generation) throws IOException {
        if (attachment.length != ATTACH_BYTES) throw new IOException("invalid_result_attachment");
        ByteBuffer input = ByteBuffer.wrap(attachment).order(ByteOrder.BIG_ENDIAN);
        if (input.get() != 1 || input.getLong() != generation || generation <= 0) {
            throw new IOException("invalid_result_generation");
        }
        int port = input.getInt();
        if (port < 1024 || port > 65535) throw new IOException("invalid_result_port");
        byte[] nonce = new byte[NONCE_BYTES];
        input.get(nonce);
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback(), 0));
        return new DirectShellMicrophoneResultChannel(socket, secret, nonce, generation,
                new InetSocketAddress(loopback(), port));
    }

    void publish(DirectShellMicrophoneFeedTotals.Snapshot completed) throws IOException {
        if (destination == null) throw new IOException("result_sender_required");
        byte[] text = completed.encode().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[NONCE_BYTES + text.length];
        System.arraycopy(nonce, 0, payload, 0, NONCE_BYTES);
        System.arraycopy(text, 0, payload, NONCE_BYTES, text.length);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeResponse(new DataOutputStream(bytes), completed.completed(),
                DirectShellProbeProtocol.Status.OK, payload, secret);
        byte[] packet = bytes.toByteArray();
        socket.send(new DatagramPacket(packet, packet.length, destination));
    }

    DirectShellPcmRingProbeClient.Response read(long previous) throws IOException {
        if (destination != null) throw new IOException("result_receiver_required");
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            byte[] bytes = new byte[DirectShellProbeProtocol.MAX_FRAME_BYTES + 1];
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new java.net.SocketTimeoutException("result_snapshot_timeout");
            socket.setSoTimeout((int) Math.max(1, (remaining + 999_999) / 1_000_000));
            socket.receive(packet);
            if (!packet.getAddress().equals(loopback()) || packet.getLength() > DirectShellProbeProtocol.MAX_FRAME_BYTES) {
                throw new IOException("invalid_result_endpoint_or_size");
            }
            ByteArrayInputStream body = new ByteArrayInputStream(bytes, 0, packet.getLength());
            DirectShellProbeProtocol.Response response;
            try { response = DirectShellProbeProtocol.readResponse(new DataInputStream(body), secret); }
            catch (DirectShellProbeProtocol.ProtocolException invalid) {
                throw new IOException("invalid_authenticated_result", invalid);
            }
            if (body.available() != 0 || response.status != DirectShellProbeProtocol.Status.OK ||
                    response.payload.length < NONCE_BYTES) {
                throw new IOException("invalid_result_session");
            }
            if (!MessageDigest.isEqual(nonce, Arrays.copyOf(response.payload, NONCE_BYTES))) continue;
            // UDP snapshots may be skipped/duplicated; never grant ingress credits or replay totals.
            if (response.requestId <= previous) continue;
            return new DirectShellPcmRingProbeClient.Response(response.requestId, response.status,
                    new String(response.payload, NONCE_BYTES, response.payload.length - NONCE_BYTES,
                            StandardCharsets.UTF_8));
        } while (true);
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
    }

    @Override public void close() {
        socket.close();
        Arrays.fill(secret, (byte) 0);
        Arrays.fill(nonce, (byte) 0);
    }
}
