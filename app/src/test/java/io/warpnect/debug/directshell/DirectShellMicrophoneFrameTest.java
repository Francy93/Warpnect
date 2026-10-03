package io.warpnect.debug.directshell;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class DirectShellMicrophoneFrameTest {
    private byte[] frame(long generation, int frames) {
        ByteBuffer b = ByteBuffer.allocate(20 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(generation).putInt(frames).putLong(1000);
        for (int i = 0; i < frames; i++) b.putShort((short) (i - 120));
        return b.array();
    }
    @Test public void fiveMillisecondMonoPacketContains240Frames480Bytes() {
        byte[] packet = frame(12, 240);
        assertEquals(500, packet.length);
        short[] pcm = DirectShellMicrophoneFrame.decode(packet, 12).samples;
        assertEquals(240, pcm.length);
        assertEquals(-120, pcm[0]);
        assertEquals(119, pcm[239]);
    }
    @Test(expected = IllegalArgumentException.class) public void staleGenerationRejected() {
        DirectShellMicrophoneFrame.decode(frame(12, 240), 13);
    }
    @Test(expected = IllegalArgumentException.class) public void oversizedFrameRejected() {
        DirectShellMicrophoneFrame.decode(frame(12, 961), 12);
    }
    @Test(expected = IllegalArgumentException.class) public void emptyFrameRejected() {
        DirectShellMicrophoneFrame.decode(frame(12, 0), 12);
    }
    @Test(expected = IllegalArgumentException.class) public void truncatedPcmRejected() {
        byte[] packet = frame(12, 240);
        DirectShellMicrophoneFrame.decode(java.util.Arrays.copyOf(packet, packet.length - 1), 12);
    }
    @Test(expected = IllegalArgumentException.class) public void otherCapturePresetRejected() {
        new DirectShellRemoteMicrophonePolicy(12, new int[]{6});
    }
    @Test(expected = IllegalArgumentException.class) public void duplicateCapturePresetRejected() {
        new DirectShellRemoteMicrophonePolicy(12, new int[]{1, 1});
    }
    @Test(expected = IllegalArgumentException.class) public void invalidOwnerRejected() {
        new DirectShellRemoteMicrophonePolicy(0, new int[]{1});
    }
    @Test public void freshnessUsesSameDeviceMonotonicAgeAndRejectsStaleOrFuturePcm() {
        assertTrue(DirectShellMicrophoneFrame.isFresh(1000, 1000));
        assertTrue(DirectShellMicrophoneFrame.isFresh(1000, 40_001_000));
        assertFalse(DirectShellMicrophoneFrame.isFresh(1000, 40_001_001));
        assertFalse(DirectShellMicrophoneFrame.isFresh(1000, 999));
        assertFalse(DirectShellMicrophoneFrame.isFresh(0, 1));
    }
    @Test public void ipcAgeSurvivesFrameDecodingRatherThanBeingRenewedOnRetry() {
        assertEquals(1000, DirectShellMicrophoneFrame.decode(frame(12, 240), 12).enqueuedNs);
    }
    @Test public void delayedDeliveryDoesNotAdmitPcmFromBeforeConsumerActivation() {
        assertTrue(DirectShellMicrophoneFrame.isFresh(1000, 2000));
        assertFalse(DirectShellMicrophoneFrame.belongsToConsumerWindow(1000, 1500));
        assertTrue(DirectShellMicrophoneFrame.belongsToConsumerWindow(1500, 1500));
        assertTrue(DirectShellMicrophoneFrame.belongsToConsumerWindow(1501, 1500));
    }
    @Test public void closedAndReopenedConsumerCannotAdmitPreviousWindowPcm() {
        assertTrue(DirectShellMicrophoneFrame.belongsToConsumerWindow(1500, 1000));
        assertFalse(DirectShellMicrophoneFrame.belongsToConsumerWindow(1500, 0));
        assertFalse(DirectShellMicrophoneFrame.belongsToConsumerWindow(1500, 2000));
        assertTrue(DirectShellMicrophoneFrame.belongsToConsumerWindow(2000, 2000));
    }
    @Test public void consumerOwnershipMatchesAndroidRouteNotApplicationUid() {
        assertTrue(DirectShellRemoteMicrophonePolicy.matchesConsumer(25, "mix:1", false, "mix:1"));
        assertFalse(DirectShellRemoteMicrophonePolicy.matchesConsumer(25, "other", false, "mix:1"));
        assertFalse(DirectShellRemoteMicrophonePolicy.matchesConsumer(15, "mix:1", false, "mix:1"));
        assertFalse(DirectShellRemoteMicrophonePolicy.matchesConsumer(25, "mix:1", true, "mix:1"));
        assertFalse(DirectShellRemoteMicrophonePolicy.matchesConsumer(25, "", false, ""));
    }
    @Test public void closedConsumerTransitionDoesNotWriteMaintenanceAgainstPauseOrFlush() {
        assertFalse(DirectShellRemoteMicrophonePolicy.mayMaintainSilence(false, true));
        assertTrue(DirectShellRemoteMicrophonePolicy.mayMaintainSilence(false, false));
    }
    @Test public void activeConsumerNeverReceivesMaintenanceSilenceInPlaceOfRealPcm() {
        assertFalse(DirectShellRemoteMicrophonePolicy.mayMaintainSilence(true, false));
        assertFalse(DirectShellRemoteMicrophonePolicy.mayMaintainSilence(true, true));
    }
}
