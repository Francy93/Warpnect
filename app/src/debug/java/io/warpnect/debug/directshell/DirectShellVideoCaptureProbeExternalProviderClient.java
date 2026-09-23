package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Shell-side external-provider bridge for one app-owned encoder Surface.
 *
 * app_process has no registered app process identity. Acquiring the provider through ActivityManager
 * preserves the actual shell Binder caller identity which the normal-process provider verifies.
 */
@TargetApi(android.os.Build.VERSION_CODES.S)
final class DirectShellVideoCaptureProbeExternalProviderClient implements AutoCloseable {
    private static final int ADB_SHELL_USER_ID = 0;

    private final Object activityManager;
    private final Object provider;
    private final Method providerCall;
    private final IBinder externalToken;
    private final AttributionSource attributionSource;
    private final int userId;
    private boolean closed;

    private DirectShellVideoCaptureProbeExternalProviderClient(
            Object activityManager,
            Object provider,
            Method providerCall,
            IBinder externalToken,
            AttributionSource attributionSource,
            int userId
    ) {
        this.activityManager = activityManager;
        this.provider = provider;
        this.providerCall = providerCall;
        this.externalToken = externalToken;
        this.attributionSource = attributionSource;
        this.userId = userId;
    }

    static OpenResult open(Context shellIdentityContext) {
        if (Process.myUid() != Process.SHELL_UID) {
            return OpenResult.failure("EXTERNAL_PROVIDER_NOT_SHELL_UID");
        }
        AttributionSource attributionSource = shellIdentityContext.getAttributionSource();
        if (attributionSource == null || attributionSource.getUid() != Process.SHELL_UID ||
                !DirectShellShellAudioContext.SHELL_PACKAGE_NAME.equals(
                        attributionSource.getPackageName())) {
            return OpenResult.failure("EXTERNAL_PROVIDER_INVALID_ATTRIBUTION");
        }
        IBinder token = new Binder();
        try {
            Object activityManager = activityManager();
            Method acquire = activityManager.getClass().getMethod(
                    "getContentProviderExternal",
                    String.class,
                    int.class,
                    IBinder.class,
                    String.class
            );
            Object holder = acquire.invoke(
                    activityManager,
                    DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY,
                    ADB_SHELL_USER_ID,
                    token,
                    "warpnect-directshell-video-capture"
            );
            if (holder == null) return OpenResult.failure("EXTERNAL_PROVIDER_NOT_FOUND");
            Field providerField = holder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            Object provider = providerField.get(holder);
            if (provider == null) return OpenResult.failure("EXTERNAL_PROVIDER_NULL");
            Class<?> providerInterface = Class.forName("android.content.IContentProvider");
            Method providerCall = providerInterface.getMethod(
                    "call",
                    AttributionSource.class,
                    String.class,
                    String.class,
                    String.class,
                    Bundle.class
            );
            return OpenResult.success(new DirectShellVideoCaptureProbeExternalProviderClient(
                    activityManager,
                    provider,
                    providerCall,
                    token,
                    attributionSource,
                    ADB_SHELL_USER_ID
            ));
        } catch (Throwable throwable) {
            return OpenResult.failure("EXTERNAL_PROVIDER_ACQUIRE_FAILED_" + rootName(throwable));
        }
    }

