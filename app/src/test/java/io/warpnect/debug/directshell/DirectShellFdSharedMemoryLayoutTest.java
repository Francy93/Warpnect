package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;

public final class DirectShellFdSharedMemoryLayoutTest {
    @Test
    public void appShellAppPatternSequenceRoundTrips() {
        ByteBuffer memory = ByteBuffer.allocateDirect(DirectShellFdProbeBinderProtocol.REGION_BYTES);
        long generation = 73L;

        DirectShellFdSharedMemoryLayout.initialize(memory, generation);
        assertEquals(
                DirectShellFdSharedMemoryLayout.Verification.OK,
                DirectShellFdSharedMemoryLayout.verify(
                        memory,
                        generation,
                        1L,
                        DirectShellFdSharedMemoryLayout.Pattern.APP_A
                )
        );
        DirectShellFdSharedMemoryLayout.write(
                memory,
                generation,
                2L,
                DirectShellFdSharedMemoryLayout.Pattern.SHELL_B
        );
        assertEquals(
                DirectShellFdSharedMemoryLayout.Verification.OK,
                DirectShellFdSharedMemoryLayout.verify(
                        memory,
                        generation,
                        2L,
                        DirectShellFdSharedMemoryLayout.Pattern.SHELL_B
                )
        );
        DirectShellFdSharedMemoryLayout.write(
                memory,
                generation,
                3L,
                DirectShellFdSharedMemoryLayout.Pattern.APP_C
        );
        assertEquals(
                DirectShellFdSharedMemoryLayout.Verification.OK,
                DirectShellFdSharedMemoryLayout.verify(
                        memory,
                        generation,
                        3L,
                        DirectShellFdSharedMemoryLayout.Pattern.APP_C
                )
        );
    }

    @Test
    public void checksumAndGenerationCorruptionAreDetected() {
        ByteBuffer memory = ByteBuffer.allocateDirect(DirectShellFdProbeBinderProtocol.REGION_BYTES);
        DirectShellFdSharedMemoryLayout.initialize(memory, 11L);
        memory.put(DirectShellFdSharedMemoryLayout.HEADER_BYTES, (byte) 0x55);
        assertEquals(
                DirectShellFdSharedMemoryLayout.Verification.CHECKSUM_MISMATCH,
                DirectShellFdSharedMemoryLayout.snapshot(memory).verification
        );

        DirectShellFdSharedMemoryLayout.initialize(memory, 11L);
        assertEquals(
                DirectShellFdSharedMemoryLayout.Verification.GENERATION_MISMATCH,
                DirectShellFdSharedMemoryLayout.verify(
                        memory,
                        12L,
                        1L,
                        DirectShellFdSharedMemoryLayout.Pattern.APP_A
                )
        );
    }
}
