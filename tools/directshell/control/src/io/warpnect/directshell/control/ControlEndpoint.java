package io.warpnect.directshell.control;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Separate Control role: one loopback channel, one generation, no executor or secondary port. */
final class ControlEndpoint {
    static final int PING = 1;
    static final int GET_RUNTIME_INFO = 2;
    static final int SHUTDOWN = 3;
    static final int OK = 0;
    static final int OUT_OF_ORDER = 1;
    static final int UNSUPPORTED = 2;
    static final int BAD_STATE = 5;
    static final int REQUEST = 1;
    static final int RESPONSE = 2;
    static final int MAC_SIZE = 32;
    static final int HEADER_SIZE = 28;
    static final int REQUEST_SIZE = HEADER_SIZE + MAC_SIZE;
    static final int MAX_PAYLOAD = 256;

    interface Ready {
        void listener(int port);
    }

    static byte[] identity(int uid, int pid, int api, long generation, String digest) {
        if (uid != 2000 || pid <= 0 || api < 26 || generation <= 0 || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("control_identity");
        }
        return ascii("role=Control\nuid=2000\npid=" + pid + "\napi=" + api + "\ngeneration=" + generation +
                "\nartifact_sha256=" + digest);
    }

    static byte[] sessionKey(byte[] master, long generation, String digest) {
        if (master.length != MAC_SIZE || generation <= 0 || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("control_session");
        }
        return hmac(master, ascii("warpnect-control-v2\n" + generation + "\n" + digest));
    }

    static void serve(byte[] key, long generation, byte[] identity, LongSupplier clock, long cutoff, Ready ready)
            throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (Selector selector = Selector.open(); ServerSocketChannel listener = ServerSocketChannel.open()) {
            remaining(clock, cutoff);
            listener.configureBlocking(false);
            listener.bind(new InetSocketAddress(loopback, 0), 1);
            SelectionKey accept = listener.register(selector, SelectionKey.OP_ACCEPT);
            ready.listener(((InetSocketAddress) listener.getLocalAddress()).getPort());
            SocketChannel next;
            while ((next = listener.accept()) == null) await(selector, clock, cutoff);
            try (SocketChannel client = next) {
                accept.cancel();
                listener.close();
                if (!loopback.equals(((InetSocketAddress) client.getRemoteAddress()).getAddress())) {
                    throw new IOException("control_peer");
                }
                client.configureBlocking(false);
                SelectionKey owned = client.register(selector, 0);
                exchange(selector, client, owned, key, generation, identity, clock, cutoff);
            }
        }
    }

    private static void exchange(Selector selector, SocketChannel client, SelectionKey owned, byte[] key,
            long generation, byte[] identity, LongSupplier clock, long cutoff) throws IOException {
        long previous = 0;
        boolean identified = false;
        for (int requestCount = 0; requestCount < 16; requestCount++) {
            ByteBuffer size = ByteBuffer.allocate(Integer.BYTES);
            transfer(selector, client, owned, size, false, clock, cutoff);
            if (size.getInt(0) != REQUEST_SIZE) throw new IOException("control_frame_size");
            ByteBuffer request = ByteBuffer.allocate(REQUEST_SIZE);
            transfer(selector, client, owned, request, false, clock, cutoff);
            byte[] raw = request.array();
            byte[] body = Arrays.copyOf(raw, HEADER_SIZE);
            byte[] actualMac = Arrays.copyOfRange(raw, HEADER_SIZE, raw.length);
            if (!MessageDigest.isEqual(actualMac, hmac(key, body))) throw new IOException("control_authentication");
            request.flip();
            if (request.getInt() != 0x574E4450 || request.getShort() != 2 || request.get() != REQUEST) {
                throw new IOException("control_profile");
            }
            long id = request.getLong();
            int command = Byte.toUnsignedInt(request.get());
            if (id <= 0 || request.getLong() != generation || request.getInt() != 0) {
                throw new IOException("control_generation_or_payload");
            }
            int status;
            byte[] payload = new byte[0];
            boolean stop = false;
            if (id <= previous) {
                status = OUT_OF_ORDER;
            } else {
                previous = id;
                status = OK;
                switch (command) {
                    case PING:
                        payload = ascii("PONG");
                        break;
                    case GET_RUNTIME_INFO:
                        payload = identity;
                        identified = true;
                        break;
                    case SHUTDOWN:
                        if (identified) {
                            payload = ascii("SHUTDOWN_ACCEPTED");
                            stop = true;
                        } else {
                            status = BAD_STATE;
                        }
                        break;
                    default:
                        status = UNSUPPORTED;
                }
            }
            transfer(selector, client, owned, frame(RESPONSE, id, status, generation, payload, key),
                    true, clock, cutoff);
            // A written reply does not acknowledge socket closure or process exit.
            if (stop) return;
        }
        throw new IOException("control_request_limit");
    }

    static ByteBuffer frame(int type, long id, int code, long generation, byte[] payload, byte[] key) {
        if (payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("control_frame_bound");
        byte[] body = ByteBuffer.allocate(HEADER_SIZE + payload.length)
                .putInt(0x574E4450).putShort((short) 2).put((byte) type).putLong(id).put((byte) code)
                .putLong(generation).putInt(payload.length).put(payload).array();
        ByteBuffer result = ByteBuffer.allocate(4 + body.length + MAC_SIZE);
        result.putInt(body.length + MAC_SIZE).put(body).put(hmac(key, body)).flip();
        return result;
    }

    private static void transfer(Selector selector, SocketChannel client, SelectionKey owned, ByteBuffer buffer,
            boolean write, LongSupplier clock, long cutoff) throws IOException {
        owned.interestOps(write ? SelectionKey.OP_WRITE : SelectionKey.OP_READ);
        while (buffer.hasRemaining()) {
            remaining(clock, cutoff);
            if (Thread.currentThread().isInterrupted()) throw new IOException("control_interrupted");
            int count = write ? client.write(buffer) : client.read(buffer);
            if (count < 0) throw new IOException("control_eof");
            if (count == 0) await(selector, clock, cutoff);
        }
    }

    private static void await(Selector selector, LongSupplier clock, long cutoff) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("control_interrupted");
        selector.select(remaining(clock, cutoff));
        selector.selectedKeys().clear();
        remaining(clock, cutoff);
    }

    private static long remaining(LongSupplier clock, long cutoff) throws SocketTimeoutException {
        long time = cutoff - clock.getAsLong();
        if (time <= 0) throw new SocketTimeoutException("control_deadline");
        return time;
    }

    static byte[] hmac(byte[] key, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("control_hmac_unavailable", e);
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private ControlEndpoint() {}
}
