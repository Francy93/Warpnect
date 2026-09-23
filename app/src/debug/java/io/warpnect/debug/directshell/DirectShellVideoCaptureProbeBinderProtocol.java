package io.warpnect.debug.directshell;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Bounded DEBUG-only contract that gives a shell process one app-owned encoder Surface for one
 * DirectShell capture generation. It is deliberately not a generic Surface or FD broker.
 */
final class DirectShellVideoCaptureProbeBinderProtocol {
    static final int MAGIC = 0x574E5653; // "WNVS"
    static final int VERSION = 1;
    static final int PROOF_BYTES = 32;
    static final String PROVIDER_AUTHORITY = "io.warpnect.debug.directshell.videocaptureprobe";
    static final String PROVIDER_METHOD_OPEN_ENCODER_SURFACE = "open_encoder_surface_v1";

    static final String KEY_MAGIC = "magic";
    static final String KEY_VERSION = "version";
    static final String KEY_GENERATION_ID = "generation_id";
    static final String KEY_WIDTH = "width";
    static final String KEY_HEIGHT = "height";
    static final String KEY_PROOF = "proof";
    static final String KEY_RESULT_CODE = "result_code";
    static final String KEY_DETAIL = "detail";
    static final String KEY_SURFACE = "encoder_surface";

    private static final byte[] PROOF_LABEL = new byte[] {
            'W', 'N', 'V', 'S', '_', 'O', 'P', 'E', 'N', '_', 'V', '1'
    };

    private DirectShellVideoCaptureProbeBinderProtocol() {}

    enum Result {
        OK(0),
        CALLER_UID_REJECTED(1),
        PROTOCOL_REJECTED(2),
        AUTH_REJECTED(3),
        GENERATION_REJECTED(4),
        METADATA_REJECTED(5),
        GENERATION_REPLAY_REJECTED(6),
        NOT_ARMED(7),
        INTERNAL_ERROR(8);

        final int code;

        Result(int code) {
            this.code = code;
        }

        static Result fromCode(int code) {
            for (Result value : values()) {
                if (value.code == code) return value;
            }
            return INTERNAL_ERROR;
        }
    }

    static byte[] createProof(
            byte[] secret,
            long generationId,
            int magic,
            int version,
            int width,
            int height
    ) {
        if (generationId <= 0L) throw new IllegalArgumentException("generation must be positive");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(PROOF_LABEL.length + 24);
            DataOutputStream output = new DataOutputStream(bytes);
            output.write(PROOF_LABEL);
            output.writeInt(magic);
            output.writeInt(version);
            output.writeLong(generationId);
            output.writeInt(width);
            output.writeInt(height);
            output.flush();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DirectShellProbeProtocol.requireSecret(secret), "HmacSHA256"));
            return mac.doFinal(bytes.toByteArray());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create DirectShell video Surface proof", exception);
        }
    }

    static Result validate(
            long activeGenerationId,
            boolean surfaceAlreadyIssued,
            int expectedWidth,
            int expectedHeight,
            int magic,
            int version,
            long requestedGenerationId,
            int width,
            int height,
            byte[] proof,
            byte[] secret
    ) {
        if (activeGenerationId <= 0L) return Result.NOT_ARMED;
        if (magic != MAGIC || version != VERSION) return Result.PROTOCOL_REJECTED;
        if (width != expectedWidth || height != expectedHeight || width <= 0 || height <= 0) {
            return Result.METADATA_REJECTED;
        }
        if (requestedGenerationId != activeGenerationId) return Result.GENERATION_REJECTED;
        if (proof == null || proof.length != PROOF_BYTES) return Result.AUTH_REJECTED;
        byte[] expected = createProof(secret, requestedGenerationId, magic, version, width, height);
        try {
            if (!MessageDigest.isEqual(proof, expected)) return Result.AUTH_REJECTED;
            return surfaceAlreadyIssued ? Result.GENERATION_REPLAY_REJECTED : Result.OK;
        } finally {
            Arrays.fill(expected, (byte) 0);
        }
    }
}
