package io.warpnect.debug.directshell;

import java.util.function.LongSupplier;

/** Published by the route observer; track resets are performed only by the destination owner. */
final class DirectShellMicrophoneConsumerWindow {
    final boolean active;
    final long sinceNs;
    final long closeEpoch;

    DirectShellMicrophoneConsumerWindow() { this(false, 0, 0); }

    private DirectShellMicrophoneConsumerWindow(boolean active, long sinceNs, long closeEpoch) {
        this.active = active;
        this.sinceNs = sinceNs;
        this.closeEpoch = closeEpoch;
    }

    DirectShellMicrophoneConsumerWindow observe(boolean present, long nowNs) {
        if (present == active) return this;
        return new DirectShellMicrophoneConsumerWindow(present, present ? nowNs : 0,
                closeEpoch + (present ? 0 : 1));
    }

    int reject(long enqueuedNs, long nowNs, long resetEpoch) {
        if (!DirectShellMicrophoneFrame.isFresh(enqueuedNs, nowNs)) return DirectShellMicrophoneFrame.DROP_STALE;
        if (!active || resetEpoch != closeEpoch) return DirectShellMicrophoneFrame.DROP_NO_CONSUMER;
        if (!DirectShellMicrophoneFrame.belongsToConsumerWindow(enqueuedNs, sinceNs)) {
            return DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION;
        }
        return 0;
    }

    int writeIfFresh(long enqueuedNs, long resetEpoch, LongSupplier clock,
            DirectShellMicrophoneFeedResult.Writer writer, short[] pcm, int frames) {
        int rejected = reject(enqueuedNs, clock.getAsLong(), resetEpoch);
        return rejected == 0 ? writer.write(pcm, frames, enqueuedNs) : rejected;
    }
}
