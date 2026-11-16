package io.warpnect.debug.directshell;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class DirectShellAppMicrophoneStateTest {
    @Test public void authenticatedWindowCarriesExactPolicyAndOwner() throws Exception {
        DirectShellAppMicrophoneState state = DirectShellAppMicrophoneState.parse("APP_V1;7;owned:mixr:0;1234;2", 7);
        assertEquals("owned:mixr:0", state.registration);
        assertEquals(1234, state.sinceNs);
        assertEquals(2, state.closeEpoch);
    }

    @Test public void wrongGenerationUnavailableAndMalformedStateFailClosed() {
        for (String payload : new String[]{"APP_V1;8;owned;1;0", "UNAVAILABLE", "APP_V1;7;;1;0",
                "APP_V1;7;owned;-1;0", "APP_V1;7;owned;1;-1", "APP_V1;7;owned;1;0;extra",
                "APP_V1;7;owned\n;1;0", "APP_V1;7;owned;bad;0"}) {
            assertThrows(IOException.class, () -> DirectShellAppMicrophoneState.parse(payload, 7));
        }
    }

    @Test public void lateObservationOfCloseReopenPreservesNewWindowNotOldPcm() throws Exception {
        DirectShellAppMicrophoneState first = DirectShellAppMicrophoneState.parse("APP_V1;7;owned;100;0", 7);
        DirectShellAppMicrophoneState reopened = DirectShellAppMicrophoneState.parse("APP_V1;7;owned;300;1", 7);
        reopened.requireContinuation(first);
        assertEquals(300, reopened.sinceNs);
        assertEquals(1, reopened.closeEpoch);
    }

    @Test public void policySwapEpochRollbackOrUnobservedWindowRewriteFailClosed() throws Exception {
        DirectShellAppMicrophoneState previous = DirectShellAppMicrophoneState.parse("APP_V1;7;owned;100;2", 7);
        for (String payload : new String[]{"APP_V1;7;different;100;2", "APP_V1;7;owned;100;1",
                "APP_V1;7;owned;200;2", "APP_V1;7;owned;0;2"}) {
            DirectShellAppMicrophoneState changed = DirectShellAppMicrophoneState.parse(payload, 7);
            assertThrows(IOException.class, () -> changed.requireContinuation(previous));
        }
    }
}
