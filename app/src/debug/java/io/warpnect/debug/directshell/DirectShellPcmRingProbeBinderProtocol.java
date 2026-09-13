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
 * Bounded DEBUG-only contract for transferring exactly one production PCM ring descriptor set.
 *
 * <p>The endpoint is deliberately not a generic FD broker. A request can only acquire the active
 * generation's SharedMemory, producer notify-write end, and producer ack-read end after the
 * framework Binder caller is verified as shell and this proof validates.</p>
 */
final class DirectShellPcmRingProbeBinderProtocol {
    static final int MAGIC = 0x574E5052; // "WNPR"
    static final int VERSION = 1;
    static final int PROOF_BYTES = 32;
    static final String PROVIDER_AUTHORITY = "io.warpnect.debug.directshell.pcmringprobe";
    static final String PROVIDER_METHOD_OPEN_PCM_RING = "open_pcm_ring_v1";

    static final String KEY_MAGIC = "magic";
    static final String KEY_VERSION = "version";
    static final String KEY_GENERATION_ID = "generation_id";
    static final String KEY_REGION_BYTES = "region_bytes";
    static final String KEY_SLOT_COUNT = "slot_count";
    static final String KEY_PAYLOAD_CAPACITY_BYTES = "payload_capacity_bytes";
    static final String KEY_PROOF = "proof";
    static final String KEY_RESULT_CODE = "result_code";
    static final String KEY_DETAIL = "detail";
    static final String KEY_SHARED_MEMORY = "shared_memory";
    static final String KEY_NOTIFY_WRITE_FD = "notify_write_fd";
    static final String KEY_ACK_READ_FD = "ack_read_fd";

    private static final byte[] PROOF_LABEL = new byte[] {
            'W', 'N', 'P', 'R', '_', 'O', 'P', 'E', 'N', '_', 'V', '1'
    };

    private DirectShellPcmRingProbeBinderProtocol() {}

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
            int regionBytes,
            int slotCount,
            int payloadCapacityBytes
    ) {
        if (generationId <= 0L) throw new IllegalArgumentException("generation must be positive");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(PROOF_LABEL.length + 28);
            DataOutputStream output = new DataOutputStream(bytes);
            output.write(PROOF_LABEL);
            output.writeInt(magic);
            output.writeInt(version);
            output.writeLong(generationId);
            output.writeInt(regionBytes);
            output.writeInt(slotCount);
            output.writeInt(payloadCapacityBytes);
            output.flush();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(DirectShellProbeProtocol.requireSecret(secret), "HmacSHA256"));
            return mac.doFinal(bytes.toByteArray());
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create DirectShell PCM ring proof", exception);
        }
    }

    static Result validate(
            long activeGenerationId,
            boolean descriptorAlreadyIssued,
            int expectedRegionBytes,
            int expectedSlotCount,
            int expectedPayloadCapacityBytes,
            int magic,
            int version,
            long requestedGenerationId,
            int regionBytes,
            int slotCount,
            int payloadCapacityBytes,
            byte[] proof,
            byte[] secret
    ) {
        if (activeGenerationId <= 0L) return Result.NOT_ARMED;
        if (magic != MAGIC || version != VERSION) return Result.PROTOCOL_REJECTED;
        if (regionBytes != expectedRegionBytes || slotCount != expectedSlotCount ||
                payloadCapacityBytes != expectedPayloadCapacityBytes) {
            return Result.METADATA_REJECTED;
        }
        if (requestedGenerationId != activeGenerationId) return Result.GENERATION_REJECTED;
        if (proof == null || proof.length != PROOF_BYTES) return Result.AUTH_REJECTED;
        byte[] expected = createProof(
                secret,
                requestedGenerationId,
                magic,
                version,
                regionBytes,
                slotCount,
                payloadCapacityBytes
        );
        try {
            if (!MessageDigest.isEqual(proof, expected)) return Result.AUTH_REJECTED;
            return descriptorAlreadyIssued ? Result.GENERATION_REPLAY_REJECTED : Result.OK;
        } finally {
            Arrays.fill(expected, (byte) 0);
        }
    }
}
