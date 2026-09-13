package io.warpnect.debug.directshell;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Tiny diagnostic layout used only by the DirectShell FD handoff probe. */
final class DirectShellFdSharedMemoryLayout {
    static final int MAGIC = 0x574E4644; // "WNFD"
    static final int VERSION = 1;
    static final int HEADER_BYTES = 64;
    static final int PAYLOAD_BYTES = 256;

    private static final int OFFSET_MAGIC = 0;
    private static final int OFFSET_VERSION = 4;
    private static final int OFFSET_GENERATION = 8;
    private static final int OFFSET_SEQUENCE = 16;
    private static final int OFFSET_PATTERN = 24;
    private static final int OFFSET_PAYLOAD_LENGTH = 28;
    private static final int OFFSET_CHECKSUM = 32;

    private DirectShellFdSharedMemoryLayout() {}

    enum Pattern {
        APP_A(0x41505041),
        SHELL_B(0x53484C42),
        APP_C(0x41505043);

        final int code;

        Pattern(int code) {
            this.code = code;
        }

        static Pattern fromCode(int code) {
            for (Pattern pattern : values()) {
                if (pattern.code == code) return pattern;
            }
            return null;
        }
    }

    enum Verification {
        OK,
        REGION_TOO_SMALL,
        MAGIC_MISMATCH,
        VERSION_MISMATCH,
        GENERATION_MISMATCH,
        SEQUENCE_MISMATCH,
        PATTERN_MISMATCH,
        PAYLOAD_LENGTH_INVALID,
        CHECKSUM_MISMATCH,
        CONCURRENT_WRITE
    }

    static void initialize(ByteBuffer memory, long generationId) {
        write(memory, generationId, 1L, Pattern.APP_A);
    }

    static void write(ByteBuffer memory, long generationId, long sequence, Pattern pattern) {
        if (generationId <= 0L || sequence <= 0L || pattern == null) {
            throw new IllegalArgumentException("invalid diagnostic shared-memory write");
        }
        ByteBuffer buffer = ordered(memory);
        requireCapacity(buffer);
        byte[] payload = payload(pattern, generationId, sequence);
        buffer.putLong(OFFSET_SEQUENCE, 0L);
        buffer.putInt(OFFSET_MAGIC, MAGIC);
        buffer.putInt(OFFSET_VERSION, VERSION);
        buffer.putLong(OFFSET_GENERATION, generationId);
        buffer.putInt(OFFSET_PATTERN, pattern.code);
        buffer.putInt(OFFSET_PAYLOAD_LENGTH, payload.length);
        for (int index = 0; index < payload.length; index++) {
            buffer.put(HEADER_BYTES + index, payload[index]);
        }
        buffer.putInt(OFFSET_CHECKSUM, checksum(generationId, sequence, pattern.code, payload));
        // Publishing the sequence last makes an in-progress write distinguishable to the reader.
        buffer.putLong(OFFSET_SEQUENCE, sequence);
    }

