package io.warpnect.debug.directshell;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneDestinationPumpTest {
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final List<DirectShellMicrophoneFeedResult> results = new ArrayList<>();
    private final Sink sink = new Sink();
    private final DirectShellMicrophoneDestinationPump pump = new DirectShellMicrophoneDestinationPump(
            sink, now::get, (id, result) -> results.add(result));

    @Test public void noIngressStillSuppliesClockedSilence() throws Exception {
        for (int i = 0; i < 20; i++) { pump.tick(); now.addAndGet(5_000_000); }
        assertEquals(20, sink.silences);
        assertEquals(0, sink.written);
        assertTrue(results.isEmpty());
    }

    @Test public void missingFreshPcmNeverReplaysAfterStall() throws Exception {
        DirectShellMicrophoneFrame old = frame(240);
        pump.offer(1, old);
        now.addAndGet(41_000_000);
        pump.tick();
        assertEquals(0, sink.written);
        assertEquals(1, sink.silences);
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, results.get(0).reason);
        assertArrayEquals(new short[240], old.samples);
        pump.offer(2, frame(240));
        pump.tick();
        assertEquals(240, sink.written);
    }

    @Test public void oneTickFillsOnlyBoundedDestinationCapacityAndNeverRepaysStaleDebt() throws Exception {
        pump.offer(1, frame(960));
        pump.offer(2, frame(960));
        pump.tick();
        assertEquals(960, sink.written);
        assertEquals(1, results.size());
        now.addAndGet(41_000_000);
        pump.tick();
        assertEquals(960, sink.written);
        assertEquals(0, results.get(1).accepted);
        assertEquals(960, results.get(1).dropped);
    }

    @Test public void partialWriteRetriesOnlyUnacceptedFreshTail() throws Exception {
        pump.offer(1, frame(240));
        sink.limit = 17;
        pump.tick();
        sink.limit = 960;
        now.addAndGet(5_000_000);
        pump.tick();
        assertEquals(240, sink.written);
        assertEquals(1, results.get(0).partialWrites);
        assertEquals(0, results.get(0).dropped);
    }

    @Test public void fullDestinationDropsWithoutRetainingOverflowPcm() throws Exception {
        for (int i = 1; i <= 9; i++) pump.offer(i, frame(240));
        assertEquals(1, results.size());
        assertEquals(DirectShellMicrophoneFrame.DROP_CAPACITY, results.get(0).reason);
        for (int i = 0; i < 8; i++) pump.tick();
        pump.tick();
        assertEquals(1920, sink.written);
        assertEquals(9, results.size());
        assertEquals(0, results.get(0).accepted);
    }

    @Test public void absentConsumerDropsRealPcmButKeepsSilenceAutonomous() throws Exception {
        sink.reason = DirectShellMicrophoneFrame.DROP_NO_CONSUMER;
        pump.offer(1, frame(240));
        pump.tick();
        assertEquals(0, sink.written);
        assertEquals(1, sink.silences);
        assertEquals(240, results.get(0).dropped);
        sink.reason = DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION;
        pump.offer(2, frame(240));
        pump.tick();
        assertEquals(DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION, results.get(1).reason);
    }

    @Test public void closeZerosRetainedPcmAndCannotFeedNextGeneration() throws Exception {
        DirectShellMicrophoneFrame old = frame(240);
        pump.offer(1, old);
        pump.close();
        pump.tick();
        assertArrayEquals(new short[240], old.samples);
        assertEquals(0, sink.written);
        assertEquals(0, sink.silences);
        try { pump.offer(2, frame(240)); fail(); } catch (java.io.IOException expected) { }
    }

    @Test public void zeroWriteNeverClaimsAcceptanceAndStillExpires() throws Exception {
        sink.limit = 0;
        pump.offer(1, frame(240));
        pump.tick();
        assertTrue(results.isEmpty());
        now.addAndGet(41_000_000);
        pump.tick();
        assertEquals(0, results.get(0).accepted);
        assertEquals(1, results.get(0).zeroWrites);
    }

    @Test public void acceptedLateIsNotReclassifiedAsUnwritten() throws Exception {
        sink.writeDelayNs = 41_000_000;
        pump.offer(1, frame(240));
        pump.tick();
        assertEquals(240, results.get(0).accepted);
        assertEquals(0, results.get(0).dropped);
        assertTrue(pump.diagnostics().contains("accepted_late_frames=240"));
    }
    @Test public void aPartialCurrentBlockStillCountsTowardTheBoundedEntryLimit() throws Exception {
        pump.offer(1, frame(240));
        sink.limit = 0;
        pump.tick();
        for (int i = 2; i <= 9; i++) pump.offer(i, frame(1));
        assertEquals(1, results.size());
        assertEquals(DirectShellMicrophoneFrame.DROP_CAPACITY, results.get(0).reason);
        pump.close();
    }

    private DirectShellMicrophoneFrame frame(int frames) {
        ByteBuffer b = ByteBuffer.allocate(20 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(73).putInt(frames).putLong(now.get());
        for (int i = 0; i < frames; i++) b.putShort((short) 19);
        return DirectShellMicrophoneFrame.decode(b.array(), 73);
    }

    private final class Sink implements DirectShellMicrophoneDestinationPump.Sink {
        int written, silences, reason;
        int limit = 960;
        long writeDelayNs;
        public int reject(long enqueue, long clock) { return reason; }
        public void silence() { silences++; }
        public int write(short[] pcm, int frames, long enqueue) {
            assertEquals(19, pcm[0]);
            now.addAndGet(writeDelayNs);
            int count = Math.min(frames, limit);
            written += count;
            return count;
        }
    }
}
