package io.warpnect.debug.directshell;

import java.io.IOException;

/** Constant-size authenticated outcome mailbox, not PCM credits or a queue of ACKs. */
final class DirectShellMicrophoneFeedTotals {
    private final long[] values = new long[9];

    synchronized void complete(DirectShellMicrophoneFeedResult result) {
        values[0]++;
        values[1] += result.accepted;
        if (result.dropped != 0) {
            int index;
            switch (result.reason) {
                case DirectShellMicrophoneFrame.DROP_NO_CONSUMER: index = 2; break;
                case DirectShellMicrophoneFrame.DROP_STALE: index = 3; break;
                case DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION: index = 4; break;
                case DirectShellMicrophoneFrame.DROP_CAPACITY: index = 5; break;
                default: throw new IllegalArgumentException("unaccounted_destination_drop");
            }
            values[index] += result.dropped;
        }
        values[6] += result.attempts;
        values[7] += result.zeroWrites;
        values[8] += result.partialWrites;
    }

    synchronized Snapshot snapshot() { return new Snapshot(values.clone()); }

    static Snapshot decode(String payload, Snapshot previous, long sentCalls, long sentFrames) throws IOException {
        try {
            String[] fields = payload.split(",", -1);
            if (fields.length != 10 || !fields[0].equals("TOTAL_V1")) throw new IllegalArgumentException();
            long[] v = new long[9];
            for (int i = 0; i < v.length; i++) {
                v[i] = Long.parseLong(fields[i + 1]);
                if (v[i] < 0 || (previous != null && v[i] < previous.values[i])) throw new IllegalArgumentException();
            }
            if (v[0] == 0 || v[0] > sentCalls || (previous != null && v[0] <= previous.values[0])) {
                throw new IllegalArgumentException();
            }
            long accounted = 0;
            for (int i = 1; i <= 5; i++) accounted = Math.addExact(accounted, v[i]);
            if (accounted > sentFrames || accounted < v[0] || accounted > Math.multiplyExact(v[0], 960) ||
                    Math.addExact(v[7], v[8]) > v[6]) throw new IllegalArgumentException();
            return new Snapshot(v);
        } catch (RuntimeException invalid) { throw new IOException("invalid_cumulative_feed_accounting", invalid); }
    }

    static final class Snapshot {
        final long[] values;
        Snapshot(long[] values) { this.values = values; }
        long completed() { return values[0]; }
        String encode() {
            StringBuilder text = new StringBuilder("TOTAL_V1");
            for (long value : values) text.append(',').append(value);
            return text.toString();
        }
    }
}
