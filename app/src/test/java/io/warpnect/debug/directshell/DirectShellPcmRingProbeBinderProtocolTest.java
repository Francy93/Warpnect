package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class DirectShellPcmRingProbeBinderProtocolTest {
    private static final byte[] SECRET = secret();
    private static final long GENERATION = 314159L;
    private static final int REGION_BYTES = 8256;
    private static final int SLOT_COUNT = 8;
    private static final int PAYLOAD_CAPACITY_BYTES = 960;

    @Test
    public void validatesOneFreshDescriptorSet() {
        byte[] proof = proof(GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES);

        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.OK,
                validate(false, GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                        DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES, proof)
        );
        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.GENERATION_REPLAY_REJECTED,
                validate(true, GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                        DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES, proof)
        );
    }

    @Test
    public void rejectsStaleGenerationAndInvalidMetadata() {
        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.GENERATION_REJECTED,
                validate(false, GENERATION - 1L, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                        DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES,
                        proof(GENERATION - 1L, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                                DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES))
        );
        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.METADATA_REJECTED,
                validate(false, GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                        DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES + 1,
                        proof(GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                                DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES + 1))
        );
    }

    @Test
    public void rejectsInvalidMagicAndProof() {
        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                validate(false, GENERATION, 0, DirectShellPcmRingProbeBinderProtocol.VERSION,
                        REGION_BYTES, proof(GENERATION, 0,
                                DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES))
        );
        assertEquals(
                DirectShellPcmRingProbeBinderProtocol.Result.AUTH_REJECTED,
                validate(false, GENERATION, DirectShellPcmRingProbeBinderProtocol.MAGIC,
                        DirectShellPcmRingProbeBinderProtocol.VERSION, REGION_BYTES,
                        new byte[DirectShellPcmRingProbeBinderProtocol.PROOF_BYTES])
        );
    }

    private static DirectShellPcmRingProbeBinderProtocol.Result validate(
            boolean issued,
            long requestedGeneration,
            int magic,
            int version,
            int regionBytes,
            byte[] proof
    ) {
        return DirectShellPcmRingProbeBinderProtocol.validate(
                GENERATION,
                issued,
                REGION_BYTES,
                SLOT_COUNT,
                PAYLOAD_CAPACITY_BYTES,
                magic,
                version,
                requestedGeneration,
                regionBytes,
                SLOT_COUNT,
                PAYLOAD_CAPACITY_BYTES,
                proof,
                SECRET
        );
    }

    private static byte[] proof(long generation, int magic, int version, int regionBytes) {
        return DirectShellPcmRingProbeBinderProtocol.createProof(
                SECRET,
                generation,
                magic,
                version,
                regionBytes,
                SLOT_COUNT,
                PAYLOAD_CAPACITY_BYTES
        );
    }

    private static byte[] secret() {
        byte[] bytes = new byte[DirectShellProbeProtocol.SECRET_BYTES];
        Arrays.fill(bytes, (byte) 0x51);
        assertTrue(bytes.length == DirectShellProbeProtocol.SECRET_BYTES);
        return bytes;
    }
}
