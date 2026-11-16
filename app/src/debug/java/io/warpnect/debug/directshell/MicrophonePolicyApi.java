package io.warpnect.debug.directshell;

import android.media.AudioDeviceInfo;
import android.media.AudioRecordingConfiguration;
import android.media.AudioAttributes;

import java.lang.reflect.Method;
import java.util.List;

/** Framework signature differences only; an adapter never grants routing authority. */
@android.annotation.SuppressLint("NewApi") // Runtime SDK is read from the actual app_process platform.
final class MicrophonePolicyApi {
    enum Adapter { UNSUPPORTED, LEGACY_PACKAGE, ATTRIBUTED }

    private MicrophonePolicyApi() { }

    static Adapter adapterForSdk(int sdk) {
        if (sdk < 26) return Adapter.UNSUPPORTED;
        return sdk < 31 ? Adapter.LEGACY_PACKAGE : Adapter.ATTRIBUTED;
    }

    static String address(AudioDeviceInfo device) {
        if (DirectShellRuntimeApi.sdkInt() >= 28) return device.getAddress();
        // Present but hidden in Android8's SDK, used to match this policy's exact registration.
        try {
            return (String) device.getClass().getMethod("getAddress").invoke(device);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return null; // Unknown device identity must never count as our consumer.
        }
    }

    @android.annotation.SuppressLint("SoonBlockedPrivateApi") // Android8 only, before hidden-API enforcement.
    static int deviceType(AudioDeviceInfo device) {
        int publicType = device.getType();
        if (publicType != 0 || DirectShellRuntimeApi.sdkInt() >= 28) return publicType;
        try {
            // Android8 omits RemoteSubmix from its public type mapping, not from the policy port.
            java.lang.reflect.Field field = AudioDeviceInfo.class.getDeclaredField("mPort");
            field.setAccessible(true);
            Object port = field.get(device);
            Method type = port.getClass().getDeclaredMethod("type");
            type.setAccessible(true);
            int internalType = (Integer) type.invoke(port);
            int submixType = Class.forName("android.media.AudioSystem")
                    .getField("DEVICE_IN_REMOTE_SUBMIX").getInt(null);
            return normalizedType(publicType, internalType, submixType);
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return publicType;
        }
    }

    static int normalizedType(int publicType, int internalType, int submixType) {
        return publicType == 0 && submixType != 0 && internalType == submixType ? 25 : publicType;
    }

    static boolean silenced(AudioRecordingConfiguration config) {
        return DirectShellRuntimeApi.sdkInt() >= 29 && config.isClientSilenced();
    }

    static boolean isCapturePresetCriterion(int rule) {
        return rule == 2; // RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET, not UID/user/exclusion.
    }

    static Object buildCaptureRules(int[] presets) throws ReflectiveOperationException {
        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> builderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        int captureRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET").getInt(null);
        Object builder = builderClass.getConstructor().newInstance();
        for (int preset : presets) {
            AudioAttributes.Builder attributes = new AudioAttributes.Builder();
            AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class).invoke(attributes, preset);
            builderClass.getMethod("addRule", AudioAttributes.class, int.class)
                    .invoke(builder, attributes.build(), captureRule);
        }
        Object rule = builderClass.getMethod("build").invoke(builder);
        if (!auditCaptureRules(rule, presets.length)) throw new IllegalArgumentException("capture_rule_audit_failed");
        return rule;
    }

    static boolean auditCaptureRules(Object rule, int expectedCount) throws ReflectiveOperationException {
        Method criteria = rule.getClass().getDeclaredMethod("getCriteria");
        criteria.setAccessible(true);
        List<?> built = (List<?>) criteria.invoke(rule);
        if (expectedCount < 1 || expectedCount > 3 || built.size() != expectedCount) return false;
        for (Object criterion : built) {
            int value;
            try {
                value = (Integer) criterion.getClass().getMethod("getRule").invoke(criterion);
            } catch (NoSuchMethodException legacy) {
                // API26's criterion has no getter. Resolve the framework field, never guess a rule.
                java.lang.reflect.Field field = criterion.getClass().getDeclaredField("mRule");
                field.setAccessible(true);
                value = field.getInt(criterion);
            }
            if (!isCapturePresetCriterion(value)) return false;
        }
        return true;
    }
}
