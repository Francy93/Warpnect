package io.warpnect.debug.directshell;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Process;
import android.util.Log;
import java.util.Arrays;

/** Session-owned input clock for the exact recorder mix. PCM is erased, never forwarded. */
final class DirectShellRemoteMicrophoneDrain implements AutoCloseable {
    private final Context context;
    private final AudioManager manager;
    private final String registration;
    private final int source;
    private final long generation;
    private volatile boolean running = true;
    private volatile int session;
    private volatile String failure;
    private final Thread worker;

    DirectShellRemoteMicrophoneDrain(Context context, AudioManager manager, String registration,
            int source, long generation) {
        this.context = context;
        this.manager = manager;
        this.registration = registration;
        this.source = source;
        this.generation = generation;
        worker = new Thread(this::drain, "RemoteMicOwnedInputDrain");
    }

    void start() { worker.start(); }
    int session() { return session; }
    String failure() { return failure; }

    private boolean inputPresent() {
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (MicrophonePolicyApi.deviceType(device) == 25 &&
                    registration.equals(MicrophonePolicyApi.address(device))) return true;
        }
        return false;
    }

    private void drain() {
        AudioRecord record = null;
        short[] scratch = new short[960];
        long frames = 0;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!inputPresent() && running && System.nanoTime() < deadline) Thread.sleep(10);
            if (!running) return;
            if (!inputPresent()) throw new IllegalStateException("owned_input_unavailable");
            AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            int bytes = Math.max(3840, AudioRecord.getMinBufferSize(48000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(format).setBufferSizeInBytes(bytes);
            try { AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, context); }
            catch (NoSuchMethodException legacy) { /* Existing shell attribution; no provider fallback. */ }
            record = builder.build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("owned_input_prepare_failed");
            }
            session = record.getAudioSessionId();
            record.startRecording();
            long routeDeadline = System.nanoTime() + 500_000_000L;
            long nextLog = 0;
            while (running) {
                AudioDeviceInfo routed = record.getRoutedDevice();
                if (routed != null && (MicrophonePolicyApi.deviceType(routed) != 25 ||
                        !registration.equals(MicrophonePolicyApi.address(routed)))) {
                    throw new IllegalStateException("owned_input_route_lost");
                }
                if (routed == null && System.nanoTime() > routeDeadline) {
                    throw new IllegalStateException("owned_input_route_unconfirmed");
                }
                if (routed == null) { Thread.sleep(3); continue; }
                int read = record.read(scratch, 0, scratch.length, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) throw new IllegalStateException("owned_input_read_" + read);
                frames += read;
                Arrays.fill(scratch, (short) 0);
                long now = System.nanoTime();
                if (now >= nextLog) {
                    Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_DRAIN generation=" + generation +
                            ";session=" + session + ";frames=" + frames + ";buffer_bytes=" + bytes +
                            ";record_buffer_frames=" + record.getBufferSizeInFrames() +
                            ";preferred=false;route=" + routed.getId());
                    nextLog = now + 1_000_000_000L;
                }
                Thread.sleep(3);
            }
        } catch (Exception failure) {
            if (running) this.failure = failure.getClass().getSimpleName() + ":" + failure.getMessage();
            Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_DRAIN_FAILED generation=" + generation +
                    ";reason=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        } finally {
            if (record != null) {
                try { record.stop(); } catch (IllegalStateException ignored) { }
                record.release();
            }
            Arrays.fill(scratch, (short) 0);
            Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_DRAIN_RELEASED generation=" + generation);
        }
    }

    @Override public void close() {
        running = false;
        try { worker.join(3500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) throw new IllegalStateException("owned_input_drain_close_timeout");
    }
}
