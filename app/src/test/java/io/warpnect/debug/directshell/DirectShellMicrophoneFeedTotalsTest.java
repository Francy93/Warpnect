package io.warpnect.debug.directshell;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneFeedTotalsTest {
    @Test public void delayedObservationCoalescesWithoutPcmCreditsOrMemoryGrowth() throws Exception {
        DirectShellMicrophoneFeedTotals totals = new DirectShellMicrophoneFeedTotals();
        for (int i = 0; i < 10000; i++) totals.complete(new DirectShellMicrophoneFeedResult(240, 0, 0, 1, 0, 0));
        DirectShellMicrophoneFeedTotals.Snapshot snapshot = totals.snapshot();
        assertEquals(9, snapshot.values.length);
        assertEquals(10000, snapshot.completed());
        assertEquals(2400000, snapshot.values[1]);
        assertEquals(2400000, DirectShellMicrophoneFeedTotals.decode(snapshot.encode(), null, 10000, 2400000).values[1]);
    }
    @Test public void actualWritesAndEachIntentionalDropRemainDistinct() throws Exception {
        DirectShellMicrophoneFeedTotals totals = new DirectShellMicrophoneFeedTotals();
        totals.complete(new DirectShellMicrophoneFeedResult(17, 223, DirectShellMicrophoneFrame.DROP_STALE, 1, 0, 1));
        totals.complete(new DirectShellMicrophoneFeedResult(0, 240, DirectShellMicrophoneFrame.DROP_NO_CONSUMER, 0, 0, 0));
        totals.complete(new DirectShellMicrophoneFeedResult(0, 240, DirectShellMicrophoneFrame.DROP_CAPACITY, 0, 0, 0));
        totals.complete(new DirectShellMicrophoneFeedResult(0, 240, DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION, 0, 0, 0));
        long[] v = totals.snapshot().values;
        assertArrayEquals(new long[]{4,17,240,223,240,240,1,0,1}, v);
        assertEquals(4, DirectShellMicrophoneFeedTotals.decode(totals.snapshot().encode(), null, 4, 960).completed());
    }
    @Test public void replayCounterRollbackAndFabricatedAcceptanceAreRejected() throws Exception {
        DirectShellMicrophoneFeedTotals t = new DirectShellMicrophoneFeedTotals();
        t.complete(new DirectShellMicrophoneFeedResult(240, 0, 0, 1, 0, 0));
        for (String payload : new String[]{t.snapshot().encode(), "TOTAL_V1,2,0,0,0,0,0,1,0,0", "TOTAL_V1,2,9999,0,0,0,0,2,0,0"}) {
            try { DirectShellMicrophoneFeedTotals.decode(payload, t.snapshot(), 2, 480); fail(); }
            catch (IOException expected) { }
        }
    }
    @Test public void aFreshGenerationStartsWithIndependentCounters() throws Exception {
        DirectShellMicrophoneFeedTotals old = new DirectShellMicrophoneFeedTotals();
        old.complete(new DirectShellMicrophoneFeedResult(240, 0, 0, 1, 0, 0));
        DirectShellMicrophoneFeedTotals fresh = new DirectShellMicrophoneFeedTotals();
        assertEquals(0, fresh.snapshot().completed());
        fresh.complete(new DirectShellMicrophoneFeedResult(0, 240, DirectShellMicrophoneFrame.DROP_NO_CONSUMER, 0, 0, 0));
        assertEquals(0, fresh.snapshot().values[1]);
        assertEquals(1, DirectShellMicrophoneFeedTotals.decode(fresh.snapshot().encode(), null, 1, 240).completed());
    }
    @Test public void aSnapshotCannotInventFramesOrMoreWriteOutcomesThanAttempts() throws Exception {
        for (String payload : new String[]{"TOTAL_V1,1,961,0,0,0,0,4,0,0",
                "TOTAL_V1,1,240,0,0,0,0,1,1,1", "TOTAL_V1,1,0,0,0,0,0,0,0,0"}) {
            try { DirectShellMicrophoneFeedTotals.decode(payload, null, 20, 4800); fail(); }
            catch (IOException expected) { }
        }
    }
}
