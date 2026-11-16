package io.warpnect.debug.directshell;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Debug;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/** Purpose-specific recorder mix. No UID, device connection, or arbitrary policy entry point. */
@android.annotation.TargetApi(31)
final class DirectShellRemoteMicrophonePolicy implements AutoCloseable {
    enum Result {
        SUPPORTED, API_UNAVAILABLE, PERMISSION_DENIED, CAPTURE_PRESET_RULE_UNAVAILABLE,
        POLICY_REGISTRATION_FAILED, REMOTE_SUBMIX_UNAVAILABLE, START_FAILED, BAD_GENERATION
    }

    private final long generation;
    private final int[] presets;
    private AudioManager manager;
    private Object policy;
    private volatile AudioTrack track;
    private volatile DirectShellNativeMicrophoneClock nativeClock;
    private final java.util.function.LongSupplier writeClock = System::nanoTime;
    private final DirectShellMicrophoneFeedResult.Writer trackWriter =
            (pcm, frames, enqueuedNs) -> track.write(pcm, 0, frames, AudioTrack.WRITE_NON_BLOCKING);
    private boolean registered;
    private boolean appWriter;
    private volatile DirectShellRemoteMicrophoneDrain inputDrain;
    private Result result = Result.API_UNAVAILABLE;
    private String registration = "";
    private volatile long writtenFrames;
    private volatile boolean active;
    private volatile DirectShellMicrophoneConsumerWindow consumerWindow = new DirectShellMicrophoneConsumerWindow();
    private long resetCloseEpoch;
    private HandlerThread recordingThread;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private volatile long noConsumerFrames;
    private volatile long staleFrames;
    private volatile long transitionFrames;
    private volatile long maxPcmAgeNs;
    private final short[] idleSilence = new short[960];
    private volatile long idleSilenceFrames;
    private volatile long missingPcmSilenceFrames;
    private volatile long zeroWrites;
    private volatile long partialWrites;
    private long nextSampleNs;
    private volatile long maxWriteNs;
    private volatile long maxConsumerTransitionNs;
    private volatile long acceptedSinceFlush;
    private volatile long previousFeedNs;
    private int observedTrackUnderruns;
    private int underrunObservations;
    private Handler recordingHandler;
    private HandlerThread diagnosticThread;
    private Handler diagnosticHandler;
    // Numeric transition probe, not an audio queue. Flushed only during terminal teardown.
    private final long[] feedEvents = new long[8192 * 13];
    private int feedEventCount;
    private int initialProbeRemaining = 2048;
    private volatile int transitionProbeRemaining;
    private int slowProbeRemaining = 128;
    private final FeedTiming probeTiming = new FeedTiming();
    private final long[] silenceEvents = new long[4096 * 6];
    private int silenceEventCount;

    DirectShellRemoteMicrophonePolicy(long generation, int[] presets) {
        if (generation <= 0 || presets == null || presets.length == 0 || presets.length > 3) {
            throw new IllegalArgumentException("invalid_microphone_contract");
        }
        for (int preset : presets) {
            if (preset != 1 && preset != 5 && preset != 7) {
                throw new IllegalArgumentException("unsupported_capture_preset");
            }
        }
        if (java.util.Arrays.stream(presets).distinct().count() != presets.length) {
            throw new IllegalArgumentException("duplicate_capture_preset");
        }
        this.generation = generation;
        this.presets = presets.clone();
    }

    synchronized Result prepare() {
        return prepare(false);
    }

    synchronized Result prepareExternalWriterProbe() {
        return prepare(true);
    }

    synchronized Result prepareAppWriter() {
        appWriter = true;
        return prepare(true);
    }

    synchronized String appWriterState() {
        if (!registered || !appWriter || result != Result.SUPPORTED) return "UNAVAILABLE";
        if (inputDrain != null && inputDrain.failure() != null) return "UNAVAILABLE";
        return "APP_V1;" + generation + ";" + registration + ";" + consumerWindow.sinceNs +
                ";" + consumerWindow.closeEpoch;
    }

