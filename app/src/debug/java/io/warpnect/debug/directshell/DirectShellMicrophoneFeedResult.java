package io.warpnect.debug.directshell;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** One terminal result accounts for every frame, including a partially written block. */
final class DirectShellMicrophoneFeedResult {
    final int accepted;
    final int dropped;
    final int reason;
    final int attempts;
    final int zeroWrites;
    final int partialWrites;

    DirectShellMicrophoneFeedResult(int accepted, int dropped, int reason, int attempts, int zeros, int partials) {
        this.accepted = accepted;
        this.dropped = dropped;
        this.reason = reason;
        this.attempts = attempts;
        this.zeroWrites = zeros;
        this.partialWrites = partials;
    }

    String encode() {
        return accepted + "," + dropped + "," + reason + "," + attempts + "," + zeroWrites + "," + partialWrites;
    }

    static DirectShellMicrophoneFeedResult decode(String payload, int requested) throws IOException {
        try {
            String[] fields = payload.split(",", -1);
            if (fields.length != 6) throw new IllegalArgumentException();
            int[] v = Arrays.stream(fields).mapToInt(Integer::parseInt).toArray();
            if (v[0] < 0 || v[1] < 0 || v[0] + v[1] != requested || v[3] < 0 || v[4] < 0 || v[5] < 0 ||
                    (v[1] == 0 ? v[2] != 0 : !isDrop(v[2]))) throw new IllegalArgumentException();
            return new DirectShellMicrophoneFeedResult(v[0], v[1], v[2], v[3], v[4], v[5]);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid_feed_accounting", invalid);
        }
    }

    interface Writer { int write(short[] pcm, int frames, long enqueuedNs); }
    interface Pause { void await() throws InterruptedException; }

    static DirectShellMicrophoneFeedResult process(DirectShellMicrophoneFrame frame, Writer writer,
            LongSupplier clock, Pause pause) throws IOException, InterruptedException {
        short[] remaining = frame.samples;
        int accepted = 0, attempts = 0, zeros = 0, partials = 0;
        try {
            while (remaining.length > 0) {
                if (!DirectShellMicrophoneFrame.isFresh(frame.enqueuedNs, clock.getAsLong())) {
                    return new DirectShellMicrophoneFeedResult(accepted, remaining.length,
                            DirectShellMicrophoneFrame.DROP_STALE, attempts, zeros, partials);
                }
                int count = writer.write(remaining, remaining.length, frame.enqueuedNs);
                attempts++;
                if (isDrop(count)) {
                    return new DirectShellMicrophoneFeedResult(accepted, remaining.length, count, attempts, zeros, partials);
                }
                if (count < 0 || count > remaining.length) throw new IOException("fatal_feed_write_" + count);
                if (count == 0) {
                    zeros++;
                    pause.await();
                } else {
                    accepted += count;
                    if (count < remaining.length) partials++;
                    short[] tail = Arrays.copyOfRange(remaining, count, remaining.length);
                    if (remaining != frame.samples) Arrays.fill(remaining, (short) 0);
                    remaining = tail;
                }
            }
            return new DirectShellMicrophoneFeedResult(accepted, 0, 0, attempts, zeros, partials);
        } finally {
            Arrays.fill(remaining, (short) 0);
            Arrays.fill(frame.samples, (short) 0);
        }
    }

    static boolean isDrop(int reason) {
        return reason == DirectShellMicrophoneFrame.DROP_NO_CONSUMER ||
                reason == DirectShellMicrophoneFrame.DROP_STALE ||
                reason == DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION ||
                reason == DirectShellMicrophoneFrame.DROP_CAPACITY;
    }
}
