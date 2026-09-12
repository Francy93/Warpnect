package io.warpnect.debug.directshell;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Internal, bounded framing for the DEBUG-only DirectShell bootstrap probe.
 *
 * <p>This is deliberately independent of SCL and all Session wire contracts. Each frame carries
 * an HMAC-SHA-256 over its immutable header and payload, allowing both peers to authenticate the
 * local control exchange with a per-launch secret.</p>
 */
final class DirectShellProbeProtocol {
    static final int MAGIC = 0x574E4450; // "WNDP"
    static final short VERSION = 1;
    static final int SECRET_BYTES = 32;
    static final int MAC_BYTES = 32;
    static final int MAX_PAYLOAD_BYTES = 4 * 1024;
    static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + 1 + Long.BYTES + 1 + Integer.BYTES;
    static final int MIN_FRAME_BYTES = HEADER_BYTES + MAC_BYTES;
    static final int MAX_FRAME_BYTES = HEADER_BYTES + MAX_PAYLOAD_BYTES + MAC_BYTES;

    private static final byte TYPE_REQUEST = 1;
    private static final byte TYPE_RESPONSE = 2;

    private DirectShellProbeProtocol() {}

    enum Command {
        PING(1),
        GET_RUNTIME_INFO(2),
        SHUTDOWN(3);

        final int code;

        Command(int code) {
            this.code = code;
        }

        static Command fromCode(int code) throws ProtocolException {
            for (Command command : values()) {
                if (command.code == code) return command;
            }
            throw ProtocolException.malformed("unknown_command");
        }
    }

    enum Status {
        OK(0),
        OUT_OF_ORDER(1),
        UNSUPPORTED(2),
        SHUTTING_DOWN(3),
        INTERNAL_ERROR(4);

        final int code;

        Status(int code) {
            this.code = code;
        }

        static Status fromCode(int code) throws ProtocolException {
            for (Status status : values()) {
                if (status.code == code) return status;
            }
            throw ProtocolException.malformed("unknown_status");
        }
    }

    static final class Request {
        final long requestId;
        final Command command;
        final byte[] payload;

        Request(long requestId, Command command, byte[] payload) {
            this.requestId = requestId;
            this.command = command;
            this.payload = payload;
        }
    }

    static final class Response {
        final long requestId;
        final Status status;
        final byte[] payload;

        Response(long requestId, Status status, byte[] payload) {
            this.requestId = requestId;
            this.status = status;
            this.payload = payload;
        }
    }

    static void writeRequest(
            DataOutputStream output,
            long requestId,
            Command command,
            byte[] payload,
            byte[] secret
    ) throws IOException {
        writeEnvelope(output, TYPE_REQUEST, requestId, command.code, payload, secret);
    }

    static Request readRequest(DataInputStream input, byte[] secret) throws IOException, ProtocolException {
        Envelope envelope = readEnvelope(input, secret);
        if (envelope.type != TYPE_REQUEST) throw ProtocolException.malformed("expected_request");
        return new Request(envelope.requestId, Command.fromCode(envelope.code), envelope.payload);
    }

    static void writeResponse(
            DataOutputStream output,
            long requestId,
            Status status,
            byte[] payload,
            byte[] secret
    ) throws IOException {
        writeEnvelope(output, TYPE_RESPONSE, requestId, status.code, payload, secret);
    }

    static Response readResponse(DataInputStream input, byte[] secret) throws IOException, ProtocolException {
        Envelope envelope = readEnvelope(input, secret);
        if (envelope.type != TYPE_RESPONSE) throw ProtocolException.malformed("expected_response");
        return new Response(envelope.requestId, Status.fromCode(envelope.code), envelope.payload);
    }

    static byte[] requireSecret(byte[] secret) {
        if (secret == null || secret.length != SECRET_BYTES) {
            throw new IllegalArgumentException("DirectShell probe secret must contain exactly 32 bytes");
        }
        return secret.clone();
    }