    static Snapshot snapshot(ByteBuffer memory) {
        ByteBuffer buffer = ordered(memory);
        if (buffer.capacity() < HEADER_BYTES) {
            return new Snapshot(Verification.REGION_TOO_SMALL, 0L, 0L, null, 0);
        }
        long firstSequence = buffer.getLong(OFFSET_SEQUENCE);
        int magic = buffer.getInt(OFFSET_MAGIC);
        int version = buffer.getInt(OFFSET_VERSION);
        long generation = buffer.getLong(OFFSET_GENERATION);
        Pattern pattern = Pattern.fromCode(buffer.getInt(OFFSET_PATTERN));
        int payloadLength = buffer.getInt(OFFSET_PAYLOAD_LENGTH);
        int storedChecksum = buffer.getInt(OFFSET_CHECKSUM);
        long finalSequence = buffer.getLong(OFFSET_SEQUENCE);
        if (firstSequence != finalSequence || firstSequence <= 0L) {
            return new Snapshot(Verification.CONCURRENT_WRITE, generation, finalSequence, pattern, storedChecksum);
        }
        if (magic != MAGIC) {
            return new Snapshot(Verification.MAGIC_MISMATCH, generation, finalSequence, pattern, storedChecksum);
        }
        if (version != VERSION) {
            return new Snapshot(Verification.VERSION_MISMATCH, generation, finalSequence, pattern, storedChecksum);
        }
        if (payloadLength <= 0 || payloadLength > PAYLOAD_BYTES ||
                buffer.capacity() < HEADER_BYTES + payloadLength) {
            return new Snapshot(Verification.PAYLOAD_LENGTH_INVALID, generation, finalSequence, pattern, storedChecksum);
        }
        if (pattern == null) {
            return new Snapshot(Verification.PATTERN_MISMATCH, generation, finalSequence, null, storedChecksum);
        }
        byte[] payload = new byte[payloadLength];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = buffer.get(HEADER_BYTES + index);
        }
        if (storedChecksum != checksum(generation, finalSequence, pattern.code, payload)) {
            return new Snapshot(Verification.CHECKSUM_MISMATCH, generation, finalSequence, pattern, storedChecksum);
        }
        return new Snapshot(Verification.OK, generation, finalSequence, pattern, storedChecksum);
    }

    static Verification verify(
            ByteBuffer memory,
            long expectedGeneration,
            long expectedSequence,
            Pattern expectedPattern
    ) {
        Snapshot snapshot = snapshot(memory);
        if (snapshot.verification != Verification.OK) return snapshot.verification;
        if (snapshot.generationId != expectedGeneration) return Verification.GENERATION_MISMATCH;
        if (snapshot.sequence != expectedSequence) return Verification.SEQUENCE_MISMATCH;
        return snapshot.pattern == expectedPattern ? Verification.OK : Verification.PATTERN_MISMATCH;
    }

    static String summary(ByteBuffer memory) {
        Snapshot snapshot = snapshot(memory);
        return "verification=" + snapshot.verification.name() +
                " generation=" + snapshot.generationId +
                " sequence=" + snapshot.sequence +
                " pattern=" + (snapshot.pattern == null ? "none" : snapshot.pattern.name()) +
                " checksum=0x" + Integer.toUnsignedString(snapshot.checksum, 16);
    }

    private static byte[] payload(Pattern pattern, long generationId, long sequence) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        long seed = generationId ^ (sequence * 0x9E3779B97F4A7C15L) ^ Integer.toUnsignedLong(pattern.code);
        for (int index = 0; index < payload.length; index++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            payload[index] = (byte) (seed ^ pattern.code ^ index);
        }
        return payload;
    }

    private static int checksum(long generationId, long sequence, int patternCode, byte[] payload) {
        CRC32 crc = new CRC32();
        updateLong(crc, generationId);
        updateLong(crc, sequence);
        updateInt(crc, patternCode);
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    private static void updateLong(CRC32 crc, long value) {
        for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            crc.update((int) (value >>> shift) & 0xFF);
        }
    }

    private static void updateInt(CRC32 crc, int value) {
        for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
            crc.update(value >>> shift & 0xFF);
        }
    }

    private static ByteBuffer ordered(ByteBuffer memory) {
        if (memory == null) throw new IllegalArgumentException("memory is null");
        return memory.duplicate().order(ByteOrder.nativeOrder());
    }

    private static void requireCapacity(ByteBuffer memory) {
        if (memory.capacity() < HEADER_BYTES + PAYLOAD_BYTES) {
            throw new IllegalArgumentException("memory is too small for DirectShell FD probe layout");
        }
    }

    static final class Snapshot {
        final Verification verification;
        final long generationId;
        final long sequence;
        final Pattern pattern;
        final int checksum;

        Snapshot(Verification verification, long generationId, long sequence, Pattern pattern, int checksum) {
            this.verification = verification;
            this.generationId = generationId;
            this.sequence = sequence;
            this.pattern = pattern;
            this.checksum = checksum;
        }
    }
}
