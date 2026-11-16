package io.warpnect.debug.directshell;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneConsumerWindowTest {
    @Test public void noReaderRejectsRealPcmAndFirstOpenRejectsOlderSpeech() {
        DirectShellMicrophoneConsumerWindow absent = new DirectShellMicrophoneConsumerWindow();
        assertEquals(DirectShellMicrophoneFrame.DROP_NO_CONSUMER, absent.reject(100, 200, 0));
        DirectShellMicrophoneConsumerWindow open = absent.observe(true, 200);
        assertEquals(DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION, open.reject(100, 300, 0));
        assertEquals(0, open.reject(200, 300, 0));
    }
    @Test public void closeAndReopenBetweenTicksStillRequiresOneOwnerReset() {
        DirectShellMicrophoneConsumerWindow first = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        DirectShellMicrophoneConsumerWindow reopened = first.observe(false, 200).observe(true, 300);
        assertEquals(1, reopened.closeEpoch);
        assertEquals(DirectShellMicrophoneFrame.DROP_NO_CONSUMER, reopened.reject(300, 400, 0));
        assertEquals(DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION, reopened.reject(200, 400, 1));
        assertEquals(0, reopened.reject(300, 400, 1));
        assertEquals(0, first.closeEpoch);
        assertEquals(100, first.sinceNs);
    }
    @Test public void unchangedRouteDoesNotMoveTheConsumerWindowOrRequireAnotherReset() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        assertSame(open, open.observe(true, 200));
        DirectShellMicrophoneConsumerWindow closed = open.observe(false, 300);
        assertSame(closed, closed.observe(false, 400));
        assertEquals(1, closed.closeEpoch);
    }
    @Test public void activeReaderNeverExtendsTheFortyMillisecondDeadline() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        assertEquals(0, open.reject(100, 40_000_100, 0));
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, open.reject(100, 40_000_101, 0));
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, open.reject(100, 99, 0));
    }
    @Test public void pcmExpiringDuringBookkeepingNeverReachesTheWriter() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        assertEquals(0, open.reject(100, 36_580_100, 0));
        int result = open.writeIfFresh(100, 0, () -> 46_270_100,
                (pcm, frames, enqueued) -> { fail("expired PCM reached writer"); return 0; }, new short[240], 240);
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, result);
    }
    @Test public void closedOrReopenedConsumerCannotUseAnEarlierAdmission() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        DirectShellMicrophoneConsumerWindow closed = open.observe(false, 200);
        DirectShellMicrophoneFeedResult.Writer forbidden = (pcm, frames, enqueued) -> {
            fail("closed consumer reached writer"); return 0;
        };
        assertEquals(DirectShellMicrophoneFrame.DROP_NO_CONSUMER,
                closed.writeIfFresh(100, 0, () -> 300, forbidden, new short[240], 240));
        assertEquals(DirectShellMicrophoneFrame.DROP_NO_CONSUMER,
                closed.observe(true, 300).writeIfFresh(300, 0, () -> 400, forbidden, new short[240], 240));
    }
    @Test public void freshPartialWriteRetainsOriginalTimestampAndAccounting() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        short[] input = new short[240];
        int result = open.writeIfFresh(100, 0, () -> 40_000_100, (pcm, frames, enqueued) -> {
            assertSame(input, pcm); assertEquals(240, frames); assertEquals(100, enqueued); return 17;
        }, input, 240);
        assertEquals(17, result);
    }
    @Test public void completedFrameworkWriteIsNotFalselyRolledBackIfTheReturnIsLate() {
        DirectShellMicrophoneConsumerWindow open = new DirectShellMicrophoneConsumerWindow().observe(true, 100);
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(39_000_100);
        int result = open.writeIfFresh(100, 0, clock::get, (pcm, frames, enqueued) -> {
            clock.set(49_000_100); return frames;
        }, new short[240], 240);
        assertEquals(240, result);
        assertEquals(DirectShellMicrophoneFrame.DROP_STALE, open.reject(100, clock.get(), 0));
    }
}
