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
    private AudioTrack track;
    private boolean registered;
    private Result result = Result.API_UNAVAILABLE;
    private String registration = "";
    private long writtenFrames;
    private boolean active;
    private boolean consumerActive;
    private boolean consumerTransition;
    private long consumerSinceNs;
    private HandlerThread recordingThread;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private long noConsumerFrames;
    private long staleFrames;
    private long transitionFrames;
    private long maxPcmAgeNs;
    private final short[] idleSilence = new short[960];
    private long idleSilenceFrames;
    private long zeroWrites;
    private long partialWrites;
    private long nextSampleNs;
    private long maxWriteNs;
    private long maxConsumerTransitionNs;
    private long acceptedSinceFlush;
    private long previousFeedNs;
    private int observedTrackUnderruns;
    private int underrunObservations;

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
        if (registered) return result;
        if (Process.myUid() != Process.SHELL_UID) return result = Result.PERMISSION_DENIED;
        DirectShellShellAudioContext.Result identity = DirectShellShellAudioContext.create();
        if (!identity.isAvailable()) return result = Result.API_UNAVAILABLE;
        Context context = identity.context;
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") !=
                PackageManager.PERMISSION_GRANTED) return result = Result.PERMISSION_DENIED;
        try {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            int captureRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null);
            Object rules = ruleBuilderClass.getConstructor().newInstance();
            for (int preset : presets) {
                AudioAttributes.Builder attributes = new AudioAttributes.Builder();
                AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class).invoke(attributes, preset);
                ruleBuilderClass.getMethod("addRule", AudioAttributes.class, int.class)
                        .invoke(rules, attributes.build(), captureRule);
            }
            Object rule = ruleBuilderClass.getMethod("build").invoke(rules);
            // Audit the actual built criteria, not just the inputs passed to the builder.
            List<?> criteria = (List<?>) ruleClass.getMethod("getCriteria").invoke(rule);
            if (criteria.size() != presets.length) return result = Result.CAPTURE_PRESET_RULE_UNAVAILABLE;
            for (Object criterion : criteria) {
                int actual = (Integer) criterion.getClass().getMethod("getRule").invoke(criterion);
                if (actual != captureRule) return result = Result.CAPTURE_PRESET_RULE_UNAVAILABLE;
            }
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
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                close();
                return result = Result.REMOTE_SUBMIX_UNAVAILABLE;
            }
            recordingThread = new HandlerThread("RemoteMicRecordingState");
            recordingThread.start();
            recordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    updateConsumers(configs);
                }
            };
            manager.registerAudioRecordingCallback(recordingCallback, new Handler(recordingThread.getLooper()));
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

    synchronized Result activate(long ownerGeneration) {
        if (ownerGeneration != generation) return Result.BAD_GENERATION;
        if (result != Result.SUPPORTED || track == null) return result;
        try {
            active = true;
            // A recorder mix must have an active virtual output before normal input selection.
            track.play();
            return Result.SUPPORTED;
        } catch (IllegalStateException failure) {
            close();
            return result = Result.START_FAILED;
        }
    }

    synchronized int feed(long ownerGeneration, short[] pcm, int frames) {
        return feed(ownerGeneration, pcm, frames, System.nanoTime());
    }

    synchronized int feed(long ownerGeneration, short[] pcm, int frames, long enqueuedNs) {
        if (ownerGeneration != generation || !active || track == null || frames <= 0 || frames > 960 ||
                pcm == null || frames > pcm.length) return -1;
        long age = System.nanoTime() - enqueuedNs;
        long nowNs = System.nanoTime();
        long feedGapNs = previousFeedNs == 0 ? 0 : nowNs - previousFeedNs;
        previousFeedNs = nowNs;
        int underruns = track.getUnderrunCount();
        if (underruns != observedTrackUnderruns && underrunObservations++ < 32) {
            Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_TRACK_STARVATION generation=" + generation +
                    ";count=" + underruns + ";previous=" + observedTrackUnderruns +
                    ";accepted_since_flush=" + acceptedSinceFlush +
                    ";head_frames=" + Integer.toUnsignedLong(track.getPlaybackHeadPosition()) +
                    ";buffer_frames=" + track.getBufferSizeInFrames() +
                    ";capacity_frames=" + track.getBufferCapacityInFrames() +
                    ";feed_gap_ns=" + feedGapNs + ";pcm_age_ns=" + age +
                    ";consumer_elapsed_ns=" + (consumerSinceNs == 0 ? -1 : nowNs - consumerSinceNs) +
                    ";consumer_active=" + consumerActive);
        }
        observedTrackUnderruns = underruns;
        if (!DirectShellMicrophoneFrame.isFresh(enqueuedNs, System.nanoTime())) {
            staleFrames += frames;
            return DirectShellMicrophoneFrame.DROP_STALE;
        }
        if (!consumerActive || consumerTransition) {
            // Android activates the recorder mix's output only after data is supplied.
            // Keep that route selectable without retaining unheard microphone content.
            if (mayMaintainSilence(consumerActive, consumerTransition)) {
                long writeStartedNs = System.nanoTime();
                // Keep the existing Android buffer supplied, independently of remote packet size.
                int silent = track.write(idleSilence, 0, idleSilence.length, AudioTrack.WRITE_NON_BLOCKING);
                maxWriteNs = Math.max(maxWriteNs, System.nanoTime() - writeStartedNs);
                if (silent < 0) return silent;
                if (silent > 0) {
                    idleSilenceFrames += silent;
                    acceptedSinceFlush += silent;
                }
            }
            noConsumerFrames += frames;
            sample();
            return DirectShellMicrophoneFrame.DROP_NO_CONSUMER;
        }
        // Delivery-time presence must not admit PCM queued before this reader window existed.
        if (!DirectShellMicrophoneFrame.belongsToConsumerWindow(enqueuedNs, consumerSinceNs)) {
            transitionFrames += frames;
            sample();
            return DirectShellMicrophoneFrame.DROP_CONSUMER_TRANSITION;
        }
        maxPcmAgeNs = Math.max(maxPcmAgeNs, age);
        long writeStartedNs = System.nanoTime();
        int written = track.write(pcm, 0, frames, AudioTrack.WRITE_NON_BLOCKING);
        maxWriteNs = Math.max(maxWriteNs, System.nanoTime() - writeStartedNs);
        if (written > 0) {
            writtenFrames += written;
            acceptedSinceFlush += written;
        }
        if (written == 0) zeroWrites++;
        else if (written > 0 && written < frames) partialWrites++;
        sample();
        return written;
    }

    synchronized String diagnostics() {
        return "remote_mic_result=" + result + ";generation=" + generation + ";uid_rules=NONE" +
                ";criteria_count=" + presets.length + ";registration=" + registration +
                ";frames_written=" + writtenFrames + ";no_consumer_frames=" + noConsumerFrames +
                ";stale_frames=" + staleFrames + ";idle_silence_frames=" + idleSilenceFrames +
                ";transition_frames=" + transitionFrames + ";consumer_since_ns=" + consumerSinceNs +
                ";max_pcm_age_ns=" + maxPcmAgeNs + ";zero_writes=" + zeroWrites +
                ";partial_writes=" + partialWrites + ";consumer_active=" + consumerActive +
                ";max_write_ns=" + maxWriteNs + ";max_consumer_transition_ns=" + maxConsumerTransitionNs +
                ";track_underruns=" + (track == null ? -1 : track.getUnderrunCount());
    }

    private void sample() {
        long now = System.nanoTime();
        if (now >= nextSampleNs) {
            Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_POLICY_PROGRESS " + diagnostics());
            nextSampleNs = now + 5_000_000_000L;
        }
    }

    private void updateConsumers(List<AudioRecordingConfiguration> configs) {
        final String ownedRegistration;
        synchronized (this) {
            if (!registered || track == null) return;
            ownedRegistration = registration;
        }
        // getAudioDevice resolves framework patches through Binder; never hold the feed lock here.
        boolean present = false;
        for (AudioRecordingConfiguration config : configs) {
            AudioDeviceInfo device = config.getAudioDevice();
            if (device != null && matchesConsumer(device.getType(), device.getAddress(),
                    config.isClientSilenced(), ownedRegistration)) {
                present = true;
            }
        }
        AudioTrack transitioningTrack = null;
        synchronized (this) {
            if (!registered || track == null || !ownedRegistration.equals(registration) ||
                    consumerActive == present) return;
            consumerActive = present;
            consumerSinceNs = present ? System.nanoTime() : 0;
            if (active && !present) {
                consumerTransition = true;
                transitioningTrack = track;
            }
            Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_CONSUMER generation=" + generation +
                    ";active=" + present + ";registration=" + registration + ";uid_rules=NONE");
        }
        if (transitioningTrack != null) {
            // Framework pause/flush/play may block. Feed must keep discarding unheard PCM meanwhile.
            long transitionStartedNs = System.nanoTime();
            try {
                transitioningTrack.pause();
                transitioningTrack.flush();
                transitioningTrack.play();
            } catch (IllegalStateException cancelled) {
                synchronized (this) {
                    if (track == transitioningTrack && registered) result = Result.START_FAILED;
                }
            } finally {
                long transitionNs = System.nanoTime() - transitionStartedNs;
                synchronized (this) {
                    if (track == transitioningTrack) consumerTransition = false;
                    if (track == transitioningTrack) acceptedSinceFlush = 0;
                    maxConsumerTransitionNs = Math.max(maxConsumerTransitionNs, transitionNs);
                }
                Log.i("WarpnectRemoteMicPolicy", "REMOTE_MIC_TRANSITION_TIMING generation=" + generation +
                        ";elapsed_ns=" + transitionNs + ";feed_lock_held=false");
            }
        }
    }

    static boolean mayMaintainSilence(boolean consumerActive, boolean consumerTransition) {
        return !consumerActive && !consumerTransition;
    }

    static boolean matchesConsumer(int type, String address, boolean silenced, String registration) {
        return !silenced && type == 25 && registration != null && !registration.isEmpty() &&
                registration.equals(address);
    }

    @Override public synchronized void close() {
        active = false;
        consumerActive = false;
        consumerTransition = false;
        consumerSinceNs = 0;
        if (recordingCallback != null) {
            manager.unregisterAudioRecordingCallback(recordingCallback);
            recordingCallback = null;
        }
        if (recordingThread != null) {
            recordingThread.quitSafely();
            recordingThread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
            track = null;
        }
        if (registered) {
            try {
                AudioManager.class.getMethod("unregisterAudioPolicy", policy.getClass()).invoke(manager, policy);
            } catch (ReflectiveOperationException failure) {
                System.out.println("remote_mic_cleanup=UNREGISTER_FAILED");
            }
        }
        registered = false;
        policy = null;
        result = Result.API_UNAVAILABLE;
    }
}