    SurfaceResult requestSurface(byte[] secret, long generationId, int width, int height) {
        if (closed) {
            return SurfaceResult.failure(
                    DirectShellVideoCaptureProbeBinderProtocol.Result.NOT_ARMED,
                    "EXTERNAL_PROVIDER_CLOSED"
            );
        }
        byte[] proof = DirectShellVideoCaptureProbeBinderProtocol.createProof(
                secret,
                generationId,
                DirectShellVideoCaptureProbeBinderProtocol.MAGIC,
                DirectShellVideoCaptureProbeBinderProtocol.VERSION,
                width,
                height
        );
        try {
            Bundle request = new Bundle();
            request.putInt(
                    DirectShellVideoCaptureProbeBinderProtocol.KEY_MAGIC,
                    DirectShellVideoCaptureProbeBinderProtocol.MAGIC
            );
            request.putInt(
                    DirectShellVideoCaptureProbeBinderProtocol.KEY_VERSION,
                    DirectShellVideoCaptureProbeBinderProtocol.VERSION
            );
            request.putLong(DirectShellVideoCaptureProbeBinderProtocol.KEY_GENERATION_ID, generationId);
            request.putInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_WIDTH, width);
            request.putInt(DirectShellVideoCaptureProbeBinderProtocol.KEY_HEIGHT, height);
            request.putByteArray(DirectShellVideoCaptureProbeBinderProtocol.KEY_PROOF, proof);
            Bundle response = (Bundle) providerCall.invoke(
                    provider,
                    attributionSource,
                    DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY,
                    DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_METHOD_OPEN_ENCODER_SURFACE,
                    null,
                    request
            );
            if (response == null) {
                return SurfaceResult.failure(
                        DirectShellVideoCaptureProbeBinderProtocol.Result.INTERNAL_ERROR,
                        "EXTERNAL_PROVIDER_NULL_RESPONSE"
                );
            }
            response.setClassLoader(Surface.class.getClassLoader());
            DirectShellVideoCaptureProbeBinderProtocol.Result result =
                    DirectShellVideoCaptureProbeBinderProtocol.Result.fromCode(response.getInt(
                            DirectShellVideoCaptureProbeBinderProtocol.KEY_RESULT_CODE,
                            DirectShellVideoCaptureProbeBinderProtocol.Result.INTERNAL_ERROR.code
                    ));
            String detail = response.getString(DirectShellVideoCaptureProbeBinderProtocol.KEY_DETAIL, "none");
            Surface surface = response.getParcelable(DirectShellVideoCaptureProbeBinderProtocol.KEY_SURFACE);
            if (result != DirectShellVideoCaptureProbeBinderProtocol.Result.OK || surface == null ||
                    !surface.isValid()) {
                if (surface != null) surface.release();
                return SurfaceResult.failure(
                        result,
                        detail + " surface=" + (surface != null)
                );
            }
            return SurfaceResult.success(surface, detail);
        } catch (Throwable throwable) {
            return SurfaceResult.failure(
                    DirectShellVideoCaptureProbeBinderProtocol.Result.INTERNAL_ERROR,
                    "EXTERNAL_PROVIDER_CALL_FAILED_" + rootName(throwable)
            );
        } finally {
            Arrays.fill(proof, (byte) 0);
        }
    }

    @Override
    public void close() {
        release();
    }

    String release() {
        if (closed) return "EXTERNAL_PROVIDER_ALREADY_RELEASED";
        closed = true;
        try {
            Method release = activityManager.getClass().getMethod(
                    "removeContentProviderExternalAsUser",
                    String.class,
                    IBinder.class,
                    int.class
            );
            release.invoke(
                    activityManager,
                    DirectShellVideoCaptureProbeBinderProtocol.PROVIDER_AUTHORITY,
                    externalToken,
                    userId
            );
            return "EXTERNAL_PROVIDER_RELEASED";
        } catch (Throwable throwable) {
            return "EXTERNAL_PROVIDER_RELEASE_FAILED_" + rootName(throwable);
        }
    }

    private static Object activityManager() throws ReflectiveOperationException {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, "activity");
        if (binder == null) throw new IllegalStateException("activity_service_unavailable");
        Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
        Method asInterface = stub.getMethod("asInterface", IBinder.class);
        Object result = asInterface.invoke(null, binder);
        if (result == null) throw new IllegalStateException("activity_manager_unavailable");
        return result;
    }

    private static String rootName(Throwable throwable) {
        Throwable root = throwable;
        while (root instanceof InvocationTargetException &&
                ((InvocationTargetException) root).getCause() != null) {
            root = ((InvocationTargetException) root).getCause();
        }
        return root.getClass().getSimpleName();
    }

    static final class OpenResult {
        final DirectShellVideoCaptureProbeExternalProviderClient client;
        final String detail;

        private OpenResult(DirectShellVideoCaptureProbeExternalProviderClient client, String detail) {
            this.client = client;
            this.detail = detail;
        }

        static OpenResult success(DirectShellVideoCaptureProbeExternalProviderClient client) {
            return new OpenResult(client, "EXTERNAL_PROVIDER_ACQUIRED");
        }

        static OpenResult failure(String detail) {
            return new OpenResult(null, detail);
        }

        boolean isSuccess() {
            return client != null;
        }
    }

    static final class SurfaceResult implements AutoCloseable {
        final DirectShellVideoCaptureProbeBinderProtocol.Result result;
        final Surface surface;
        final String detail;

        private SurfaceResult(
                DirectShellVideoCaptureProbeBinderProtocol.Result result,
                Surface surface,
                String detail
        ) {
            this.result = result;
            this.surface = surface;
            this.detail = detail;
        }

        static SurfaceResult success(Surface surface, String detail) {
            return new SurfaceResult(DirectShellVideoCaptureProbeBinderProtocol.Result.OK, surface, detail);
        }

        static SurfaceResult failure(DirectShellVideoCaptureProbeBinderProtocol.Result result, String detail) {
            return new SurfaceResult(result, null, detail);
        }

        @Override
        public void close() {
            if (surface != null) surface.release();
        }
    }
}
