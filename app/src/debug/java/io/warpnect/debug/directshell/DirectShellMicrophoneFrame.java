package io.warpnect.debug.directshell;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Fixed mono PCM16 contract; no arbitrary audio format or capture target is accepted. */
final class DirectShellMicrophoneFrame {
    static final int HEADER_BYTES = 20;
    static final long MAX_AGE_NS = 40_000_000L;
    static final int DROP_NO_CONSUMER = -1001;
    static final int DROP_STALE = -1002;
    static final int DROP_CONSUMER_TRANSITION = -1003;
    final short[] samples;
    final long enqueuedNs;

    private DirectShellMicrophoneFrame(short[] samples, long enqueuedNs) {
        this.samples = samples;
        this.enqueuedNs = enqueuedNs;
    }

    static boolean isFresh(long enqueuedNs, long nowNs) {
        long age = nowNs - enqueuedNs;
        return enqueuedNs > 0 && age >= 0 && age <= MAX_AGE_NS;
    }

    static boolean belongsToConsumerWindow(long enqueuedNs, long consumerSinceNs) {
        return consumerSinceNs > 0 && enqueuedNs >= consumerSinceNs;
    }

    static DirectShellMicrophoneFrame decode(byte[] payload, long generation) {
        if (payload == null || payload.length < HEADER_BYTES) throw new IllegalArgumentException("invalid_pcm_frame");
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getLong() != generation) throw new IllegalArgumentException("stale_generation");
        int frames = buffer.getInt();
        long enqueuedNs = buffer.getLong();
        if (enqueuedNs <= 0 || frames <= 0 || frames > 960 || payload.length != HEADER_BYTES + frames * 2) {
            throw new IllegalArgumentException("invalid_pcm_frame");
        }
        short[] samples = new short[frames];
        buffer.asShortBuffer().get(samples);
        return new DirectShellMicrophoneFrame(samples, enqueuedNs);
    }
}
