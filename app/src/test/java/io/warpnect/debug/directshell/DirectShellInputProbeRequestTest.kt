package io.warpnect.debug.directshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectShellInputProbeRequestTest {
    @Test
    fun parsesBoundedSequenceCount() {
        assertEquals(1, DirectShellInputProbeRequest.parseCount("count=1".encodeToByteArray()))
        assertEquals(100, DirectShellInputProbeRequest.parseCount("count=100".encodeToByteArray()))
    }

    @Test
    fun rejectsMalformedOrOversizedSequenceCount() {
        assertNull(DirectShellInputProbeRequest.parseCount("count=0".encodeToByteArray()))
        assertNull(DirectShellInputProbeRequest.parseCount("count=101".encodeToByteArray()))
        assertNull(DirectShellInputProbeRequest.parseCount("count=1;extra=x".encodeToByteArray()))
    }
}
