package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated shell framework thread for the DEBUG external-provider bridge.
 *
 * <p>The shell identity Context is used only to construct a valid shell AttributionSource. A
 * package Context is observed for diagnostics; no Warpnect Application is created in shell.</p>
 */
@TargetApi(android.os.Build.VERSION_CODES.S)
final class DirectShellFdProbeFrameworkBridge implements AutoCloseable {
    private static final long START_TIMEOUT_MILLIS = 3_000L;
    private final CountDownLatch initialized = new CountDownLatch(1);
    private final AtomicReference<StartResult> startResult = new AtomicReference<>();
    private Thread frameworkThread;
    private Handler handler;
    private Context shellIdentityContext;

    StartResult start() {
        if (frameworkThread != null) return startResult.get();
        frameworkThread = new Thread(this::runFrameworkLoop, "WarpnectDirectShellFdFramework");
        frameworkThread.start();
        try {
            if (!initialized.await(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return StartResult.failure("FRAMEWORK_CONTEXT_TIMEOUT", "none");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return StartResult.failure("FRAMEWORK_CONTEXT_INTERRUPTED", "none");
        }
        return startResult.get();
    }

    Context shellIdentityContext() {
        StartResult started = startResult.get();
        return started != null && started.available ? shellIdentityContext : null;
    }

    @Override
    public void close() {
        Handler currentHandler = handler;
        if (currentHandler != null) {
            CountDownLatch quitPosted = new CountDownLatch(1);
            currentHandler.post(() -> {
                quitPosted.countDown();
                Looper.myLooper().quitSafely();
            });
            await(quitPosted, 500L);
        }
        Thread thread = frameworkThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runFrameworkLoop() {
        try {
            Looper.prepare();
            DirectShellShellAudioContext.Result shell = DirectShellShellAudioContext.create();
            if (!shell.isAvailable()) {
                startResult.set(StartResult.failure("SHELL_CONTEXT_UNAVAILABLE", shell.classification));
                initialized.countDown();
                return;
            }
            shellIdentityContext = shell.context;
            AttributionSource attribution = shell.context.getAttributionSource();
            Context packageContext = shell.context.createPackageContext(
                    DirectShellShellAudioContext.SHELL_PACKAGE_NAME,
                    0
            );
            String packageName = packageContext.getPackageName();
            String opPackageName = packageContext.getOpPackageName();
            if (!DirectShellShellAudioContext.SHELL_PACKAGE_NAME.equals(packageName)) {
                startResult.set(StartResult.failure(
                        "SHELL_PACKAGE_CONTEXT_PACKAGE_MISMATCH",
                        "package=" + safe(packageName) + " op_package=" + safe(opPackageName)
                ));
                initialized.countDown();
                return;
            }
            handler = new Handler(Looper.myLooper());
            startResult.set(StartResult.available(
                    packageContext.getClass().getName(),
                    packageName,
                    opPackageName,
                    shell.context.getPackageName(),
                    shell.context.getOpPackageName(),
                    attribution == null ? "none" : String.valueOf(attribution.getUid()),
                    attribution == null ? "none" : attribution.getPackageName()
            ));
            initialized.countDown();
            Looper.loop();
        } catch (Throwable throwable) {
            startResult.set(StartResult.failure(
                    "SHELL_PACKAGE_CONTEXT_FAILED_" + throwable.getClass().getSimpleName(),
                    safe(throwable.getMessage())
            ));
            initialized.countDown();
        }
    }

    private static boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String safe(String value) {
        if (value == null) return "none";
        String sanitized = value.replace('\n', '_').replace('\r', '_').replace('\u0000', '_');
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180) + "...";
    }

    static final class StartResult {
        final boolean available;
        final String classification;
        final String detail;
        final String contextClass;
        final String packageName;
        final String opPackageName;
        final String shellIdentityPackage;
        final String shellIdentityOpPackage;
        final String shellAttributionUid;
        final String shellAttributionPackage;

        private StartResult(
                boolean available,
                String classification,
                String detail,
                String contextClass,
                String packageName,
                String opPackageName,
                String shellIdentityPackage,
                String shellIdentityOpPackage,
                String shellAttributionUid,
                String shellAttributionPackage
        ) {
            this.available = available;
            this.classification = classification;
            this.detail = detail;
            this.contextClass = contextClass;
            this.packageName = packageName;
            this.opPackageName = opPackageName;
            this.shellIdentityPackage = shellIdentityPackage;
            this.shellIdentityOpPackage = shellIdentityOpPackage;
            this.shellAttributionUid = shellAttributionUid;
            this.shellAttributionPackage = shellAttributionPackage;
        }

        static StartResult available(
                String contextClass,
                String packageName,
                String opPackageName,
                String shellIdentityPackage,
                String shellIdentityOpPackage,
                String shellAttributionUid,
                String shellAttributionPackage
        ) {
            return new StartResult(
                    true,
                    "AVAILABLE",
                    "none",
                    contextClass,
                    packageName,
                    opPackageName,
                    shellIdentityPackage,
                    shellIdentityOpPackage,
                    shellAttributionUid,
                    shellAttributionPackage
            );
        }

        static StartResult failure(String classification, String detail) {
            return new StartResult(
                    false,
                    classification,
                    detail,
                    "none",
                    "none",
                    "none",
                    "none",
                    "none",
                    "none",
                    "none"
            );
        }

        String payload() {
            return "framework_context=" + classification +
                    "\nframework_detail=" + detail +
                    "\nbinding_context_class=" + contextClass +
                    "\nbinding_package=" + packageName +
                    "\nbinding_op_package=" + opPackageName +
                    "\nshell_identity_package=" + shellIdentityPackage +
                    "\nshell_identity_op_package=" + shellIdentityOpPackage +
                    "\nshell_attribution_uid=" + shellAttributionUid +
                    "\nshell_attribution_package=" + shellAttributionPackage;
        }
    }

}
