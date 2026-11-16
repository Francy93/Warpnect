package io.warpnect.debug.directshell;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class DirectShellMicrophoneResultChannelTest {
    private static final byte[] SECRET = new byte[32];

    @Test public void skippedSnapshotsRemainCumulativeAndDoNotGrantPcmCredits() throws Exception {
        try (DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73);
                DirectShellMicrophoneResultChannel sender = DirectShellMicrophoneResultChannel.sender(receiver.attachment(), SECRET, 73)) {
            sender.publish(totals(128));
            DirectShellPcmRingProbeClient.Response response = receiver.read(0);
            assertEquals(128, response.requestId);
            DirectShellMicrophoneFeedTotals.Snapshot decoded = DirectShellMicrophoneFeedTotals.decode(
                    response.payload, null, 128, 128 * 240);
            assertEquals(128 * 240, decoded.values[1]);
        }
    }

    @Test public void duplicateSnapshotsCannotReplayCounters() throws Exception {
        try (DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73);
                DirectShellMicrophoneResultChannel sender = DirectShellMicrophoneResultChannel.sender(receiver.attachment(), SECRET, 73)) {
            sender.publish(totals(1));
            sender.publish(totals(2));
            assertEquals(2, receiver.read(1).requestId);
        }
    }

    @Test public void oldSessionNonceCannotAffectNewSessionEvenIfPortIsReused() throws Exception {
        byte[] old;
        try (DirectShellMicrophoneResultChannel previous = DirectShellMicrophoneResultChannel.receiver(SECRET, 73)) {
            old = previous.attachment();
        }
        try (DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73)) {
            byte[] current = receiver.attachment();
            ByteBuffer.wrap(old).putInt(9, ByteBuffer.wrap(current).getInt(9));
            try (DirectShellMicrophoneResultChannel stale = DirectShellMicrophoneResultChannel.sender(old, SECRET, 73);
                    DirectShellMicrophoneResultChannel fresh = DirectShellMicrophoneResultChannel.sender(current, SECRET, 73)) {
                stale.publish(totals(999));
                fresh.publish(totals(2));
                assertEquals(2, receiver.read(0).requestId);
            }
        }
    }

    @Test public void wrongAuthenticationCannotPublishTotals() throws Exception {
        byte[] wrong = SECRET.clone();
        wrong[0] = 1;
        try (DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73);
                DirectShellMicrophoneResultChannel sender = DirectShellMicrophoneResultChannel.sender(receiver.attachment(), wrong, 73)) {
            sender.publish(totals(1));
            try { receiver.read(0); fail(); }
            catch (IOException expected) { assertEquals("invalid_authenticated_result", expected.getMessage()); }
        }
    }

    @Test public void attachmentRejectsStaleGenerationAndInvalidEndpoint() throws Exception {
        try (DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73)) {
            for (int kind = 0; kind < 4; kind++) {
                byte[] payload = receiver.attachment();
                if (kind == 0) ByteBuffer.wrap(payload).putLong(1, 74);
                if (kind == 1) ByteBuffer.wrap(payload).putInt(9, 22);
                if (kind == 2) payload[0] = 0;
                if (kind == 3) payload = new byte[1];
                try (DirectShellMicrophoneResultChannel ignored = DirectShellMicrophoneResultChannel.sender(payload, SECRET, 73)) {
                    fail();
                } catch (IOException expected) { }
            }
        }
    }

    @Test public void ownerCloseUnblocksResultReceiverWithoutWaitingForPcm() throws Exception {
        DirectShellMicrophoneResultChannel receiver = DirectShellMicrophoneResultChannel.receiver(SECRET, 73);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            entered.countDown();
            try { receiver.read(0); } catch (Throwable failure) { result.set(failure); }
        });
        reader.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            receiver.close();
            reader.join(1000);
            assertFalse(reader.isAlive());
            assertTrue(result.get() instanceof IOException);
        } finally { receiver.close(); reader.join(1000); }
    }

    private static DirectShellMicrophoneFeedTotals.Snapshot totals(long completed) {
        return new DirectShellMicrophoneFeedTotals.Snapshot(new long[]{completed, completed * 240, 0, 0, 0, 0, completed, 0, 0});
    }
}
