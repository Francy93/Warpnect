package io.warpnect.debug.directshell;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneFeedTest {
    @Test public void outcomeLedgerRetainsOnlyBoundedUnconfirmedCountsNotPcm() throws Exception {
        DirectShellMicrophoneFeedWindow ledger = DirectShellMicrophoneFeedWindow.resultLedger();
        for (int i = 1; i <= 64; i++) assertTrue(ledger.admit(i, 240, System.nanoTime()));
        assertFalse(ledger.admit(65, 240, System.nanoTime() - 41_000_000L));
        assertEquals(64, ledger.size());
        assertTrue(ledger.complete(1));
        assertTrue(ledger.admit(65, 240, System.nanoTime()));
        assertEquals(15360, ledger.close());
    }
    @Test public void delayedResultsAllowBoundedProgressWithoutClaimingAcceptance() throws Exception {
        DirectShellMicrophoneFeedWindow window = new DirectShellMicrophoneFeedWindow();
        for (int i = 1; i <= 8; i++) assertTrue(window.admit(i, 240, System.nanoTime()));
        assertEquals(8, window.size());
        assertEquals(8, window.highWater());
        assertFalse(window.admit(9, 240, System.nanoTime() - 41_000_000));
        assertEquals(1, window.next().id);
        assertTrue(window.complete(1));
        assertTrue(window.admit(9, 240, System.nanoTime()));
        assertEquals(1920, window.close());
    }

    @Test public void frameBoundAppliesEvenWithLargePackets() throws Exception {
        DirectShellMicrophoneFeedWindow window = new DirectShellMicrophoneFeedWindow();
        assertTrue(window.admit(1, 960, System.nanoTime()));
        assertTrue(window.admit(2, 960, System.nanoTime()));
        assertFalse(window.admit(3, 1, System.nanoTime() - 41_000_000));
        assertEquals(2, window.size());
        window.close();
    }

    @Test public void wrongOrOutOfOrderResultDoesNotReleaseAnotherCredit() throws Exception {
        DirectShellMicrophoneFeedWindow window = new DirectShellMicrophoneFeedWindow();
        window.admit(41, 240, System.nanoTime());
        window.admit(42, 240, System.nanoTime());
        try { window.complete(42); fail(); } catch (IOException expected) { }
        assertEquals(2, window.size());
        assertTrue(window.complete(41));
        window.close();
    }

    @Test public void releaseCancelsInflightAndLateAckCannotAffectNewOwner() throws Exception {
        DirectShellMicrophoneFeedWindow old = new DirectShellMicrophoneFeedWindow();
        old.admit(1, 240, System.nanoTime());
        assertEquals(240, old.close());
        DirectShellMicrophoneFeedWindow fresh = new DirectShellMicrophoneFeedWindow();
        fresh.admit(1, 240, System.nanoTime());
        assertFalse(old.complete(1));
        assertEquals(1, fresh.size());
        assertNull(old.next());
        assertFalse(old.admit(2, 240, System.nanoTime()));
        fresh.close();
    }

    @Test public void partialWritesRetryOnlyTheirRemainderBeforeNextBlock() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        DirectShellMicrophoneFrame frame = frame(3, clock.get(), new short[]{11, 22, 33, 44});
        int[] invocation = {0};
        DirectShellMicrophoneFeedResult result = DirectShellMicrophoneFeedResult.process(frame, (samples, count, age) -> {
            assertEquals(clock.get(), age);
            if (invocation[0]++ == 0) { assertArrayEquals(new short[]{11,22,33,44}, samples); return 2; }
            assertArrayEquals(new short[]{33,44}, samples);
            return 2;
        }, clock::get, () -> fail());
        assertEquals(4, result.accepted);
        assertEquals(0, result.dropped);
        assertEquals(1, result.partialWrites);
        assertArrayEquals(new short[4], frame.samples);
        assertEquals(4, DirectShellMicrophoneFeedResult.decode(result.encode(), 4).accepted);
    }

    @Test public void expiryAfterPartialWriteDropsOnlyStaleRemainder() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        DirectShellMicrophoneFrame frame = frame(3, clock.get(), new short[240]);
        DirectShellMicrophoneFeedResult result = DirectShellMicrophoneFeedResult.process(frame, (samples, count, age) -> {
            clock.addAndGet(41_000_000L);
            return 17;
        }, clock::get, () -> fail());
        assertEquals(17, result.accepted);
        assertEquals(223, result.dropped);
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, result.reason);
        assertEquals(17, DirectShellMicrophoneFeedResult.decode(result.encode(), 240).accepted);
    }

    @Test public void zeroWritesCannotRetryForeverOrResetFreshness() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        DirectShellMicrophoneFeedResult result = DirectShellMicrophoneFeedResult.process(
                frame(3, clock.get(), new short[240]), (samples, count, age) -> 0,
                clock::get, () -> clock.addAndGet(10_000_000L));
        assertEquals(0, result.accepted);
        assertEquals(240, result.dropped);
        assertEquals(5, result.zeroWrites);
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, result.reason);
    }

    @Test public void noConsumerAndWindowDropsAreNotReportedAsWritten() throws Exception {
        for (int reason : new int[]{DirectShellMicrophoneFrame.DROP_NO_CONSUMER,
                DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION}) {
            DirectShellMicrophoneFeedResult result = DirectShellMicrophoneFeedResult.process(
                    frame(3, 1_000_000_000L, new short[240]), (samples, count, age) -> reason,
                    () -> 1_000_000_000L, () -> fail());
            assertEquals(0, result.accepted);
            assertEquals(240, result.dropped);
            assertEquals(reason, result.reason);
        }
    }

    @Test public void invalidWriteAndFalseFullAcceptanceAreFatal() throws Exception {
        try {
            DirectShellMicrophoneFeedResult.process(frame(3, 1_000_000_000L, new short[240]),
                    (samples, count, age) -> -1, () -> 1_000_000_000L, () -> fail());
            fail();
        } catch (IOException expected) { }
        try { DirectShellMicrophoneFeedResult.decode("240,10,0,1,0,0", 240); fail(); }
        catch (IOException expected) { }
    }

    @Test public void wrongGenerationNeverReachesWriter() {
        byte[] payload = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).putLong(7).putInt(1)
                .putLong(1_000_000_000L).putShort((short) 123).array();
        try { DirectShellMicrophoneFrame.decode(payload, 8); fail(); }
        catch (IllegalArgumentException expected) { assertEquals("stale_generation", expected.getMessage()); }
    }

    private static DirectShellMicrophoneFrame frame(long generation, long age, short[] samples) {
        ByteBuffer bytes = ByteBuffer.allocate(20 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putLong(generation).putInt(samples.length).putLong(age);
        bytes.asShortBuffer().put(samples);
        return DirectShellMicrophoneFrame.decode(bytes.array(), generation);
    }
}
