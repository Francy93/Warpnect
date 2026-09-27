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

    @Test public void onlyTypedInputAndFoundationAreAuthorized() {
        for (DirectShellProbeProtocol.Command command : DirectShellProbeProtocol.Command.values()) {
            assertEquals(command == DirectShellProbeProtocol.Command.PING ||
                    command == DirectShellProbeProtocol.Command.GET_RUNTIME_INFO ||
                    command == DirectShellProbeProtocol.Command.ROOT_CAPABILITIES ||
                    command == DirectShellProbeProtocol.Command.SHUTDOWN || RootControlLease.inputCommand(command) ||
                    RootControlLease.videoCommand(command) || RootControlLease.audioCommand(command), RootControlLease.supported(command));
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

    private static byte[] input(long generation) {
        return java.nio.ByteBuffer.allocate(16).putInt(0x574e4953).putInt(1).putLong(generation).array();
    }

    private static byte[] video(long generation) {
        return java.nio.ByteBuffer.allocate(40).putInt(0x574e5652).putInt(1).putLong(generation)
                .putLong(20).putInt(0).putInt(640).putInt(360).putInt(1).array();
    }
    private static byte[] audio(long generation) {
        return java.nio.ByteBuffer.allocate(24).putInt(0x574e5241).putInt(1).putLong(generation).putLong(20).array();
    }
    @Test public void audioRequiresAuthentication() {
        assertFalse(lease().acceptAudio(2, audio(10), 1));
    }
    @Test public void audioBindsGenerationOrderAndExpiry() {
        RootControlLease lease = lease(); lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptAudio(2, audio(11), 2));
        assertTrue(lease.acceptAudio(2, audio(10), 2));
        assertFalse(lease.acceptAudio(2, audio(10), 2));
        assertFalse(lease.acceptAudio(3, audio(10), 100));
    }
    @Test public void malformedAudioFailsClosed() {
        RootControlLease lease = lease(); lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptAudio(2, new byte[4000], 2));
        assertFalse(lease.acceptAudio(2, new byte[23], 2));
        byte[] wrong = audio(10); wrong[7] = 2;
        assertFalse(lease.acceptAudio(2, wrong, 2));
        byte[] zero = audio(10); Arrays.fill(zero, 16, 24, (byte) 0);
        assertFalse(lease.acceptAudio(2, zero, 2));
    }
    @Test public void audioUsesExistingAuthenticatedEnvelope() throws Exception {
        byte[] wrong = new byte[32]; Arrays.fill(wrong, (byte) 1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(new DataOutputStream(encoded), 2,
                DirectShellProbeProtocol.Command.PCM_RING_AUDIO_START, audio(10), wrong);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), new byte[32]);
            fail("Unauthenticated audio accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.UNAUTHENTICATED, expected.kind);
        }
    }
    @Test public void videoRequiresAuthentication() {
        assertFalse(lease().acceptVideo(2, video(10), 1));
    }
    @Test public void videoBindsGenerationOrderAndExpiry() {
        RootControlLease lease = lease();
        lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptVideo(2, video(11), 2));
        assertTrue(lease.acceptVideo(2, video(10), 2));
        assertFalse(lease.acceptVideo(2, video(10), 2));
        assertFalse(lease.acceptVideo(3, video(10), 100));
    }
    @Test public void malformedVideoFailsClosed() {
        RootControlLease lease = lease(); lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptVideo(2, new byte[4000], 2));
        byte[] wrong = video(10); wrong[7] = 2;
        assertFalse(lease.acceptVideo(2, wrong, 2));
    }
    @Test public void videoUsesExistingAuthenticatedEnvelope() throws Exception {
        byte[] wrong = new byte[32]; Arrays.fill(wrong, (byte) 1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(new DataOutputStream(encoded), 2,
                DirectShellProbeProtocol.Command.VIDEO_START, video(10), wrong);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), new byte[32]);
            fail("Unauthenticated video accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.UNAUTHENTICATED, expected.kind);
        }
    }
    @Test public void typedInputRequiresAuthentication() {
        assertFalse(lease().acceptInput(2, input(10), 2));
    }
    @Test public void typedInputBindsGenerationAndRequestOrder() {
        RootControlLease lease = lease();
        lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptInput(2, input(11), 2));
        assertTrue(lease.acceptInput(2, input(10), 2));
        assertFalse(lease.acceptInput(2, input(10), 2));
        assertTrue(lease.accept(3, "10", 3));
    }
    @Test public void malformedInputHeaderAndOversizeAreRejected() {
        RootControlLease lease = lease();
        lease.hello(1, "10|" + NONCE, 1);
        assertFalse(lease.acceptInput(2, new byte[15], 2));
        assertFalse(lease.acceptInput(2, new byte[4097], 2));
        byte[] wrong = input(10); wrong[7] = 2;
        assertFalse(lease.acceptInput(2, wrong, 2));
        assertFalse(lease.acceptInput(2, input(10), 100));
    }
    @Test public void inputUsesTheExistingAuthenticatedEnvelope() throws Exception {
        byte[] wrong = new byte[32]; Arrays.fill(wrong, (byte) 1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(new DataOutputStream(encoded), 2,
                DirectShellProbeProtocol.Command.INPUT_SESSION_KEY, input(10), wrong);
        try {
            DirectShellProbeProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())), new byte[32]);
            fail("Unauthenticated input accepted");
        } catch (DirectShellProbeProtocol.ProtocolException expected) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.UNAUTHENTICATED, expected.kind);
        }
    }
}
