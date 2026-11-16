package io.warpnect.debug.directshell;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

/** Session-only streaming mode. Result serialization never runs on the PCM worker. */
final class DirectShellMicrophoneFeedPump implements AutoCloseable {
    private final Closeable connection;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final LongSupplier clock;
    private final byte[] secret;
    private final long generation;
    private final DirectShellMicrophoneDestinationPump destination;
    private final DirectShellNativeMicrophoneClock nativeDestination;
    private DirectShellMicrophoneResultChannel resultChannel;
    private Runnable configureOwner = () -> {};
    private LongSupplier cpuClock = () -> 0;
    private final DirectShellMicrophoneFeedTotals totals = new DirectShellMicrophoneFeedTotals();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean destinationStarted = new AtomicBoolean();
    private Thread resultWriter;
    private Thread destinationOwner;
    private Consumer<String> diagnostics = ignored -> {};
    private final String[] samples = new String[68];
    private int sampleCount;
    private long previousReadNs;
    private long maxReadGapNs;
    private long maxProcessingNs;
    private long maxResponseNs;
    private final AtomicBoolean reported = new AtomicBoolean();
    private final long[] clockEvents = new long[4096 * 5];
    private int clockEventCount;

    DirectShellMicrophoneFeedPump(DirectShellProbeIpv4Socket socket, byte[] secret, long generation,
            DirectShellMicrophoneDestinationPump.Sink sink) throws IOException {
        this(socket.input(), socket.output(), socket::close, secret, generation, sink, System::nanoTime);
        configureOwner = () -> android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        cpuClock = android.os.Debug::threadCpuTimeNanos;
        socket.enableMicrophoneWrites();
    }

    DirectShellMicrophoneFeedPump(DirectShellProbeIpv4Socket socket, byte[] secret, long generation,
            DirectShellRemoteMicrophonePolicy policy, DirectShellMicrophoneResultChannel results) throws IOException {
        this(socket.input(), socket.output(), socket::close, secret, generation,
                new DirectShellMicrophoneDestinationPump.Sink() {
                    public int write(short[] pcm, int frames, long enqueuedNs) {
                        return policy.feed(generation, pcm, frames, enqueuedNs);
                    }
                    public int reject(long enqueuedNs, long nowNs) { return policy.reject(enqueuedNs, nowNs); }
                    public void silence() { policy.supplySilence(); }
                }, System::nanoTime, policy.nativeClock());
        configureOwner = () -> android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        cpuClock = android.os.Debug::threadCpuTimeNanos;
        resultChannel = results;
        socket.enableMicrophoneWrites();
    }

    void setDiagnostics(Consumer<String> diagnostics) { this.diagnostics = diagnostics; }

    DirectShellMicrophoneFeedPump(DataInputStream input, DataOutputStream output, Closeable connection,
            byte[] secret, long generation, DirectShellMicrophoneFeedResult.Writer sink, LongSupplier clock) {
        this(input, output, connection, secret, generation, sink, clock, null);
    }

    private DirectShellMicrophoneFeedPump(DataInputStream input, DataOutputStream output, Closeable connection,
            byte[] secret, long generation, DirectShellMicrophoneFeedResult.Writer sink, LongSupplier clock,
            DirectShellNativeMicrophoneClock nativeDestination) {
        this.connection = connection;
        this.input = input;
        this.output = output;
        this.clock = clock;
        this.secret = DirectShellProbeProtocol.requireSecret(secret);
        this.generation = generation;
        this.nativeDestination = nativeDestination;
        DirectShellMicrophoneDestinationPump.Sink destinationSink;
        if (sink instanceof DirectShellMicrophoneDestinationPump.Sink) {
            destinationSink = (DirectShellMicrophoneDestinationPump.Sink) sink;
        } else {
            destinationSink = new DirectShellMicrophoneDestinationPump.Sink() {
                public int write(short[] pcm, int frames, long enqueuedNs) { return sink.write(pcm, frames, enqueuedNs); }
                public int reject(long enqueuedNs, long nowNs) { return 0; }
                public void silence() { }
            };
        }
        destination = nativeDestination == null ?
                new DirectShellMicrophoneDestinationPump(destinationSink, clock, (id, result) -> totals.complete(result)) : null;
    }

    void run(DirectShellProbeProtocol.RequestOrder order) throws IOException {
        resultWriter = new Thread(this::reply, "RemoteMicResults");
        resultWriter.start();
        startDestination();
        try {
            while (!closed.get()) {
                DirectShellProbeProtocol.Request request;
                try {
                    request = DirectShellProbeProtocol.readRequest(input, secret);
                } catch (DirectShellProbeProtocol.ProtocolException invalid) {
                    throw new IOException("invalid_authenticated_feed", invalid);
                }
                long readNs = System.nanoTime();
                if (previousReadNs != 0) maxReadGapNs = Math.max(maxReadGapNs, readNs - previousReadNs);
                previousReadNs = readNs;
                if (request.command != DirectShellProbeProtocol.Command.MICROPHONE_FEED || !order.accept(request.requestId)) {
                    throw new IOException("invalid_stream_request");
                }
                DirectShellMicrophoneFrame frame;
                try {
                    frame = DirectShellMicrophoneFrame.decode(request.payload, generation);
                } finally {
                    Arrays.fill(request.payload, (byte) 0);
                }
                if (nativeDestination == null) destination.offer(request.requestId, frame);
                else nativeDestination.offer(frame);
                long ended = System.nanoTime();
                maxProcessingNs = Math.max(maxProcessingNs, ended - readNs);
                if (ended - readNs >= 20_000_000L) record("REMOTE_MIC_STREAM_PCM request_id=" + request.requestId +
                        " read_ns=" + readNs + " ended_ns=" + ended +
                        " admitted=true");
            }
        } catch (IOException failure) {
            record("REMOTE_MIC_STREAM_FAILURE reason=" + failure.getMessage());
            throw failure;
        } finally {
            close();
        }
    }

