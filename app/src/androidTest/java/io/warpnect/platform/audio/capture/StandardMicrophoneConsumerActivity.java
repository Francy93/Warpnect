package io.warpnect.platform.audio.capture;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/** Standalone test-APK UID. No Kotlin/target dependency, PCM bridge, or preferred input. */
public final class StandardMicrophoneConsumerActivity extends Activity {
    private static final String TAG = "WarpnectOrdinaryMic";
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ORDINARY_MIC result=PERMISSION_DENIED");
            finish();
            return;
        }
        int source = getIntent().getIntExtra("source", MediaRecorder.AudioSource.MIC);
        if (source != 1 && source != 5 && source != 7) {
            finish();
            return;
        }
        int duration = Math.max(1, Math.min(90, getIntent().getIntExtra("durationSeconds", 12)));
        new Thread(() -> observe(source, duration), "OrdinaryMicConsumer").start();
    }

    @Override public void onDestroy() {
        running.set(false);
        super.onDestroy();
    }

    private void observe(int source, int durationSeconds) {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            Log.i(TAG, "ORDINARY_INPUT id=" + input.getId() + ";type=" + input.getType() +
                    ";product=" + input.getProductName() + ";address=" +
                    (android.os.Build.VERSION.SDK_INT >= 28 ? input.getAddress() : "API_UNAVAILABLE") +
                    ";rates=" + java.util.Arrays.toString(input.getSampleRates()) +
                    ";channels=" + java.util.Arrays.toString(input.getChannelCounts()) +
                    ";encodings=" + java.util.Arrays.toString(input.getEncodings()));
        }
        AudioRecord record = null;
        try {
            AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            record = new AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(AudioRecord.getMinBufferSize(48000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3840)).build();
            record.startRecording();
            Log.i(TAG, "ORDINARY_MIC started=true uid=" + android.os.Process.myUid() +
                    ";source=" + source + ";preferred=false");
            short[] pcm = new short[960];
            long deadline = SystemClock.elapsedRealtime() + durationSeconds * 1000L;
            long window = SystemClock.elapsedRealtime();
            long samples = 0;
            double energy = 0, real = 0, imaginary = 0;
            int peak = 0;
            while (running.get() && SystemClock.elapsedRealtime() < deadline) {
                int read = record.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) throw new IllegalStateException("read_error=" + read);
                for (int i = 0; i < read; i++) {
                    double value = pcm[i];
                    double phase = 2 * Math.PI * 997 * samples / 48000;
                    energy += value * value;
                    peak = Math.max(peak, Math.abs(pcm[i]));
                    real += value * Math.cos(phase);
                    imaginary += value * Math.sin(phase);
                    samples++;
                }
                if (SystemClock.elapsedRealtime() - window >= 1000) {
                    double n = Math.max(1, samples);
                    AudioDeviceInfo routed = record.getRoutedDevice();
                    Log.i(TAG, "ORDINARY_MIC samples=" + samples + ";rms=" + Math.sqrt(energy / n) +
                            ";peak=" + peak + ";tone997=" + 2 * Math.sqrt(real * real + imaginary * imaginary) / n +
                            ";route_type=" + (routed == null ? -1 : routed.getType()) +
                            ";route_id=" + (routed == null ? -1 : routed.getId()));
                    samples = 0;
                    energy = real = imaginary = 0;
                    peak = 0;
                    window = SystemClock.elapsedRealtime();
                }
                Thread.sleep(3);
            }
        } catch (Exception failure) {
            Log.i(TAG, "ORDINARY_MIC failure=" + failure.getClass().getSimpleName());
        } finally {
            if (record != null) {
                try { record.stop(); } catch (IllegalStateException ignored) { }
                record.release();
            }
            Log.i(TAG, "ORDINARY_MIC cleanup=RELEASED");
            runOnUiThread(this::finish);
        }
    }
}