    private Result prepare(boolean externalWriterProbe) {
        if (registered) return result;
        if (Process.myUid() != Process.SHELL_UID) return result = Result.PERMISSION_DENIED;
        DirectShellShellAudioContext.Result identity = DirectShellShellAudioContext.create();
        if (!identity.isAvailable()) return result = Result.API_UNAVAILABLE;
        Context context = identity.context;
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") !=
                PackageManager.PERMISSION_GRANTED) return result = Result.PERMISSION_DENIED;
        try {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object rule = MicrophonePolicyApi.buildCaptureRules(presets);
            AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder,
                    mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null));
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);
            Object builder = policyBuilderClass.getConstructor(Context.class).newInstance(context);
            policyBuilderClass.getMethod("addMix", mixClass).invoke(builder, mix);
            policy = policyBuilderClass.getMethod("build").invoke(builder);
            manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int status = (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyClass)
                    .invoke(manager, policy);
            if (status != 0) return result = Result.POLICY_REGISTRATION_FAILED;
            registered = true;
            Log.i("WarpnectRemoteMicPolicy", "PLATFORM_EVENT policy_registered ns=" + System.nanoTime());
            registration = (String) mixClass.getMethod("getRegistration").invoke(mix);
            AudioAttributes.Builder attributes = new AudioAttributes.Builder();
            attributes.setUsage(AudioAttributes.class.getField("USAGE_VIRTUAL_SOURCE").getInt(null));
            AudioAttributes.Builder.class.getMethod("addTag", String.class)
                    .invoke(attributes, "addr=" + registration);
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder().setAudioAttributes(attributes.build())
                    .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(
                            AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT));
            try {
                AudioTrack.Builder.class.getMethod("setContext", Context.class).invoke(trackBuilder, context);
            } catch (NoSuchMethodException legacy) {
                // Existing shell app_process attribution: native framework resolves the real UID.
            }
            track = trackBuilder.build();
            Log.i("WarpnectRemoteMicPolicy", "PLATFORM_EVENT track_ready ns=" + System.nanoTime());
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                close();
                return result = Result.REMOTE_SUBMIX_UNAVAILABLE;
            }
            if (externalWriterProbe) {
                // Bounded DEBUG prerequisite: policy ownership only, never two PCM writers.
                track.release();
                track = null;
                if (!appWriter) return result = Result.SUPPORTED;
            }
            if (!externalWriterProbe && DirectShellRuntimeApi.sdkInt() >= 31) {
                // Discover the policy-created output, then replace (never duplicate) its writer.
                track.release();
                track = null;
                nativeClock = DirectShellNativeMicrophoneClock.open(context, manager, registration, generation);
            }
            recordingThread = new HandlerThread("RemoteMicRecordingState");
            recordingThread.start();
            recordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    updateConsumers(configs);
                }
            };
            recordingHandler = new Handler(recordingThread.getLooper());
            manager.registerAudioRecordingCallback(recordingCallback, recordingHandler);
            if (appWriter) {
                inputDrain = new DirectShellRemoteMicrophoneDrain(context, manager, registration, presets[0], generation);
                inputDrain.start();
            }
            if (!appWriter) {
                diagnosticThread = new HandlerThread("RemoteMicDiagnostics");
                diagnosticThread.start();
                diagnosticHandler = new Handler(diagnosticThread.getLooper());
                diagnosticHandler.post(this::observeTrack);
            }
            updateConsumers(manager.getActiveRecordingConfigurations());
            return result = Result.SUPPORTED;
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            Result unavailable = cause instanceof SecurityException ? Result.PERMISSION_DENIED : Result.API_UNAVAILABLE;
            System.out.println("remote_mic_prepare_failure=" + cause.getClass().getSimpleName());
            close();
            return result = unavailable;
        }
    }

    synchronized Result result() { return result; }

    DirectShellNativeMicrophoneClock nativeClock() { return nativeClock; }

    synchronized Result activate(long ownerGeneration) {
        if (ownerGeneration != generation) return Result.BAD_GENERATION;
        if (result != Result.SUPPORTED || (track == null && nativeClock == null)) return result;
        try {
            active = true;
            // A recorder mix must have an active virtual output before normal input selection.
            if (nativeClock != null) nativeClock.start();
            else track.play();
            return Result.SUPPORTED;
        } catch (Exception failure) {
            close();
            return result = Result.START_FAILED;
        }
    }

    int feed(long ownerGeneration, short[] pcm, int frames) {
        return feed(ownerGeneration, pcm, frames, System.nanoTime());
    }

    int feed(long ownerGeneration, short[] pcm, int frames, long enqueuedNs) {
        long cpu = Debug.threadCpuTimeNanos();
        int outcome = feed(ownerGeneration, pcm, frames, enqueuedNs, probeTiming);
        long cpuEnd = Debug.threadCpuTimeNanos();
        {
            boolean slow = probeTiming.finishedNs - probeTiming.beginNs >= 10_000_000L;
            if (feedEventCount < 8192 && (initialProbeRemaining > 0 || transitionProbeRemaining > 0 ||
                    (slow && slowProbeRemaining > 0))) {
                if (initialProbeRemaining > 0) initialProbeRemaining--;
                if (transitionProbeRemaining > 0) transitionProbeRemaining--;
                if (slow && slowProbeRemaining > 0) slowProbeRemaining--;
                int i = feedEventCount++ * 13;
                feedEvents[i] = probeTiming.beginNs;
                feedEvents[i + 1] = probeTiming.lockAcquiredNs;
                feedEvents[i + 2] = probeTiming.writeStartedNs;
                feedEvents[i + 3] = probeTiming.writeFinishedNs;
                feedEvents[i + 4] = probeTiming.finishedNs;
                feedEvents[i + 5] = cpuEnd - cpu;
                feedEvents[i + 6] = frames;
                feedEvents[i + 7] = outcome;
                feedEvents[i + 8] = enqueuedNs;
                feedEvents[i + 9] = probeTiming.consumerActive ? 1 : 0;
                feedEvents[i + 10] = probeTiming.writeRequestedFrames;
                feedEvents[i + 11] = probeTiming.writeResult;
                feedEvents[i + 12] = probeTiming.writeCpuNs;
            }
        }
        return outcome;
    }

    int feed(long ownerGeneration, short[] pcm, int frames, long enqueuedNs, FeedTiming timing) {
        if (timing != null) {
            timing.clear();
            timing.beginNs = System.nanoTime();
        }
        {
            if (timing != null) {
                timing.lockAcquiredNs = System.nanoTime();
                DirectShellMicrophoneConsumerWindow window = consumerWindow;
                timing.consumerActive = window.active;
                timing.consumerSinceNs = window.sinceNs;
            }
            try {
                return feedOwned(ownerGeneration, pcm, frames, enqueuedNs, timing);
            } finally {
                if (timing != null) timing.finishedNs = System.nanoTime();
            }
        }
    }

    private int feedOwned(long ownerGeneration, short[] pcm, int frames, long enqueuedNs, FeedTiming timing) {
        if (ownerGeneration != generation || !active || track == null || frames <= 0 || frames > 960 ||
                pcm == null || frames > pcm.length) return -1;
        long age = System.nanoTime() - enqueuedNs;
        long nowNs = System.nanoTime();
        previousFeedNs = nowNs;
        if (!DirectShellMicrophoneFrame.isFresh(enqueuedNs, System.nanoTime())) {
            staleFrames += frames;
            return DirectShellMicrophoneFrame.DROP_STALE;
        }
        DirectShellMicrophoneConsumerWindow window = consumerWindow;
        if (!window.active || resetCloseEpoch != window.closeEpoch) {
            noConsumerFrames += frames;
            return DirectShellMicrophoneFrame.DROP_NO_CONSUMER;
        }
        // Delivery-time presence must not admit PCM queued before this reader window existed.
        if (!DirectShellMicrophoneFrame.belongsToConsumerWindow(enqueuedNs, window.sinceNs)) {
            transitionFrames += frames;
            return DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION;
        }
        int changedWindow = consumerWindow.reject(enqueuedNs, System.nanoTime(), resetCloseEpoch);
        if (changedWindow != 0) return changedWindow;
        maxPcmAgeNs = Math.max(maxPcmAgeNs, age);
        long writeStartedNs = System.nanoTime();
        if (timing != null) timing.writeStartedNs = writeStartedNs;
        long cpuStartedNs = timing == null ? 0 : Debug.threadCpuTimeNanos();
        // Bookkeeping can be descheduled too; the earlier admission is not a write-time permit.
        int written = consumerWindow.writeIfFresh(enqueuedNs, resetCloseEpoch, writeClock, trackWriter, pcm, frames);
        if (timing != null) {
            timing.writeFinishedNs = System.nanoTime();
            timing.writeRequestedFrames = frames;
            timing.writeResult = written;
            timing.writeCpuNs = Debug.threadCpuTimeNanos() - cpuStartedNs;
        }
        maxWriteNs = Math.max(maxWriteNs, System.nanoTime() - writeStartedNs);
        if (written > 0) {
            writtenFrames += written;
            acceptedSinceFlush += written;
        }
        if (written == 0) zeroWrites++;
        else if (written > 0 && written < frames) partialWrites++;
        return written;
    }

    int reject(long enqueuedNs, long nowNs) {
        if (!active || track == null) return -1;
        return consumerWindow.reject(enqueuedNs, nowNs, resetCloseEpoch);
    }

    // Only RemoteMicDestination writes or resets the track after activation.
    void supplySilence() {
        long begin = System.nanoTime();
        long cpu = Debug.threadCpuTimeNanos();
        long lockNs;
        long writeNs = 0;
        int written = 0;
        DirectShellMicrophoneConsumerWindow window = consumerWindow;
        final AudioTrack transitioning = resetCloseEpoch != window.closeEpoch ? track : null;
        if (transitioning != null) {
            long start = System.nanoTime();
            transitioning.pause();
            transitioning.flush();
            transitioning.play();
            {
                resetCloseEpoch = window.closeEpoch;
                acceptedSinceFlush = 0;
                maxConsumerTransitionNs = Math.max(maxConsumerTransitionNs, System.nanoTime() - start);
            }
        }
        {
            lockNs = System.nanoTime();
            if (!active || track == null) return;
            int requested = idleSilence.length;
            long start = System.nanoTime();
            int count = track.write(idleSilence, 0, requested, AudioTrack.WRITE_NON_BLOCKING);
            written = count;
            previousFeedNs = System.nanoTime();
            writeNs = previousFeedNs - start;
            maxWriteNs = Math.max(maxWriteNs, previousFeedNs - start);
            if (count < 0) throw new IllegalStateException("destination_silence_write_" + count);
            if (consumerWindow.active) missingPcmSilenceFrames += count;
            else idleSilenceFrames += count;
            acceptedSinceFlush += count;
        }
        if (silenceEventCount < 4096) {
            int offset = silenceEventCount++ * 6;
            silenceEvents[offset] = begin;
            silenceEvents[offset + 1] = lockNs;
            silenceEvents[offset + 2] = System.nanoTime();
            silenceEvents[offset + 3] = Debug.threadCpuTimeNanos() - cpu;
            silenceEvents[offset + 4] = writeNs;
            silenceEvents[offset + 5] = written;
        }
    }

    // Reused only by the serialized feed handler; this is not a PCM queue.
    static final class FeedTiming {
        long beginNs;
        long lockAcquiredNs;
        long getterStartedNs;
        long getterFinishedNs;
        long writeStartedNs;
        long writeFinishedNs;
        long finishedNs;
        long consumerSinceNs;
        boolean consumerActive;
        int writeRequestedFrames;
        int writeResult;
        long writeCpuNs;

        void clear() {
            beginNs = lockAcquiredNs = getterStartedNs = getterFinishedNs = 0;
            writeStartedNs = writeFinishedNs = finishedNs = consumerSinceNs = 0;
            consumerActive = false;
            writeRequestedFrames = writeResult = 0;
            writeCpuNs = 0;
        }
    }

    synchronized String diagnostics() {
        return "remote_mic_result=" + result + ";generation=" + generation + ";uid_rules=NONE" +
                ";criteria_count=" + presets.length + ";registration=" + registration +
                ";frames_written=" + writtenFrames + ";no_consumer_frames=" + noConsumerFrames +
                ";stale_frames=" + staleFrames + ";idle_silence_frames=" + idleSilenceFrames +
                ";missing_pcm_silence_frames=" + missingPcmSilenceFrames +
                ";transition_frames=" + transitionFrames + ";consumer_since_ns=" + consumerWindow.sinceNs +
                ";max_pcm_age_ns=" + maxPcmAgeNs + ";zero_writes=" + zeroWrites +
                ";partial_writes=" + partialWrites + ";consumer_active=" + consumerWindow.active +
                ";max_write_ns=" + maxWriteNs + ";max_consumer_transition_ns=" + maxConsumerTransitionNs +
                ";track_underruns=" + observedTrackUnderruns;
    }

    private void observeTrack() {
        final AudioTrack observed;
        final DirectShellNativeMicrophoneClock observedClock;
        synchronized (this) {
            if (!registered || (track == null && nativeClock == null) || diagnosticHandler == null) return;
            observed = track;
            observedClock = nativeClock;
        }
        try {
            long begin = System.nanoTime();
            int count = observedClock == null ? observed.getUnderrunCount() : observedClock.underruns();
            long end = System.nanoTime();
            String changed = null;
            String progress = null;
            synchronized (this) {
                if (track != observed || nativeClock != observedClock || diagnosticHandler == null) return;
                if (count != observedTrackUnderruns && underrunObservations++ < 64) {
                    changed = "PLATFORM_COUNTER begin_ns=" + begin + ";end_ns=" + end +
                            ";count=" + count + ";previous=" + observedTrackUnderruns +
                            ";last_feed_ns=" + previousFeedNs + ";accepted=" + acceptedSinceFlush +
                            ";consumer=" + consumerWindow.active +
                            ";clock=" + (observedClock == null ? "JAVA" : "NATIVE_AAUDIO");
                }
                observedTrackUnderruns = count;
                if (end >= nextSampleNs) {
                    progress = "REMOTE_MIC_POLICY_PROGRESS " + diagnostics() +
                            (observedClock == null ? "" : ";" + observedClock.diagnostics());
                    nextSampleNs = end + 5_000_000_000L;
                }
                diagnosticHandler.postDelayed(this::observeTrack, 50);
            }
            if (changed != null) Log.i("WarpnectRemoteMicPolicy", changed);
            if (progress != null) Log.i("WarpnectRemoteMicPolicy", progress);
        } catch (IllegalStateException released) {
            // Observation is deliberately outside PCM ingress and may race terminal release.
        }
    }

    private void updateConsumers(List<AudioRecordingConfiguration> configs) {
        final String ownedRegistration;
        synchronized (this) {
            if (!registered || (!appWriter && track == null && nativeClock == null)) return;
            ownedRegistration = registration;
        }
        // getAudioDevice resolves framework patches through Binder; never hold the feed lock here.
        boolean present = false;
        for (AudioRecordingConfiguration config : configs) {
            DirectShellRemoteMicrophoneDrain drain = inputDrain;
            AudioDeviceInfo device = config.getAudioDevice();
            if (device != null && matchesExternalConsumer(MicrophonePolicyApi.deviceType(device), MicrophonePolicyApi.address(device),
                    MicrophonePolicyApi.silenced(config), ownedRegistration, config.getClientAudioSessionId(),
                    drain == null ? 0 : drain.session())) {
                present = true;
            }
        }
        synchronized (this) {
            if (!registered || (!appWriter && track == null && nativeClock == null) || !ownedRegistration.equals(registration) ||
                    consumerWindow.active == present) return;
            consumerWindow = consumerWindow.observe(present, System.nanoTime());
            if (nativeClock != null) nativeClock.window(consumerWindow);
            transitionProbeRemaining = 768;
        }
        Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_CONSUMER generation=" + generation +
                ";active=" + present + ";ns=" + System.nanoTime() +
                ";registration=" + ownedRegistration + ";uid_rules=NONE");
    }

    static boolean mayMaintainSilence(boolean consumerActive, boolean consumerTransition) {
        return !consumerActive && !consumerTransition;
    }

    static boolean matchesConsumer(int type, String address, boolean silenced, String registration) {
        return !silenced && type == 25 && registration != null && !registration.isEmpty() &&
                registration.equals(address);
    }

    static boolean matchesExternalConsumer(int type, String address, boolean silenced, String registration,
            int session, int drainSession) {
        // AudioSession identity excludes only our drain; there is no application UID criterion.
        return !(drainSession > 0 && session == drainSession) && matchesConsumer(type, address, silenced, registration);
    }

    @Override public synchronized void close() {
        RuntimeException drainFailure = null;
        active = false;
        consumerWindow = new DirectShellMicrophoneConsumerWindow();
        resetCloseEpoch = 0;
        if (recordingCallback != null) {
            manager.unregisterAudioRecordingCallback(recordingCallback);
            recordingCallback = null;
        }
        if (recordingThread != null) {
            if (recordingHandler != null) recordingHandler.removeCallbacksAndMessages(null);
            recordingHandler = null;
            recordingThread.quitSafely();
            recordingThread = null;
        }
        if (diagnosticThread != null) {
            if (diagnosticHandler != null) diagnosticHandler.removeCallbacksAndMessages(null);
            diagnosticHandler = null;
            diagnosticThread.quitSafely();
            diagnosticThread = null;
        }
        if (inputDrain != null) {
            try { inputDrain.close(); }
            catch (RuntimeException failure) { drainFailure = failure; }
            finally { inputDrain = null; }
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
        if (nativeClock != null) {
            nativeClock.close();
            nativeClock = null;
        }
        if (registered) {
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", policy.getClass()).invoke(manager, policy);
            } catch (ReflectiveOperationException failure) {
                System.out.println("remote_mic_cleanup=UNREGISTER_FAILED");
            }
        }
        registered = false;
        appWriter = false;
        policy = null;
        result = Result.API_UNAVAILABLE;
        if (drainFailure != null) throw drainFailure;
        for (int j = 0; j < feedEventCount; j++) {
            int i = j * 13;
            Log.i("WarpnectRemoteMicPolicy", "PLATFORM_FEED begin_ns=" + feedEvents[i] +
                    ";lock_ns=" + feedEvents[i + 1] + ";write_begin_ns=" + feedEvents[i + 2] +
                    ";write_end_ns=" + feedEvents[i + 3] + ";end_ns=" + feedEvents[i + 4] +
                    ";cpu_ns=" + feedEvents[i + 5] + ";requested=" + feedEvents[i + 6] +
                    ";result=" + feedEvents[i + 7] + ";enqueue_ns=" + feedEvents[i + 8] +
                    ";consumer=" + feedEvents[i + 9] + ";write_requested=" + feedEvents[i + 10] +
                    ";write_result=" + feedEvents[i + 11] + ";write_cpu_ns=" + feedEvents[i + 12] +
                    ";mode=NON_BLOCKING");
        }
        feedEventCount = 0;
        for (int j = 0; j < silenceEventCount; j++) {
            int i = j * 6;
            Log.i("WarpnectRemoteMicPolicy", "PLATFORM_SILENCE begin_ns=" + silenceEvents[i] +
                    ";lock_ns=" + silenceEvents[i + 1] + ";end_ns=" + silenceEvents[i + 2] +
                    ";cpu_ns=" + silenceEvents[i + 3] + ";write_ns=" + silenceEvents[i + 4] +
                    ";written=" + silenceEvents[i + 5]);
        }
        silenceEventCount = 0;
    }
}
