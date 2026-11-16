package io.warpnect.debug.directshell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.Lock;

/** DEBUG-only public AAudio clock for the exact Session-owned policy output. */
@android.annotation.TargetApi(31)
final class DirectShellNativeMicrophoneClock implements AutoCloseable {
    private final long generation;
    private long handle;
    private long[] terminal;
    private volatile long[] lastSnapshot = new long[27];
    private final ReentrantReadWriteLock lifetime = new ReentrantReadWriteLock();
    private final Lock reading = lifetime.readLock();
    private final Lock owning = lifetime.writeLock();

    private DirectShellNativeMicrophoneClock(long generation, long handle) {
        this.generation = generation;
        this.handle = handle;
    }

    static DirectShellNativeMicrophoneClock open(Context context, AudioManager manager,
            String registration, long generation) throws Exception {
        if (Process.myUid() != Process.SHELL_UID || DirectShellRuntimeApi.sdkInt() < 31) {
            throw new IOException("native_clock_identity_or_api_unavailable");
        }
        int deviceId = ownedOutput(manager, registration).getId();
        ApplicationInfo app = context.getPackageManager().getApplicationInfo("io.warpnect", 0);
        boolean classpathOwner = Arrays.asList(System.getProperty("java.class.path", "").split(":"))
                .contains(app.sourceDir);
        if (!classpathOwner) throw new IOException("native_clock_apk_owner_mismatch");
        String abi = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0];
        load(app, abi, "c++_shared");
        load(app, abi, "warpnect_microphone_clock");
        long handle = openNative(deviceId, generation);
        if (handle == 0) throw new IOException("native_clock_route_or_format_unavailable");
        return new DirectShellNativeMicrophoneClock(generation, handle);
    }

    static boolean ownsOutput(int type, String address, String registration) {
        return type == AudioDeviceInfo.TYPE_REMOTE_SUBMIX && registration != null &&
                !registration.isEmpty() && registration.equals(address);
    }

    static AudioDeviceInfo ownedOutput(AudioManager manager, String registration) throws IOException {
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int[] ids = new int[devices.length];
        int[] types = new int[devices.length];
        String[] addresses = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            ids[i] = devices[i].getId(); types[i] = devices[i].getType(); addresses[i] = devices[i].getAddress();
        }
        int selected = selectOutputId(ids, types, addresses, registration);
        for (AudioDeviceInfo device : devices) if (device.getId() == selected) return device;
        throw new IOException("owned_submix_output_unavailable");
    }

    static int selectOutputId(int[] ids, int[] types, String[] addresses, String registration) throws IOException {
        if (ids.length != types.length || ids.length != addresses.length) throw new IOException("invalid_device_view");
        int selected = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] > 0 && ownsOutput(types[i], addresses[i], registration)) {
                if (selected != 0) throw new IOException("ambiguous_owned_submix_output");
                selected = ids[i];
            }
        }
        if (selected == 0) throw new IOException("owned_submix_output_unavailable");
        return selected;
    }

    private static void load(ApplicationInfo app, String abi, String name) {
        File extracted = new File(app.nativeLibraryDir, "lib" + name + ".so");
        System.load(extracted.isFile() ? extracted.getAbsolutePath() :
                app.sourceDir + "!/lib/" + abi + "/lib" + name + ".so");
    }

    void start() throws IOException {
        owning.lock();
        try {
            if (handle == 0 || startNative(handle) != 0) throw new IOException("native_clock_start_failed");
        } finally { owning.unlock(); }
    }

    void offer(DirectShellMicrophoneFrame frame) throws IOException {
        reading.lock();
        try {
            if (handle == 0 || !offerNative(handle, generation, frame.samples, frame.enqueuedNs)) {
                throw new IOException("native_clock_feed_invalid_or_closed");
            }
        } finally { Arrays.fill(frame.samples, (short) 0); reading.unlock(); }
    }

    void window(DirectShellMicrophoneConsumerWindow window) {
        reading.lock();
        try {
            if (handle != 0) windowNative(handle, window.active ? window.sinceNs : 0);
        } finally { reading.unlock(); }
    }

    DirectShellMicrophoneFeedTotals.Snapshot totals() {
        reading.lock();
        try { return new DirectShellMicrophoneFeedTotals.Snapshot(Arrays.copyOf(snapshot(), 9)); }
        finally { reading.unlock(); }
    }

    // Only result/diagnostic readers serialize; PCM ingress never takes this monitor.
    private synchronized long[] snapshot() {
        if (handle == 0) return terminal;
        long[] fresh = snapshotNative(handle);
        if (fresh != null) lastSnapshot = fresh;
        return fresh == null ? lastSnapshot : fresh;
    }

    String diagnostics() {
        final long[] v;
        reading.lock();
        try { v = snapshot(); } finally { reading.unlock(); }
        return "native_clock=true;retained_frames=" + v[9] + ";pcm_hwm_frames=" + v[10] +
                ";max_accepted_age_ns=" + v[11] + ";missing_pcm_silence_frames=" + v[12] +
                ";idle_silence_frames=" + v[13] + ";max_callback_gap_ns=" + v[14] +
                ";callbacks=" + v[15] + ";callback_frames=" + v[16] +
                ";track_underruns=" + v[17] + ";native_error=" + v[18] +
                ";scheduler=" + v[19] + ";nice=" + v[20] + ";platform_written=" + v[21] +
                ";platform_read=" + v[22] + ";max_callback_body_ns=" + v[23] +
                ";max_callback_return_age_ns=" + v[24] + ";late_callback_frames=" + v[25] +
                ";post_copy_stale_dropped_frames=" + v[26] + ";totals=" +
                new DirectShellMicrophoneFeedTotals.Snapshot(Arrays.copyOf(v, 9)).encode();
    }

    int underruns() {
        reading.lock();
        try { return (int) snapshot()[17]; } finally { reading.unlock(); }
    }

    @Override public void close() {
        owning.lock();
        try {
            if (handle == 0) return;
            windowNative(handle, 0);
            terminal = snapshot();
            closeNative(handle);
            handle = 0;
        } finally { owning.unlock(); }
    }

    private static native long openNative(int deviceId, long generation);
    private static native int startNative(long handle);
    private static native boolean offerNative(long handle, long generation, short[] pcm, long enqueuedNs);
    private static native void windowNative(long handle, long sinceNs);
    private static native long[] snapshotNative(long handle);
    private static native void closeNative(long handle);
}
