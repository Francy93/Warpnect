package io.warpnect.debug.directshell;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneFeedPumpTest {
    private static final byte[] SECRET = new byte[32];

    @Test public void blockedAuthenticatedReplyDoesNotBlockPcmBeyondTheOld64Credits() throws Exception {
        PipedInputStream incoming = new PipedInputStream(4096);
        PipedOutputStream requests = new PipedOutputStream(incoming);
        CountDownLatch twoWrites = new CountDownLatch(8);
        CountDownLatch releaseReply = new CountDownLatch(1);
        CountDownLatch finishedReplies = new CountDownLatch(1);
        AtomicInteger clockTicks = new AtomicInteger();
        ByteArrayOutputStream results = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes, int offset, int length) {
                try { assertTrue(releaseReply.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return; }
                super.write(bytes, offset, length);
                finishedReplies.countDown();
            }
        };
        DirectShellMicrophoneFeedPump pump = new DirectShellMicrophoneFeedPump(
                new DataInputStream(incoming), new DataOutputStream(results), incoming, SECRET, 73,
                new DirectShellMicrophoneDestinationPump.Sink() {
                    public int write(short[] pcm, int frames, long enqueued) { twoWrites.countDown(); return frames; }
                    public int reject(long enqueue, long now) { return 0; }
                    public void silence() { clockTicks.incrementAndGet(); }
                }, () -> 1_000_000_000L);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try { pump.run(new DirectShellProbeProtocol.RequestOrder()); }
            catch (IOException expectedEof) { error.set(expectedEof); }
        });
        owner.start();
        try {
            DataOutputStream output = new DataOutputStream(requests);
            for (int id = 1; id <= 128; id++) {
                DirectShellProbeProtocol.writeRequest(output, id, DirectShellProbeProtocol.Command.MICROPHONE_FEED,
                        payload(73), SECRET);
            }
            assertTrue("PCM still serializes behind reply", twoWrites.await(2, TimeUnit.SECONDS));
            assertEquals(0, results.size());
            long until = System.nanoTime() + 1_000_000_000L;
            while (clockTicks.get() < 10 && System.nanoTime() < until) Thread.sleep(5);
            assertTrue("ACK blocked the destination clock", clockTicks.get() >= 10);
            releaseReply.countDown();
            assertTrue(finishedReplies.await(2, TimeUnit.SECONDS));
            DirectShellMicrophoneFeedTotals.Snapshot last = null;
            until = System.nanoTime() + 1_000_000_000L;
            do {
                Thread.sleep(20);
                DataInputStream replies = new DataInputStream(new ByteArrayInputStream(results.toByteArray()));
                last = null;
                while (replies.available() > 0) {
                    DirectShellProbeProtocol.Response response = DirectShellProbeProtocol.readResponse(replies, SECRET);
                    last = DirectShellMicrophoneFeedTotals.decode(new String(response.payload,
                            java.nio.charset.StandardCharsets.UTF_8), last, 128, 128 * 240);
                    assertEquals(response.requestId, last.completed());
                }
            } while ((last == null || last.completed() < 128) && System.nanoTime() < until);
            assertNotNull(last);
            assertEquals(128, last.completed());
            long accounted = 0;
            for (int i = 1; i <= 5; i++) accounted += last.values[i];
            assertEquals(128 * 240, accounted);
        } finally {
            releaseReply.countDown();
            requests.close();
            owner.join(3000);
            pump.close();
        }
        assertFalse(owner.isAlive());
        assertTrue(error.get() instanceof EOFException);
    }

    @Test public void wrongSecretAndGenerationCannotReachAudioTrack() throws Exception {
        for (boolean wrongSecret : new boolean[]{true, false}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] key = SECRET.clone();
            if (wrongSecret) key[0] = 1;
            DirectShellProbeProtocol.writeRequest(new DataOutputStream(bytes), 1,
                    DirectShellProbeProtocol.Command.MICROPHONE_FEED, payload(wrongSecret ? 73 : 74), key);
            AtomicInteger writes = new AtomicInteger();
            DirectShellMicrophoneFeedPump pump = new DirectShellMicrophoneFeedPump(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    new DataOutputStream(new ByteArrayOutputStream()), () -> {}, SECRET, 73,
                    (pcm, frames, age) -> { writes.incrementAndGet(); return frames; }, () -> 1_000_000_000L);
            try { pump.run(new DirectShellProbeProtocol.RequestOrder()); fail(); }
            catch (IOException | IllegalArgumentException expected) { }
            finally { pump.close(); }
            assertEquals(0, writes.get());
        }
    }

    @Test public void duplicateOrReorderedRequestCannotWriteSecondPcmBlock() throws Exception {
        for (long[] ids : new long[][]{{1, 1}, {2, 1}}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (long id : ids) DirectShellProbeProtocol.writeRequest(new DataOutputStream(bytes), id,
                    DirectShellProbeProtocol.Command.MICROPHONE_FEED, payload(73), SECRET);
            AtomicInteger writes = new AtomicInteger();
            DirectShellMicrophoneFeedPump pump = new DirectShellMicrophoneFeedPump(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    new DataOutputStream(new ByteArrayOutputStream()), () -> {}, SECRET, 73,
                    (pcm, frames, age) -> { writes.incrementAndGet(); return frames; }, () -> 1_000_000_000L);
            try { pump.run(new DirectShellProbeProtocol.RequestOrder()); fail(); }
            catch (IOException expected) { assertEquals("invalid_stream_request", expected.getMessage()); }
            finally { pump.close(); }
            // EOF/invalid ingress may cancel the previously admitted block before its next tick.
            assertTrue(writes.get() <= 1);
        }
    }

    private static byte[] payload(long generation) {
        return ByteBuffer.allocate(500).order(ByteOrder.LITTLE_ENDIAN).putLong(generation).putInt(240)
                .putLong(1_000_000_000L).array();
    }
}
