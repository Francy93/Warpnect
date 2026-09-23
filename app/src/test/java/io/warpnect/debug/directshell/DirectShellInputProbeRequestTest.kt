package io.warpnect.debug.directshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectShellInputProbeRequestTest {
    @Test
    fun parsesLegacyAndTargetedTouchGesturesWithinTheBoundedDebugContract() {
        assertEquals(
            DirectShellInputProbeRequest.TouchGesture(count = 2),
            DirectShellInputProbeRequest.parseTouchGesture("count=2".encodeToByteArray()),
        )
        assertEquals(
            DirectShellInputProbeRequest.TouchGesture(count = 1, targetX = 540, targetY = 1_170),
            DirectShellInputProbeRequest.parseTouchGesture("count=1;x=540;y=1170".encodeToByteArray()),
        )
    }

    @Test
    fun rejectsMalformedOrOutOfBoundsTargetedGestures() {
        assertNull(DirectShellInputProbeRequest.parseTouchGesture("count=0;x=1;y=1".encodeToByteArray()))
        assertNull(DirectShellInputProbeRequest.parseTouchGesture("count=1;x=-1;y=1".encodeToByteArray()))
        assertNull(DirectShellInputProbeRequest.parseTouchGesture("count=1;x=16385;y=1".encodeToByteArray()))
        assertNull(DirectShellInputProbeRequest.parseTouchGesture("count=1;y=1;x=1".encodeToByteArray()))
    }

    @Test
    fun encodesTheOnlyAcceptedTargetedTouchGrammar() {
        assertEquals("count=3;x=12;y=34", DirectShellInputProbeRequest.touchGesturePayload(3, 12, 34))
    }
}
