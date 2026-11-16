package io.warpnect.debug.directshell;

import java.io.IOException;
import java.util.ArrayDeque;

/** Ordered credits; PCM admission and result accounting have separate bounded lifetimes. */
final class DirectShellMicrophoneFeedWindow {
    static final int MAX_REQUESTS = 8;
    static final int MAX_FRAMES = 1920;
    static final int MAX_RESULTS = 64;
    private final int requestLimit;
    private final int frameLimit;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private int frames;
    private long lastId;
    private boolean closed;
    private int highWater;

    DirectShellMicrophoneFeedWindow() { this(MAX_REQUESTS, MAX_FRAMES); }

    private DirectShellMicrophoneFeedWindow(int requests, int frames) {
        requestLimit = requests;
        frameLimit = frames;
    }

    // IDs/counts/timestamps only, never retained PCM; covers the measured 241 ms ACK tail.
    static DirectShellMicrophoneFeedWindow resultLedger() {
        return new DirectShellMicrophoneFeedWindow(MAX_RESULTS, MAX_RESULTS * 240);
    }

    synchronized boolean admit(long id, int count, long enqueuedNs) throws InterruptedException, IOException {
        if (count <= 0 || count > 960 || id <= lastId) throw new IOException("invalid_feed_admission");
        while (!closed && (pending.size() == requestLimit || frames + count > frameLimit)) {
            long remaining = DirectShellMicrophoneFrame.MAX_AGE_NS - (System.nanoTime() - enqueuedNs);
            if (remaining <= 0) return false;
            try {
                wait(Math.max(1, (remaining + 999999) / 1000000));
            } catch (InterruptedException interrupted) {
                if (closed) return false;
                throw interrupted;
            }
        }
        if (closed || !DirectShellMicrophoneFrame.isFresh(enqueuedNs, System.nanoTime())) return false;
        pending.addLast(new Pending(id, count, System.nanoTime()));
        frames += count;
        lastId = id;
        highWater = Math.max(highWater, pending.size());
        notifyAll();
        return true;
    }

    synchronized Pending next() throws InterruptedException {
        while (!closed && pending.isEmpty()) wait();
        return closed ? null : pending.peekFirst();
    }

    synchronized boolean complete(long id) throws IOException {
        if (closed) return false;
        Pending first = pending.peekFirst();
        if (first == null || first.id != id) throw new IOException("feed_result_out_of_order");
        frames -= pending.removeFirst().frames;
        notifyAll();
        return true;
    }

    synchronized int close() {
        closed = true;
        int cancelled = frames;
        frames = 0;
        pending.clear();
        notifyAll();
        return cancelled;
    }

    synchronized int highWater() { return highWater; }
    synchronized int size() { return pending.size(); }

    static final class Pending {
        final long id;
        final int frames;
        final long sentNs;
        Pending(long id, int frames, long sentNs) {
            this.id = id;
            this.frames = frames;
            this.sentNs = sentNs;
        }
    }
}
