package io.warpnect.debug.directshell;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Process;
import java.io.IOException;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Session-owned public AAudio writer. No policy registration privilege or synthetic PCM. */
@android.annotation.TargetApi(31)
final class AppOwnedMicrophoneClock implements AutoCloseable {
    private final long generation;
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private long handle;
    private volatile long[] last = new long[35];
    private final File traceFile;
    private Thread pump;
    private Thread metrics;
    private volatile boolean sampling;

    private AppOwnedMicrophoneClock(Context context, long generation, long handle) {
        this.generation = generation;
        this.handle = handle;
        traceFile = new File(context.getCacheDir(), "app-microphone-clock-" + generation + ".csv");
    }

    static AppOwnedMicrophoneClock open(Context context, String registration, long generation) throws IOException {
        if (Process.myUid() < 10000 || Build.VERSION.SDK_INT < 31) throw new IOException("app_clock_identity_or_api_unavailable");
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int device = DirectShellNativeMicrophoneClock.ownedOutput(manager, registration).getId();
        System.loadLibrary("warpnect_microphone_clock");
        long handle = openNative(device, generation);
        if (handle == 0) throw new IOException("app_clock_route_or_format_unavailable");
        return new AppOwnedMicrophoneClock(context, generation, handle);
    }

    void start() throws IOException {
        lifetime.readLock().lock();
        try {
            if (handle == 0 || startNative(handle) != 0) throw new IOException("app_clock_start_failed");
            final long owned = handle;
            pump = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                pumpNative(owned);
            }, "AppRemoteMicDestinationPump");
            pump.start();
            sampling = true;
            metrics = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                while (sampling) {
                    int delayMs = sampleSdkNative(owned);
                    try { Thread.sleep(delayMs); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); break; }
                }
            }, "AppRemoteMicSdkMetrics");
            metrics.start();
        } finally { lifetime.readLock().unlock(); }
    }

    boolean offer(short[] pcm, long enqueuedNs, long firstFramePosition) {
        lifetime.readLock().lock();
        try { return handle != 0 && offerNative(handle, generation, pcm, enqueuedNs, firstFramePosition); }
        finally { Arrays.fill(pcm, (short) 0); lifetime.readLock().unlock(); }
    }

    void window(long sinceNs) {
        lifetime.readLock().lock();
        try { if (handle != 0) windowNative(handle, sinceNs); }
        finally { lifetime.readLock().unlock(); }
    }

    // Only the control/diagnostic owner calls this. Never on decoded PCM ingress.
    long[] snapshot() {
        lifetime.readLock().lock();
        try {
            if (handle != 0) {
                long[] fresh = snapshotNative(handle);
                if (fresh != null) last = fresh;
            }
            return last;
        } finally { lifetime.readLock().unlock(); }
    }

    @Override public void close() {
        lifetime.writeLock().lock();
        try {
            if (handle == 0) return;
            stopPumpNative(handle);
            sampling = false;
            if (pump != null) {
                try { pump.join(3500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (pump.isAlive()) throw new IllegalStateException("app_clock_pump_close_timeout");
            }
            if (metrics != null) {
                try { metrics.join(3500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (metrics.isAlive()) throw new IllegalStateException("app_clock_metrics_close_timeout");
            }
            long[] trace = closeNative(handle);
            handle = 0;
            // Bounded numeric diagnostics after callback/ingress stop; no PCM is persisted.
            if (trace != null) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(traceFile))) {
                    for (int i = 0; i + 9 < trace.length; i += 10) {
                        for (int j = 0; j < 10; j++) {
                            if (j != 0) writer.write(',');
                            writer.write(Long.toString(trace[i + j]));
                        }
                        writer.newLine();
                    }
                } catch (IOException failure) {
                    android.util.Log.i("WarpnectRemoteMic", "APP_MIC_TRACE_FAILED " + failure.getClass().getSimpleName());
                }
            }
        } finally { lifetime.writeLock().unlock(); }
    }

    private static native long openNative(int deviceId, long generation);
    private static native int startNative(long handle);
    private static native void pumpNative(long handle);
    private static native void stopPumpNative(long handle);
    private static native int sampleSdkNative(long handle);
    private static native boolean offerNative(long handle, long generation, short[] pcm, long enqueuedNs, long firstFramePosition);
    private static native void windowNative(long handle, long sinceNs);
    private static native long[] snapshotNative(long handle);
    private static native long[] closeNative(long handle);
}
