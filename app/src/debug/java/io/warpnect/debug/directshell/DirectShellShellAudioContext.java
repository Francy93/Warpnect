package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Method;

/**
 * A shell-identity wrapper around a real framework system Context.
 *
 * <p>This intentionally does not create an Application or pretend to be Warpnect. The wrapper
 * represents the actual app_process caller: UID 2000 and the installed {@code com.android.shell}
 * package. It is only used by the DEBUG REMOTE_SUBMIX feasibility probe.</p>
 */
@TargetApi(Build.VERSION_CODES.S)
final class DirectShellShellAudioContext extends ContextWrapper {
    static final String SHELL_PACKAGE_NAME = "com.android.shell";

    private static Context cachedSystemContext;
    private static boolean preparedThreadLooper;

    private final AttributionSource attributionSource;

    private DirectShellShellAudioContext(Context systemContext) {
        super(systemContext);
        attributionSource = new AttributionSource.Builder(Process.myUid())
                .setPackageName(SHELL_PACKAGE_NAME)
                .build();
    }

    static Result create() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure("API_LT_31_CONTEXT_UNSUPPORTED");
        }
        if (Process.myUid() != Process.SHELL_UID) {
            return Result.failure("IDENTITY_UNAVAILABLE_NOT_SHELL_UID");
        }
        try {
            Context systemContext = systemContext();
            if (systemContext == null) return Result.failure("CONTEXT_UNAVAILABLE_SYSTEM_MAIN_NULL");
            ApplicationInfo applicationInfo = systemContext.getPackageManager().getApplicationInfo(
                    SHELL_PACKAGE_NAME,
                    0
            );
            if (applicationInfo.uid != Process.SHELL_UID) {
                return Result.failure("IDENTITY_UNAVAILABLE_PACKAGE_UID_MISMATCH");
            }
            DirectShellShellAudioContext context = new DirectShellShellAudioContext(systemContext);
            AttributionSource attribution = context.getAttributionSource();
            if (attribution.getUid() != Process.SHELL_UID ||
                    !SHELL_PACKAGE_NAME.equals(attribution.getPackageName())) {
                return Result.failure("IDENTITY_UNAVAILABLE_ATTRIBUTION_MISMATCH");
            }
            return Result.available(
                    context,
                    systemContext.getPackageName(),
                    applicationInfo.uid,
                    preparedThreadLooper
            );
        } catch (Throwable throwable) {
            Throwable root = rootCause(throwable);
            return Result.failure(
                    "CONTEXT_UNAVAILABLE_" + root.getClass().getSimpleName() +
                            "_" + safe(root.getMessage()),
                    stackSummary(root)
            );
        }
    }

    @Override
    public String getPackageName() {
        return SHELL_PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return SHELL_PACKAGE_NAME;
    }

    @Override
    public AttributionSource getAttributionSource() {
        return attributionSource;
    }

    private static synchronized Context systemContext() throws Exception {
        if (cachedSystemContext != null) return cachedSystemContext;
        // ActivityThread.systemMain() creates framework Handlers. app_process does not prepare a
        // thread Looper for an arbitrary Java main(), so establish that normal framework
        // prerequisite without creating an Application or manipulating the global main Looper.
        if (Looper.myLooper() == null) {
            Looper.prepare();
            preparedThreadLooper = true;
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThread.setAccessible(true);
        Object activityThread = currentActivityThread.invoke(null);
        if (activityThread == null) {
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            activityThread = systemMain.invoke(null);
        }
        if (activityThread == null) return null;
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Object context = getSystemContext.invoke(activityThread);
        if (context instanceof Context) {
            cachedSystemContext = (Context) context;
        }
        return cachedSystemContext;
    }

    static final class Result {
        final DirectShellShellAudioContext context;
        final String classification;
        final String basePackageName;
        final int shellPackageUid;
        final String detail;
        final boolean threadLooperPrepared;

        private Result(
                DirectShellShellAudioContext context,
                String classification,
                String basePackageName,
                int shellPackageUid,
                String detail,
                boolean threadLooperPrepared
        ) {
            this.context = context;
            this.classification = classification;
            this.basePackageName = basePackageName;
            this.shellPackageUid = shellPackageUid;
            this.detail = detail;
            this.threadLooperPrepared = threadLooperPrepared;
        }

        static Result available(
                DirectShellShellAudioContext context,
                String basePackageName,
                int shellPackageUid,
                boolean threadLooperPrepared
        ) {
            return new Result(
                    context,
                    "AVAILABLE",
                    basePackageName,
                    shellPackageUid,
                    "none",
                    threadLooperPrepared
            );
        }

        static Result failure(String classification) {
            return failure(classification, "none");
        }

        static Result failure(String classification, String detail) {
            return new Result(null, classification, "UNAVAILABLE", -1, detail, preparedThreadLooper);
        }

        boolean isAvailable() {
            return context != null;
        }

        String toPayload() {
            if (!isAvailable()) {
                return "context_strategy=UNAVAILABLE\n" +
                        "context_classification=" + classification + '\n' +
                        "context_detail=" + detail;
            }
            AttributionSource attribution = context.getAttributionSource();
            return "context_strategy=ACTIVITY_THREAD_SYSTEM_MAIN" +
                    (threadLooperPrepared ? "_WITH_THREAD_LOOPER" : "_EXISTING_THREAD_LOOPER") + '\n' +
                    "context_classification=" + classification + '\n' +
                    "base_system_package=" + safe(basePackageName) + '\n' +
                    "package_name=" + context.getPackageName() + '\n' +
                    "op_package_name=" + context.getOpPackageName() + '\n' +
                    "attribution_uid=" + attribution.getUid() + '\n' +
                    "attribution_package=" + safe(attribution.getPackageName()) + '\n' +
                    "shell_package_uid=" + shellPackageUid;
        }
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 160 ? sanitized : sanitized.substring(0, 160) + "...";
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String stackSummary(Throwable throwable) {
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack.length == 0) return "none";
        StackTraceElement first = stack[0];
        return safe(first.getClassName() + '#' + first.getMethodName() + ':' + first.getLineNumber());
    }
}
