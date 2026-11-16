package io.warpnect.debug.directshell;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DirectShellNativeMicrophoneClockTest {
    @Test public void exactPolicyAddressIsRequiredWithoutHardcodedDeviceId() {
        assertTrue(DirectShellNativeMicrophoneClock.ownsOutput(25, "owned:mixr:0", "owned:mixr:0"));
        assertFalse(DirectShellNativeMicrophoneClock.ownsOutput(25, "old:mixr:0", "owned:mixr:0"));
    }

    @Test public void speakerOrUnknownAddressCanNeverBecomeInjectionOutput() {
        assertFalse(DirectShellNativeMicrophoneClock.ownsOutput(2, "owned:mixr:0", "owned:mixr:0"));
        assertFalse(DirectShellNativeMicrophoneClock.ownsOutput(25, "", ""));
        assertFalse(DirectShellNativeMicrophoneClock.ownsOutput(25, null, "owned:mixr:0"));
        assertFalse(DirectShellNativeMicrophoneClock.ownsOutput(25, "owned:mixr:0", null));
    }

    @Test public void ownedOutputSelectionUsesRuntimeIdentityNotDeviceNumber() throws Exception {
        assertEquals(731, DirectShellNativeMicrophoneClock.selectOutputId(new int[] {1, 44, 731},
                new int[] {2, 25, 25}, new String[] {"", "previous:mixr:0", "current:mixr:0"}, "current:mixr:0"));
    }

    @Test public void duplicateOwnedOutputsFailClosed() {
        assertThrows(java.io.IOException.class, () -> DirectShellNativeMicrophoneClock.selectOutputId(
                new int[] {3, 4}, new int[] {25, 25}, new String[] {"owned", "owned"}, "owned"));
    }

    @Test public void absentOrInvalidOwnedDeviceCannotFallBackToSpeaker() {
        assertThrows(java.io.IOException.class, () -> DirectShellNativeMicrophoneClock.selectOutputId(
                new int[] {5, 0}, new int[] {2, 25}, new String[] {"owned", "owned"}, "owned"));
    }

    @Test public void inconsistentFrameworkViewIsRejected() {
        assertThrows(java.io.IOException.class, () -> DirectShellNativeMicrophoneClock.selectOutputId(
                new int[] {5}, new int[] {}, new String[] {"owned"}, "owned"));
    }
}
