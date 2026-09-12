package io.warpnect.debug.directshell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DirectShellProbeProtocolTest {
    private static final byte[] SECRET = sequence(0x20);
    private static final byte[] OTHER_SECRET = sequence(0x40);

    @Test
    public void authenticatedRequestRoundTripsWithBoundedPayload() throws Exception {
        byte[] encoded = encodeRequest(41L, DirectShellProbeProtocol.Command.PING, "hello".getBytes(StandardCharsets.UTF_8));

        DirectShellProbeProtocol.Request request = DirectShellProbeProtocol.readRequest(
                new DataInputStream(new ByteArrayInputStream(encoded)),
                SECRET
        );

        assertEquals(41L, request.requestId);
        assertEquals(DirectShellProbeProtocol.Command.PING, request.command);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), request.payload);
    }

    @Test
    public void responseAuthenticatesItsRequestIdAndPayload() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeResponse(
                new DataOutputStream(bytes),
                72L,
                DirectShellProbeProtocol.Status.OK,
                "PONG".getBytes(StandardCharsets.UTF_8),
                SECRET
        );

        DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                SECRET
        );

        assertEquals(72L, response.requestId);
        assertEquals(DirectShellProbeProtocol.Status.OK, response.status);
        assertArrayEquals("PONG".getBytes(StandardCharsets.UTF_8), response.payload);
    }

    @Test
    public void requestWithWrongSecretIsRejectedBeforeDispatch() throws Exception {
        byte[] encoded = encodeRequest(91L, DirectShellProbeProtocol.Command.GET_RUNTIME_INFO, new byte[0]);

        try {
            DirectShellProbeProtocol.readRequest(
                    new DataInputStream(new ByteArrayInputStream(encoded)),
                    OTHER_SECRET
            );
            fail("Expected authentication rejection");
        } catch (DirectShellProbeProtocol.ProtocolException exception) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.UNAUTHENTICATED, exception.kind);
        }
    }

    @Test
    public void malformedFrameLengthIsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(DirectShellProbeProtocol.MAX_FRAME_BYTES + 1);

        try {
            DirectShellProbeProtocol.readRequest(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    SECRET
            );
            fail("Expected malformed frame rejection");
        } catch (DirectShellProbeProtocol.ProtocolException exception) {
            assertEquals(DirectShellProbeProtocol.ProtocolException.Kind.MALFORMED, exception.kind);
        }
    }

    @Test
    public void requestOrderRequiresStrictlyIncreasingIds() {
        DirectShellProbeProtocol.RequestOrder order = new DirectShellProbeProtocol.RequestOrder();

        assertTrue(order.accept(10L));
        assertFalse(order.accept(10L));
        assertFalse(order.accept(9L));
        assertTrue(order.accept(11L));
    }

    @Test
    public void secretMustBeExactlyCryptographicKeyLength() {
        assertArrayEquals(SECRET, DirectShellProbeProtocol.requireSecret(SECRET));
        try {
            DirectShellProbeProtocol.requireSecret(Arrays.copyOf(SECRET, SECRET.length - 1));
            fail("Expected secret length rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("32"));
        }
    }

    private static byte[] encodeRequest(long requestId, DirectShellProbeProtocol.Command command, byte[] payload)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DirectShellProbeProtocol.writeRequest(
                new DataOutputStream(bytes),
                requestId,
                command,
                payload,
                SECRET
        );
        return bytes.toByteArray();
    }

    private static byte[] sequence(int first) {
        byte[] output = new byte[DirectShellProbeProtocol.SECRET_BYTES];
        for (int index = 0; index < output.length; index++) output[index] = (byte) (first + index);
        return output;
    }
}
