package io.warpnect.debug.directshell;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** One destination clock. No socket, Binder lookup, or result serialization on its hot path. */
final class DirectShellMicrophoneDestinationPump implements AutoCloseable {
    static final int TICK_FRAMES = 240;
    static final int MAX_WRITE_FRAMES = 960;
    static final long PERIOD_NS = 5_000_000L;
    interface Sink extends DirectShellMicrophoneFeedResult.Writer {
        int reject(long enqueuedNs, long nowNs);
        void silence();
    }
    private final Sink sink;
    private final LongSupplier clock;
    private final BiConsumer<Long, DirectShellMicrophoneFeedResult> completed;
    private final ArrayDeque<Job> pending = new ArrayDeque<>();
    private final short[] scratch = new short[TICK_FRAMES];
    private Job current;
    private boolean closed;
    private int retainedFrames;
    private int highWater;
    private long ticks;
    private long silenceTicks;
    private long maxTickGapNs;
    private long lastTickNs;
    private long maxAgeNs;
    private long acceptedLate;

    DirectShellMicrophoneDestinationPump(Sink sink, LongSupplier clock,
            BiConsumer<Long, DirectShellMicrophoneFeedResult> completed) {
        this.sink = sink;
        this.clock = clock;
        this.completed = completed;
    }

    synchronized void offer(long id, DirectShellMicrophoneFrame frame) throws IOException {
        if (closed) { Arrays.fill(frame.samples, (short) 0); throw new IOException("destination_closed"); }
        // A rejected block retains only result metadata, never stale PCM or an extra audio queue.
        Job job = new Job(id, frame);
        if (retainedFrames + frame.samples.length > DirectShellMicrophoneFeedWindow.MAX_FRAMES ||
                pending.size() + (current == null ? 0 : 1) >= DirectShellMicrophoneFeedWindow.MAX_REQUESTS) {
            job.drop = DirectShellMicrophoneFrame.DROP_CAPACITY;
            Arrays.fill(frame.samples, (short) 0);
            completed.accept(id, new DirectShellMicrophoneFeedResult(0, job.frames, job.drop, 0, 0, 0));
            return;
        } else {
            retainedFrames += frame.samples.length;
            highWater = Math.max(highWater, retainedFrames);
        }
        pending.addLast(job);
    }

    // Called only by the destination owner, also directly with a virtual clock in focused tests.
    void tick() throws IOException {
        long now = clock.getAsLong();
        ticks++;
        if (lastTickNs != 0) maxTickGapNs = Math.max(maxTickGapNs, now - lastTickNs);
        lastTickNs = now;
        int budget = MAX_WRITE_FRAMES;
        boolean wrote = false;
        for (int drained = 0; drained < DirectShellMicrophoneFeedWindow.MAX_REQUESTS; drained++) {
            synchronized (this) {
                if (closed) return;
                if (current == null) current = pending.pollFirst();
            }
            if (current == null) {
                if (!wrote) { sink.silence(); silenceTicks++; }
                return;
            }
            Job job = current;
            now = clock.getAsLong();
            int reason = job.drop;
            if (reason == 0 && !DirectShellMicrophoneFrame.isFresh(job.enqueuedNs, now)) {
                reason = DirectShellMicrophoneFrame.DROP_STALE;
            }
            if (reason == 0) reason = sink.reject(job.enqueuedNs, now);
            if (reason != 0) {
                if (!DirectShellMicrophoneFeedResult.isDrop(reason)) throw new IOException("destination_inactive_" + reason);
                finish(job, reason);
                continue;
            }
            int requested = Math.min(Math.min(TICK_FRAMES, budget), job.frames - job.accepted);
            System.arraycopy(job.samples, job.accepted, scratch, 0, requested);
            int count;
            try { count = sink.write(scratch, requested, job.enqueuedNs); }
            finally { Arrays.fill(scratch, (short) 0); }
            job.attempts++;
            if (count < 0) {
                if (!DirectShellMicrophoneFeedResult.isDrop(count)) throw new IOException("fatal_destination_write_" + count);
                finish(job, count);
                sink.silence();
                silenceTicks++;
                return;
            }
            if (count > requested) throw new IOException("invalid_destination_accounting");
            if (count == 0) job.zeros++;
            else {
                if (count < requested) job.partials++;
                job.accepted += count;
                budget -= count;
                wrote = true;
                long age = clock.getAsLong() - job.enqueuedNs;
                maxAgeNs = Math.max(maxAgeNs, age);
                if (age > DirectShellMicrophoneFrame.MAX_AGE_NS) acceptedLate += count;
            }
            if (job.accepted == job.frames) finish(job, 0);
            // Fill only existing Android capacity from still-fresh PCM, never an elapsed-time debt.
            if (count < requested || budget == 0) return;
        }
    }

    private void finish(Job job, int reason) {
        synchronized (this) {
            if (job.samples != null) {
                retainedFrames -= job.frames;
                Arrays.fill(job.samples, (short) 0);
            }
            current = null;
        }
        completed.accept(job.id, new DirectShellMicrophoneFeedResult(job.accepted,
                job.frames - job.accepted, reason, job.attempts, job.zeros, job.partials));
    }

    synchronized String diagnostics() {
        return "ticks=" + ticks + " silence_ticks=" + silenceTicks + " pcm_capacity_frames=" +
                DirectShellMicrophoneFeedWindow.MAX_FRAMES + " pcm_hwm_frames=" + highWater +
                " retained_frames=" + retainedFrames + " max_tick_gap_ns=" + maxTickGapNs +
                " max_accepted_age_ns=" + maxAgeNs + " accepted_late_frames=" + acceptedLate;
    }

    @Override public synchronized void close() {
        closed = true;
        if (current != null && current.samples != null) Arrays.fill(current.samples, (short) 0);
        for (Job job : pending) if (job.samples != null) Arrays.fill(job.samples, (short) 0);
        pending.clear();
        current = null;
        retainedFrames = 0;
        Arrays.fill(scratch, (short) 0);
    }

    private static final class Job {
        final long id;
        final int frames;
        final long enqueuedNs;
        short[] samples;
        int accepted, drop, attempts, zeros, partials;
        Job(long id, DirectShellMicrophoneFrame frame) {
            this.id = id;
            frames = frame.samples.length;
            samples = frame.samples;
            enqueuedNs = frame.enqueuedNs;
        }
    }
}
