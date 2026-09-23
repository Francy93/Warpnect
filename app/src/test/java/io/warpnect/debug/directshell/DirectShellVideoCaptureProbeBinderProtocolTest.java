package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class DirectShellVideoCaptureProbeBinderProtocolTest {
    private static final byte[] SECRET = secret();
    private static final long GENERATION = 271828L;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    @Test
    public void acceptsOneAuthenticatedSurfaceRequestAndRejectsReplay() {
        byte[] proof = proof(GENERATION, WIDTH, HEIGHT);

        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.OK,
                validate(false, GENERATION, WIDTH, HEIGHT, proof)
        );
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.GENERATION_REPLAY_REJECTED,
                validate(true, GENERATION, WIDTH, HEIGHT, proof)
        );
    }

    @Test
    public void rejectsStaleGenerationAndUnexpectedSurfaceGeometry() {
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.GENERATION_REJECTED,
                validate(false, GENERATION - 1L, WIDTH, HEIGHT, proof(GENERATION - 1L, WIDTH, HEIGHT))
        );
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.METADATA_REJECTED,
                validate(false, GENERATION, WIDTH + 1, HEIGHT, proof(GENERATION, WIDTH + 1, HEIGHT))
        );
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.METADATA_REJECTED,
                validate(false, GENERATION, WIDTH, 0, proof(GENERATION, WIDTH, 0))
        );
    }

    @Test
    public void rejectsUnexpectedProtocolAndMalformedProof() {
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.PROTOCOL_REJECTED,
                DirectShellVideoCaptureProbeBinderProtocol.validate(
                        GENERATION,
                        false,
                        WIDTH,
                        HEIGHT,
                        0,
                        DirectShellVideoCaptureProbeBinderProtocol.VERSION,
                        GENERATION,
                        WIDTH,
                        HEIGHT,
                        proof(GENERATION, WIDTH, HEIGHT),
                        SECRET
                )
        );
        assertEquals(
                DirectShellVideoCaptureProbeBinderProtocol.Result.AUTH_REJECTED,
                validate(false, GENERATION, WIDTH, HEIGHT,
                        new byte[DirectShellVideoCaptureProbeBinderProtocol.PROOF_BYTES])
        );
    }

    private static DirectShellVideoCaptureProbeBinderProtocol.Result validate(
            boolean surfaceIssued,
            long requestedGeneration,
            int width,
            int height,
            byte[] proof
    ) {
        return DirectShellVideoCaptureProbeBinderProtocol.validate(
                GENERATION,
                surfaceIssued,
                WIDTH,
                HEIGHT,
                DirectShellVideoCaptureProbeBinderProtocol.MAGIC,
                DirectShellVideoCaptureProbeBinderProtocol.VERSION,
                requestedGeneration,
                width,
                height,
                proof,
                SECRET
        );
    }

    private static byte[] proof(long generation, int width, int height) {
        return DirectShellVideoCaptureProbeBinderProtocol.createProof(
                SECRET,
                generation,
                DirectShellVideoCaptureProbeBinderProtocol.MAGIC,
                DirectShellVideoCaptureProbeBinderProtocol.VERSION,
                width,
                height
        );
    }

    private static byte[] secret() {
        byte[] bytes = new byte[DirectShellProbeProtocol.SECRET_BYTES];
        Arrays.fill(bytes, (byte) 0x4A);
        assertTrue(bytes.length == DirectShellProbeProtocol.SECRET_BYTES);
        return bytes;
    }
}
