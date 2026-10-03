package io.warpnect.debug.directshell;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.Context;
import android.os.Process;

/** Bounded prerequisite-only probe. Synthetic output is never remote Session qualification. */
public final class GlobalMicrophonePolicyProbe {
    private GlobalMicrophonePolicyProbe() { }

    public static void main(String[] args) {
        boolean priorityOnly = args.length == 1 && "--priority-probe-only".equals(args[0]);
        if ((!priorityOnly && args.length != 0) || Process.myUid() != Process.SHELL_UID) System.exit(1);
        DirectShellShellAudioContext.Result identity = DirectShellShellAudioContext.create();
        if (!identity.isAvailable()) {
            System.out.println(identity.toPayload());
            System.exit(1);
        }
        AudioManager manager = (AudioManager) identity.context.getSystemService(Context.AUDIO_SERVICE);
        if (priorityOnly) {
            probePriority(manager);
            System.exit(0);
        }
        inputs(manager, "BEFORE");
        try (DirectShellRemoteMicrophonePolicy policy =
                     new DirectShellRemoteMicrophonePolicy(1, new int[] {1, 7, 5})) {
            DirectShellRemoteMicrophonePolicy.Result prepared = policy.prepare();
            System.out.println("global_probe_uid=" + Process.myUid() + ";pid=" + Process.myPid());
            System.out.println(policy.diagnostics());
            if (prepared == DirectShellRemoteMicrophonePolicy.Result.SUPPORTED) {
                System.out.println("global_probe_activate=" + policy.activate(1));
                inputs(manager, "ACTIVE");
                short[] pcm = new short[240];
                long deadline = System.nanoTime() + 25_000_000_000L;
                while (System.nanoTime() < deadline) {
                    int written = policy.feed(1, pcm, pcm.length);
                    if (written < 0) break;
                    if (written == 0) Thread.sleep(2);
                }
                System.out.println(policy.diagnostics());
            }
        } catch (Exception failure) {
            System.out.println("global_probe_failure=" + failure.getClass().getSimpleName());
        }
        inputs(manager, "AFTER");
        System.out.println("global_probe_complete=PREREQUISITE_ONLY");
        System.exit(0);
    }

    private static void inputs(AudioManager manager, String stage) {
        for (AudioDeviceInfo input : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            System.out.println("input_stage=" + stage + ";id=" + input.getId() + ";type=" + input.getType() +
                    ";address=" + (android.os.Build.VERSION.SDK_INT >= 28 ? input.getAddress() : "API_UNAVAILABLE") +
                    ";rates=" + java.util.Arrays.toString(input.getSampleRates()) +
                    ";channels=" + java.util.Arrays.toString(input.getChannelCounts()));
        }
    }

    private static void probePriority(AudioManager manager) {
        // Read-only investigation: these preferences are service-global, not binder-owned leases.
        try {
            Class<?> attributes = Class.forName("android.media.AudioDeviceAttributes");
            AudioManager.class.getMethod("setPreferredDeviceForCapturePreset", int.class, attributes);
            AudioManager.class.getMethod("clearPreferredDevicesForCapturePreset", int.class);
            java.lang.reflect.Method getter = AudioManager.class.getMethod(
                    "getPreferredDevicesForCapturePreset", int.class);
            for (int preset : new int[] {1, 7, 5}) {
                Object devices = getter.invoke(manager, preset);
                System.out.println("priority_probe_preset=" + preset + ";getter=AUTHORIZED;preferences=" + devices);
            }
            System.out.println("priority_probe_setter=API_PRESENT_NOT_INVOKED;clearer=API_PRESENT_NOT_INVOKED");
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException ?
                    failure.getCause() : failure;
            System.out.println("priority_probe_failure=" + cause.getClass().getSimpleName());
        }
    }
}
