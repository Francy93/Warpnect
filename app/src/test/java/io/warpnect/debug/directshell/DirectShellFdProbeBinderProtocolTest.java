package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DirectShellFdProbeBinderProtocolTest {
    private static final byte[] SECRET = sequence(0x31);

    @Test
    public void currentGenerationWithCorrectProofIsAcceptedOnce() {
        long generation = 91L;
        byte[] proof = DirectShellFdProbeBinderProtocol.createProof(
                SECRET,
                generation,
                DirectShellFdProbeBinderProtocol.MAGIC,
                DirectShellFdProbeBinderProtocol.VERSION,
                DirectShellFdProbeBinderProtocol.REGION_BYTES
        );

        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.OK,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        proof,
                        SECRET
                )
        );
        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.GENERATION_REPLAY_REJECTED,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        true,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        proof,
                        SECRET
                )
        );
    }

    @Test
    public void wrongGenerationAndMetadataAreRejectedBeforeDescriptorIssue() {
        long generation = 19L;
        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.GENERATION_REJECTED,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation + 1L,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        new byte[DirectShellFdProbeBinderProtocol.PROOF_BYTES],
                        SECRET
                )
        );
        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.METADATA_REJECTED,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES + 1,
                        new byte[DirectShellFdProbeBinderProtocol.PROOF_BYTES],
                        SECRET
                )
        );
        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        0,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        new byte[DirectShellFdProbeBinderProtocol.PROOF_BYTES],
                        SECRET
                )
        );
    }

    @Test
    public void proofBindsTheGenerationAndSecret() {
        long generation = 52L;
        byte[] proof = DirectShellFdProbeBinderProtocol.createProof(
                SECRET,
                generation,
                DirectShellFdProbeBinderProtocol.MAGIC,
                DirectShellFdProbeBinderProtocol.VERSION,
                DirectShellFdProbeBinderProtocol.REGION_BYTES
        );
        byte[] otherSecret = sequence(0x51);

        assertEquals(
                DirectShellFdProbeBinderProtocol.Result.AUTH_REJECTED,
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        proof,
                        otherSecret
                )
        );
        proof[0] ^= 0x01;
        assertFalse(
                DirectShellFdProbeBinderProtocol.validate(
                        generation,
                        false,
                        DirectShellFdProbeBinderProtocol.MAGIC,
                        DirectShellFdProbeBinderProtocol.VERSION,
                        generation,
                        DirectShellFdProbeBinderProtocol.REGION_BYTES,
                        proof,
                        SECRET
                ) == DirectShellFdProbeBinderProtocol.Result.OK
        );
        assertTrue(proof.length == DirectShellFdProbeBinderProtocol.PROOF_BYTES);
    }

    private static byte[] sequence(int first) {
        byte[] output = new byte[DirectShellProbeProtocol.SECRET_BYTES];
        for (int index = 0; index < output.length; index++) output[index] = (byte) (first + index);
        return output;
    }
}
