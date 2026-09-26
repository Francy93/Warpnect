package io.warpnect.debug.directshell;

import static org.junit.Assert.*;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;

public final class RootControlLeaseTest {
    private static final String NONCE = "a".repeat(64);

    @Test public void validHandshakeAndOrderedControl() {
        RootControlLease lease = lease();
        assertTrue(lease.hello(1, "10|" + NONCE, 1));
        assertTrue(lease.accept(2, "10", 2));
        assertTrue(lease.accept(3, "10", 3));
    }

    @Test public void rejectsWrongGeneration() {
        assertFalse(lease().hello(1, "11|" + NONCE, 1));
    }

    @Test public void rejectsReplayedChallenge() {
        assertFalse(lease().hello(1, "10|" + "b".repeat(64), 1));
    }

    @Test public void rejectsUnauthenticatedCommands() {
        assertFalse(lease().accept(2, "10", 1));
    }

    @Test public void rejectsExpiredGeneration() {
        assertFalse(lease().hello(1, "10|" + NONCE, 100));
    }

    @Test public void expiresAuthenticatedGeneration() {
        RootControlLease lease = lease();
        lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.accept(2, "10", 100));
    }

    @Test public void rejectsDuplicateHelloAndReplayedRequest() {
        RootControlLease lease = lease();
        assertTrue(lease.hello(1, "10|" + NONCE, 1));
        assertFalse(lease.hello(1, "10|" + NONCE, 1));
        assertTrue(lease.accept(2, "10", 2));
        assertFalse(lease.accept(2, "10", 2));
    }

    @Test public void closedLeaseRejectsControl() {
        RootControlLease lease = lease();
        lease.hello(1, "10|" + NONCE, 1);
        lease.close();
        assertFalse(lease.accept(2, "10", 2));
    }

    @Test public void doesNotAuthorizeMediaInputOrMutation() {
        for (DirectShellProbeProtocol.Command command : DirectShellProbeProtocol.Command.values()) {
            assertEquals(command == DirectShellProbeProtocol.Command.PING ||
                    command == DirectShellProbeProtocol.Command.GET_RUNTIME_INFO ||
                    command == DirectShellProbeProtocol.Command.ROOT_CAPABILITIES ||
                    command == DirectShellProbeProtocol.Command.SHUTDOWN, RootControlLease.supported(command));
        }
    }

    @Test public void invalidHmacRejectedBySharedEnvelope() throws Exception {
        byte[] secret = new byte[32];
        byte[] wrong = new byte[32];
        Arrays.fill(wrong, (byte) 1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(new DataOutputStream(encoded), 1,
                DirectShellProbeProtocol.Command.GET_RUNTIME_INFO, ("10|" + NONCE).getBytes(), wrong);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), secret);
            fail("HMAC accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.UNAUTHENTICATED, expected.kind);
        }
    }

    @Test public void malformedFrameRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new DataOutputStream(encoded).writeInt(Integer.MAX_VALUE);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), new byte[32]);
            fail("Malformed frame accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.MALFORMED, expected.kind);
        }
    }

    @Test public void authenticatedWrongProtocolVersionFailsClosed() throws Exception {
        byte[] secret = new byte[32];
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(new DataOutputStream(encoded), 1,
                DirectShellProbeProtocol.Command.PING, "10".getBytes(), secret);
        byte[] frame = encoded.toByteArray();
        frame[9] = 2; // Header version low byte, following the frame length and magic.
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
        byte[] signature = mac.doFinal(Arrays.copyOfRange(frame, 4, frame.length - 32));
        System.arraycopy(signature, 0, frame, frame.length - 32, 32);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(frame)), secret);
            fail("Version mismatch accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.MALFORMED, expected.kind);
        }
    }

    private static RootControlLease lease() { return new RootControlLease(10, 100, NONCE); }
}