    synchronized void startDestination() throws IOException {
        if (closed.get()) throw new IOException("destination_closed");
        if (!destinationStarted.compareAndSet(false, true)) return;
        if (nativeDestination != null) return; // AAudio already owns the activated destination callback.
        destinationOwner = new Thread(this::supply, "RemoteMicDestination");
        destinationOwner.start();
    }

    private void supply() {
        try {
            configureOwner.run();
            long next = System.nanoTime();
            while (!closed.get()) {
                long remaining = next - System.nanoTime();
                if (remaining > 0) { LockSupport.parkNanos(remaining); continue; }
                long begin = System.nanoTime();
                long cpu = cpuClock.getAsLong();
                destination.tick();
                long end = System.nanoTime();
                if (clockEventCount < 4096) {
                    int offset = clockEventCount++ * 5;
                    clockEvents[offset] = next;
                    clockEvents[offset + 1] = begin;
                    clockEvents[offset + 2] = end;
                    clockEvents[offset + 3] = cpuClock.getAsLong() - cpu;
                    clockEvents[offset + 4] = Thread.currentThread().getId();
                }
                next += DirectShellMicrophoneDestinationPump.PERIOD_NS;
                long now = System.nanoTime();
                // A stall never causes a catch-up burst or extends a PCM frame's deadline.
                if (next < now) next = now + DirectShellMicrophoneDestinationPump.PERIOD_NS;
            }
        } catch (Exception failure) {
            record("REMOTE_MIC_DESTINATION_FAILURE type=" + failure.getClass().getSimpleName());
            close();
        }
    }

    private void reply() {
        try {
            long previous = 0;
            while (!closed.get()) {
                Thread.sleep(50);
                DirectShellMicrophoneFeedTotals.Snapshot completed = snapshot();
                if (completed.completed() == previous) continue;
                long started = System.nanoTime();
                if (resultChannel != null) resultChannel.publish(completed);
                else DirectShellProbeProtocol.writeResponse(output, completed.completed(), DirectShellProbeProtocol.Status.OK,
                            completed.encode().getBytes(StandardCharsets.UTF_8), secret);
                previous = completed.completed();
                long ended = System.nanoTime();
                maxResponseNs = Math.max(maxResponseNs, ended - started);
                if (ended - started >= 20_000_000L) record("REMOTE_MIC_STREAM_ACK completed=" + completed.completed() +
                        " started_ns=" + started + " ended_ns=" + ended);
            }
        } catch (Exception failure) {
            close();
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { connection.close(); } catch (IOException ignored) { }
            if (resultChannel != null) resultChannel.close();
            if (resultWriter != null) resultWriter.interrupt();
            if (destinationOwner != null) destinationOwner.interrupt();
        }
        // Joining happens on the connection owner, never reciprocally between the workers.
        if (Thread.currentThread() != resultWriter && Thread.currentThread() != destinationOwner) {
            join(destinationOwner);
            join(resultWriter);
            if (nativeDestination == null) destination.close();
            else nativeDestination.close();
            Arrays.fill(secret, (byte) 0);
            if (reported.compareAndSet(false, true)) {
                diagnostics.accept("REMOTE_MIC_STREAM_RELEASE generation=" + generation +
                        " result_transport=" + (resultChannel == null ? "TEST_STREAM" : "SESSION_LOOPBACK_UDP") +
                        " max_read_gap_ns=" + maxReadGapNs + " max_processing_ns=" + maxProcessingNs +
                        " max_response_ns=" + maxResponseNs + " threads_alive=" +
                        ((resultWriter != null && resultWriter.isAlive()) ||
                        (destinationOwner != null && destinationOwner.isAlive())) + " " +
                        (nativeDestination == null ? destination.diagnostics() : nativeDestination.diagnostics()) +
                        " totals=" + snapshot().encode());
                for (int i = 0; i < sampleCount; i++) diagnostics.accept(samples[i]);
                for (int i = 0; i < clockEventCount; i++) {
                    int offset = i * 5;
                    diagnostics.accept("REMOTE_MIC_CLOCK deadline_ns=" + clockEvents[offset] +
                            ";begin_ns=" + clockEvents[offset + 1] + ";end_ns=" + clockEvents[offset + 2] +
                            ";cpu_ns=" + clockEvents[offset + 3]);
                }
            }
        }
    }

    private synchronized void record(String sample) {
        if (sampleCount < samples.length) samples[sampleCount++] = sample;
    }

    private DirectShellMicrophoneFeedTotals.Snapshot snapshot() {
        return nativeDestination == null ? totals.snapshot() : nativeDestination.totals();
    }

    private static void join(Thread thread) {
        if (thread == null) return;
        try { thread.join(2000); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
    }

}
