package io.warpnect.directshell.control;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/** Offline verifier for independent launch/exit measurements; it cannot manufacture them or launch anything. */
final class ControlWitness implements AutoCloseable {
    enum Phase { WAITING, AUTHENTICATED, ACKNOWLEDGED, RELEASED, UNRESOLVED }

    static final class Measurement {
        final Object owner;
        final long creation;
        final int uid;
        final int pid;
        final int api;
        final long generation;
        final String artifact;

        Measurement(Object owner, long creation, int uid, int pid, int api, long generation, String artifact) {
            this.owner = owner;
            this.creation = creation;
            this.uid = uid;
            this.pid = pid;
            this.api = api;
            this.generation = generation;
            this.artifact = artifact;
        }
    }

    private final Measurement expected;
    private final byte[] key;
    private final byte[] identity;
    private final long identityRequest;
    private final long stopRequest;
    private Phase phase = Phase.WAITING;

    ControlWitness(Measurement launch, byte[] master, long identityRequest, long stopRequest) {
        if (launch.owner == null || launch.creation <= 0 || identityRequest <= 0 || stopRequest <= identityRequest) {
            throw new IllegalArgumentException("control_launch_witness");
        }
        this.expected = launch;
        this.identityRequest = identityRequest;
        this.stopRequest = stopRequest;
        this.identity = ControlEndpoint.identity(launch.uid, launch.pid, launch.api, launch.generation, launch.artifact);
        this.key = ControlEndpoint.sessionKey(master, launch.generation, launch.artifact);
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized boolean authenticate(Measurement live, byte[] frame) {
        if (phase != Phase.WAITING || !matches(live) || !response(frame, identityRequest, identity)) return false;
        phase = Phase.AUTHENTICATED;
        return true;
    }

    synchronized boolean acknowledge(Measurement live, byte[] frame) {
        byte[] accepted = "SHUTDOWN_ACCEPTED".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (phase != Phase.AUTHENTICATED || !matches(live) || !response(frame, stopRequest, accepted)) return false;
        phase = Phase.ACKNOWLEDGED;
        return true;
    }

    synchronized boolean released(Measurement exited, boolean processExited, boolean channelClosed,
            boolean launcherResourcesClosed) {
        if (phase != Phase.ACKNOWLEDGED || !matches(exited) || !processExited || !channelClosed ||
                !launcherResourcesClosed) return false;
        phase = Phase.RELEASED;
        Arrays.fill(key, (byte) 0);
        return true;
    }

    private boolean matches(Measurement current) {
        return current != null && current.owner == expected.owner && current.creation == expected.creation &&
                current.pid == expected.pid && current.api == expected.api && current.uid == expected.uid &&
                current.generation == expected.generation && expected.artifact.equals(current.artifact);
    }

    private boolean response(byte[] frame, long expectedId, byte[] expectedData) {
        int expectedBody = ControlEndpoint.HEADER_SIZE + expectedData.length;
        if (frame.length != 4 + expectedBody + ControlEndpoint.MAC_SIZE) return false;
        ByteBuffer input = ByteBuffer.wrap(frame);
        if (input.getInt() != frame.length - 4) return false;
        byte[] body = Arrays.copyOfRange(frame, 4, 4 + expectedBody);
        byte[] mac = Arrays.copyOfRange(frame, 4 + expectedBody, frame.length);
        if (!MessageDigest.isEqual(mac, ControlEndpoint.hmac(key, body))) return false;
        if (input.getInt() != 0x574E4450 || input.getShort() != 2 || input.get() != ControlEndpoint.RESPONSE ||
                input.getLong() != expectedId || input.get() != ControlEndpoint.OK ||
                input.getLong() != expected.generation || input.getInt() != expectedData.length) return false;
        byte[] observed = new byte[expectedData.length];
        input.get(observed);
        return MessageDigest.isEqual(expectedData, observed);
    }

    @Override public synchronized void close() {
        if (phase != Phase.RELEASED) phase = Phase.UNRESOLVED;
        Arrays.fill(key, (byte) 0);
    }
}