    private static void writeEnvelope(
            DataOutputStream output,
            byte type,
            long requestId,
            int code,
            byte[] payload,
            byte[] secret
    ) throws IOException {
        byte[] authenticated = authenticatedBody(type, requestId, code, payload);
        byte[] mac = mac(requireSecret(secret), authenticated);
        output.writeInt(authenticated.length + mac.length);
        output.write(authenticated);
        output.write(mac);
        output.flush();
    }

    private static Envelope readEnvelope(DataInputStream input, byte[] secret) throws IOException, ProtocolException {
        int frameLength = input.readInt();
        if (frameLength < MIN_FRAME_BYTES || frameLength > MAX_FRAME_BYTES) {
            throw ProtocolException.malformed("frame_length");
        }
        byte[] frame = new byte[frameLength];
        input.readFully(frame);
        int bodyLength = frame.length - MAC_BYTES;
        byte[] body = Arrays.copyOf(frame, bodyLength);
        byte[] actualMac = Arrays.copyOfRange(frame, bodyLength, frame.length);
        byte[] expectedMac = mac(requireSecret(secret), body);
        if (!MessageDigest.isEqual(actualMac, expectedMac)) {
            throw ProtocolException.unauthenticated();
        }

        DataInputStream bodyInput = new DataInputStream(new ByteArrayInputStream(body));
        int magic = bodyInput.readInt();
        short version = bodyInput.readShort();
        byte type = bodyInput.readByte();
        long requestId = bodyInput.readLong();
        int code = Byte.toUnsignedInt(bodyInput.readByte());
        int payloadLength = bodyInput.readInt();
        if (magic != MAGIC || version != VERSION || requestId <= 0L) {
            throw ProtocolException.malformed("header");
        }
        if (type != TYPE_REQUEST && type != TYPE_RESPONSE) {
            throw ProtocolException.malformed("frame_type");
        }
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES ||
                payloadLength != bodyInput.available()) {
            throw ProtocolException.malformed("payload_length");
        }
        byte[] payload = new byte[payloadLength];
        bodyInput.readFully(payload);
        return new Envelope(type, requestId, code, payload);
    }

    private static byte[] authenticatedBody(byte type, long requestId, int code, byte[] payload) throws IOException {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        if (safePayload.length > MAX_PAYLOAD_BYTES || requestId <= 0L || code < 0 || code > 255) {
            throw new IllegalArgumentException("DirectShell probe frame is outside its bounded contract");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES + safePayload.length);
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(MAGIC);
        output.writeShort(VERSION);
        output.writeByte(type);
        output.writeLong(requestId);
        output.writeByte(code);
        output.writeInt(safePayload.length);
        output.write(safePayload);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] mac(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static final class Envelope {
        final byte type;
        final long requestId;
        final int code;
        final byte[] payload;

        Envelope(byte type, long requestId, int code, byte[] payload) {
            this.type = type;
            this.requestId = requestId;
            this.code = code;
            this.payload = payload;
        }
    }

    static final class RequestOrder {
        private long lastAcceptedRequestId;

        boolean accept(long requestId) {
            if (requestId <= lastAcceptedRequestId) return false;
            lastAcceptedRequestId = requestId;
            return true;
        }
    }

    static final class ProtocolException extends Exception {
        enum Kind {
            MALFORMED,
            UNAUTHENTICATED,
            INTERNAL,
        }

        final Kind kind;

        private ProtocolException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        static ProtocolException malformed(String detail) {
            return new ProtocolException(Kind.MALFORMED, detail, null);
        }

        static ProtocolException unauthenticated() {
            return new ProtocolException(Kind.UNAUTHENTICATED, "authentication_failed", null);
        }

        static ProtocolException internal(Throwable cause) {
            return new ProtocolException(Kind.INTERNAL, "authentication_unavailable", cause);
        }
    }
}
