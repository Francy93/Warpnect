package io.warpnect.directshell.control;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Host loopback exercise of the production decoder and socket owner, not Android UID evidence. */
public final class ControlEndpointTest {
    private static final long GENERATION = 27;
    private static final String DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final InetAddress LOOPBACK;
    private static final byte[] KEY;

    static {
        try {
            LOOPBACK = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            byte[] token = new byte[32];
            Arrays.fill(token, (byte) 0x6a);
            KEY = sign(token, ("warpnect-control-v2\n" + GENERATION + "\n" + DIGEST).getBytes(StandardCharsets.US_ASCII));
            require(Arrays.equals(KEY, ControlEndpoint.sessionKey(token, GENERATION, DIGEST)), "session key");
            require(!Arrays.equals(KEY, ControlEndpoint.sessionKey(token, GENERATION + 1, DIGEST)), "generation key");
            String other = DIGEST.replace('a', 'b');
            require(!Arrays.equals(KEY, ControlEndpoint.sessionKey(token, GENERATION, other)), "artifact key");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static void main(String[] args) throws Exception {
        launchIdentity();
        allowedCommandsReplayAndDrain();
        rejected(2, GENERATION, new byte[32], 1, 0, "control_authentication");
        rejected(2, GENERATION + 1, KEY, 3, 0, "control_generation_or_payload");
        rejected(1, GENERATION, KEY, 1, 0, "control_profile");
        rejected(2, GENERATION, KEY, 1, 1, "control_generation_or_payload");
        stalledReaderDrainsWithoutAcknowledgement();
        witnessRequiresExactProcessAndPhysicalExit();
        System.out.println("CONTROL_HOST_TESTS=8 PASS");
    }

    private static void witnessRequiresExactProcessAndPhysicalExit() {
        Object owner = new Object();
        ControlWitness.Measurement launch = new ControlWitness.Measurement(owner, 200, 2000, 109, 30, GENERATION, DIGEST);
        ControlWitness.Measurement reused = new ControlWitness.Measurement(owner, 201, 2000, 109, 30, GENERATION, DIGEST);
        ControlWitness.Measurement foreign =
                new ControlWitness.Measurement(new Object(), 200, 2000, 109, 30, GENERATION, DIGEST);
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 0x6a);
        byte[] auth = ControlEndpoint.frame(2, 1, 0, GENERATION,
                ControlEndpoint.identity(2000, 109, 30, GENERATION, DIGEST), KEY).array();
        byte[] ack = ControlEndpoint.frame(2, 2, 0, GENERATION,
                "SHUTDOWN_ACCEPTED".getBytes(StandardCharsets.US_ASCII), KEY).array();
        try (ControlWitness witness = new ControlWitness(launch, master, 1, 2)) {
            require(!witness.acknowledge(launch, ack), "unverified shutdown");
            require(!witness.authenticate(reused, auth) && !witness.authenticate(foreign, auth), "ownership mismatch");
            require(witness.authenticate(launch, auth) && witness.acknowledge(launch, ack), "verified phase and HMAC");
            require(witness.phase() == ControlWitness.Phase.ACKNOWLEDGED, "ack is not release");
            require(!witness.released(reused, true, true, true), "PID reuse");
            require(!witness.released(launch, false, true, true), "process still running");
            require(!witness.released(launch, true, false, true), "channel held");
            require(!witness.released(launch, true, true, false), "launch resource held");
            require(witness.released(launch, true, true, true), "all terminal witnesses admitted");
        }
    }

    private static void launchIdentity() {
        expectIllegal(() -> ControlEndpoint.identity(0, 1, 30, GENERATION, DIGEST));
        expectIllegal(() -> ControlEndpoint.identity(10000, 1, 30, GENERATION, DIGEST));
        expectIllegal(() -> ControlEndpoint.identity(2000, 0, 30, GENERATION, DIGEST));
        expectIllegal(() -> ControlEndpoint.identity(2000, 1, 30, 0, DIGEST));
        expectIllegal(() -> ControlEndpoint.identity(2000, 1, 30, GENERATION, "bad"));
    }

    private static void allowedCommandsReplayAndDrain() throws Exception {
        try (Host host = new Host(4_000); Socket client = host.connect()) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());
            out.write(request(2, 1, 3, GENERATION, KEY, 0)); // Can't stop before identity.
            require(receive(in, 1, 5).isEmpty(), "early shutdown");
            out.write(request(2, 2, 1, GENERATION, KEY, 0));
            require(receive(in, 2, 0).equals("PONG"), "ping");
            out.write(request(2, 2, 3, GENERATION, KEY, 0));
            require(receive(in, 2, 1).isEmpty(), "replay");
            out.write(request(2, 3, 80, GENERATION, KEY, 0));
            require(receive(in, 3, 2).isEmpty(), "foreign role");
            out.write(request(2, 4, 2, GENERATION, KEY, 0));
            require(receive(in, 4, 0).equals("role=Control\nuid=2000\npid=109\napi=30\ngeneration=" + GENERATION +
                    "\nartifact_sha256=" + DIGEST), "runtime identity");
            out.write(request(2, 5, 3, GENERATION, KEY, 0));
            require(receive(in, 5, 0).equals("SHUTDOWN_ACCEPTED"), "ack");
            require(in.read() == -1, "physical peer closure after ack");
            host.terminated(null);
            assertListenerClosed(host.port.get());
        }
    }

    private static void rejected(int version, long generation, byte[] key, int command, int payload, String reason)
            throws Exception {
        try (Host host = new Host(4_000); Socket client = host.connect()) {
            client.getOutputStream().write(request(version, 1, command, generation, key, payload));
            require(client.getInputStream().read() == -1, "invalid request has no response");
            host.terminated(reason);
            assertListenerClosed(host.port.get());
        }
    }

    private static void stalledReaderDrainsWithoutAcknowledgement() throws Exception {
        try (Host host = new Host(1_000); Socket client = host.connect()) {
            client.getOutputStream().write(0); // Never completes even the frame length.
            require(client.getInputStream().read() == -1, "deadline must close incomplete client");
            host.terminated("control_deadline");
            assertListenerClosed(host.port.get());
        }
    }

    private static byte[] request(int version, long id, int command, long generation, byte[] key, int payload)
            throws Exception {
        byte[] body = ByteBuffer.allocate(28).putInt(0x574E4450).putShort((short) version)
                .put((byte) 1).putLong(id).put((byte) command).putLong(generation).putInt(payload).array();
        return ByteBuffer.allocate(64).putInt(60).put(body).put(sign(key, body)).array();
    }

    private static String receive(DataInputStream input, long expectedId, int expectedStatus) throws Exception {
        int length = input.readInt();
        require(length >= 60 && length <= 316, "bounded response");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
        require(MessageDigest.isEqual(sign(KEY, body), Arrays.copyOfRange(bytes, body.length, bytes.length)), "response mac");
        ByteBuffer fields = ByteBuffer.wrap(body);
        require(fields.getInt() == 0x574E4450 && fields.getShort() == 2 && fields.get() == 2, "response profile");
        require(fields.getLong() == expectedId && Byte.toUnsignedInt(fields.get()) == expectedStatus, "response order/status");
        require(fields.getLong() == GENERATION && fields.getInt() == fields.remaining(), "response generation/bounds");
        return StandardCharsets.US_ASCII.decode(fields).toString();
    }

    private static byte[] sign(byte[] key, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(body);
    }

    private static void assertListenerClosed(int port) throws IOException {
        try (Socket rejected = new Socket()) {
            try {
                rejected.connect(new InetSocketAddress(LOOPBACK, port), 500);
                throw new AssertionError("owned listener remained open");
            } catch (java.net.ConnectException expected) {
                // Loopback refuses after the exact worker exited.
            }
        }
    }

    private static void expectIllegal(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid identity accepted");
        } catch (IllegalArgumentException expected) {
            // No endpoint was constructed.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Host implements AutoCloseable {
        final CompletableFuture<Integer> port = new CompletableFuture<>();
        final CompletableFuture<String> ended = new CompletableFuture<>();
        final Thread worker;

        Host(long timeout) {
            long cutoff = monotonicMillis() + timeout;
            worker = new Thread(() -> {
                try {
                    ControlEndpoint.serve(KEY, GENERATION, ControlEndpoint.identity(2000, 109, 30, GENERATION, DIGEST),
                            Host::monotonicMillis, cutoff, port::complete);
                    ended.complete(null);
                } catch (IOException e) {
                    ended.complete(e.getMessage());
                } catch (Throwable e) {
                    ended.completeExceptionally(e);
                }
            }, "owned-control-host-fixture");
            worker.start();
        }

        Socket connect() throws Exception {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(LOOPBACK, port.get(3, TimeUnit.SECONDS)), 500);
                socket.setSoTimeout(5_000);
                return socket;
            } catch (Exception failure) {
                socket.close();
                throw failure;
            }
        }

        void terminated(String reason) throws Exception {
            require(java.util.Objects.equals(ended.get(5, TimeUnit.SECONDS), reason), "terminal reason");
            worker.join(500);
            require(!worker.isAlive(), "worker retained");
        }

        @Override public void close() throws InterruptedException {
            worker.interrupt();
            worker.join(5_000);
            require(!worker.isAlive(), "host cleanup uncertain");
        }

        private static long monotonicMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
    }
}
