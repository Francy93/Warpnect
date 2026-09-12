package io.warpnect.debug.directshell;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal AF_INET socket wrapper for the DEBUG-only DirectShell control probe. */
final class DirectShellProbeIpv4Socket implements AutoCloseable {
    private static final int CONTROL_CALL_TIMEOUT_MILLIS = 3_000;

    private final FileDescriptor descriptor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final DataInputStream input;
    private final DataOutputStream output;

    private DirectShellProbeIpv4Socket(FileDescriptor descriptor) {
        this.descriptor = descriptor;
        this.input = new DataInputStream(new DescriptorInputStream(descriptor));
        this.output = new DataOutputStream(new DescriptorOutputStream(descriptor));
    }

    static FileDescriptor bindListener(int port) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);
            Os.setsockoptInt(descriptor, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1);
            Os.bind(descriptor, ipv4Loopback(), port);
            Os.listen(descriptor, 1);
            return descriptor;
        } catch (ErrnoException | SocketException exception) {
            closeQuietly(descriptor);
            throw asIOException("DirectShell probe listener bind failed", exception);
        }
    }

    static DirectShellProbeIpv4Socket accept(FileDescriptor listener, int timeoutMillis)
            throws IOException {
        try {
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = listener;
            pollfd.events = (short) OsConstants.POLLIN;
            int ready = Os.poll(new StructPollfd[] {pollfd}, timeoutMillis);
            if (ready == 0 || (pollfd.revents & OsConstants.POLLIN) == 0) return null;
            FileDescriptor descriptor = Os.accept(listener, null);
            return new DirectShellProbeIpv4Socket(descriptor);
        } catch (ErrnoException | SocketException exception) {
            throw asIOException("DirectShell probe listener accept failed", exception);
        }
    }

    static DirectShellProbeIpv4Socket connect(int port) throws IOException {
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0);
            Os.connect(descriptor, ipv4Loopback(), port);
            return new DirectShellProbeIpv4Socket(descriptor);
        } catch (ErrnoException | SocketException exception) {
            closeQuietly(descriptor);
            throw asIOException("DirectShell probe loopback connect failed", exception);
        }
    }

    DataInputStream input() {
        return input;
    }

    DataOutputStream output() {
        return output;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try {
            Os.close(descriptor);
        } catch (ErrnoException exception) {
            throw asIOException("DirectShell probe socket close failed", exception);
        }
    }

    static void closeListener(FileDescriptor descriptor) throws IOException {
        if (descriptor == null) return;
        try {
            Os.close(descriptor);
        } catch (ErrnoException exception) {
            throw asIOException("DirectShell probe listener close failed", exception);
        }
    }

    private static InetAddress ipv4Loopback() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    }

    private static void closeQuietly(FileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            Os.close(descriptor);
        } catch (ErrnoException ignored) {
            // The original bind or connect failure remains authoritative.
        }
    }

    private static IOException asIOException(String message, Exception exception) {
        return new IOException(message + ": " + exception.getMessage(), exception);
    }

    private static void await(FileDescriptor descriptor, short events, String operation) throws IOException {
        try {
            StructPollfd pollfd = new StructPollfd();
            pollfd.fd = descriptor;
            pollfd.events = events;
            if (Os.poll(new StructPollfd[] {pollfd}, CONTROL_CALL_TIMEOUT_MILLIS) == 0) {
                throw new SocketTimeoutException("DirectShell probe socket " + operation + " timed out");
            }
        } catch (ErrnoException exception) {
            throw asIOException("DirectShell probe socket " + operation + " poll failed", exception);
        }
    }

    private static final class DescriptorInputStream extends InputStream {
        private final FileDescriptor descriptor;

        DescriptorInputStream(FileDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public int read() throws IOException {
            byte[] oneByte = new byte[1];
            int count = read(oneByte, 0, 1);
            return count == -1 ? -1 : Byte.toUnsignedInt(oneByte[0]);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            try {
                await(descriptor, (short) OsConstants.POLLIN, "read");
                int count = Os.read(descriptor, buffer, offset, length);
                return count == 0 ? -1 : count;
            } catch (ErrnoException | InterruptedIOException exception) {
                throw asIOException("DirectShell probe socket read failed", exception);
            }
        }
    }

    private static final class DescriptorOutputStream extends OutputStream {
        private final FileDescriptor descriptor;

        DescriptorOutputStream(FileDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            int written = 0;
            while (written < length) {
                try {
                    await(descriptor, (short) OsConstants.POLLOUT, "write");
                    int count = Os.write(descriptor, buffer, offset + written, length - written);
                    if (count <= 0) throw new EOFException("DirectShell probe socket write closed");
                    written += count;
                } catch (ErrnoException | InterruptedIOException exception) {
                    throw asIOException("DirectShell probe socket write failed", exception);
                }
            }
        }
    }
}
