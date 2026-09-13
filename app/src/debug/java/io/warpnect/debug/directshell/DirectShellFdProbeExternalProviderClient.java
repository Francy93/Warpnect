package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SharedMemory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Shell-side acquisition of the DEBUG provider through ActivityManager's external-provider API.
 *
 * <p>This is intentionally a narrow experimental use of the framework's external-provider path:
 * app_process is not a registered application process, so ordinary Context service/provider
 * acquisition cannot represent it correctly. The returned provider still observes the real shell
 * Binder UID and validates the per-generation proof before returning a descriptor.</p>
 */
@TargetApi(android.os.Build.VERSION_CODES.S)
final class DirectShellFdProbeExternalProviderClient implements AutoCloseable {
    // app_process launched through adb shell is the system-user shell process (UID 2000).
    private static final int ADB_SHELL_USER_ID = 0;

    private final Object activityManager;
    private final Object provider;
    private final Method providerCall;
    private final IBinder externalToken;
    private final AttributionSource attributionSource;
    private final int userId;
    private boolean closed;

    private DirectShellFdProbeExternalProviderClient(
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
        int userId = ADB_SHELL_USER_ID;
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
                    DirectShellFdProbeBinderProtocol.PROVIDER_AUTHORITY,
                    userId,
                    token,
                    "warpnect-directshell-fd"
            );
            if (holder == null) {
                return OpenResult.failure("EXTERNAL_PROVIDER_NOT_FOUND");
            }
            Field providerField = holder.getClass().getDeclaredField("provider");
            providerField.setAccessible(true);
            Object provider = providerField.get(holder);
            if (provider == null) {
                return OpenResult.failure("EXTERNAL_PROVIDER_NULL");
            }
            Class<?> providerInterface = Class.forName("android.content.IContentProvider");
            Method providerCall = providerInterface.getMethod(
                    "call",
                    AttributionSource.class,
                    String.class,
                    String.class,
                    String.class,
                    Bundle.class
            );
            return OpenResult.success(new DirectShellFdProbeExternalProviderClient(
                    activityManager,
                    provider,
                    providerCall,
                    token,
                    attributionSource,
                    userId
            ));
        } catch (Throwable throwable) {
            return OpenResult.failure("EXTERNAL_PROVIDER_ACQUIRE_FAILED_" +
                    rootName(throwable));
        }
    }

    DescriptorResult requestDescriptor(
            byte[] secret,
            long generationId,
            int magic,
            int version,
            int regionBytes
    ) {
        byte[] proof = DirectShellFdProbeBinderProtocol.createProof(
                secret,
                generationId,
                magic,
                version,
                regionBytes
        );
        try {
            return requestDescriptorWithProof(generationId, magic, version, regionBytes, proof);
        } finally {
            Arrays.fill(proof, (byte) 0);
        }
    }

    DescriptorResult requestDescriptorWithProof(
            long generationId,
            int magic,
            int version,
            int regionBytes,
            byte[] proof
    ) {
        if (closed) {
            return DescriptorResult.failure(
                    DirectShellFdProbeBinderProtocol.Result.NOT_ARMED,
                    "EXTERNAL_PROVIDER_CLOSED"
            );
        }
        Bundle request = new Bundle();
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_MAGIC, magic);
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_VERSION, version);
        request.putLong(DirectShellFdProbeBinderProtocol.KEY_GENERATION_ID, generationId);
        request.putInt(DirectShellFdProbeBinderProtocol.KEY_REGION_BYTES, regionBytes);
        request.putByteArray(DirectShellFdProbeBinderProtocol.KEY_PROOF, proof);
        try {
            Bundle response = (Bundle) providerCall.invoke(
                    provider,
                    attributionSource,
                    DirectShellFdProbeBinderProtocol.PROVIDER_AUTHORITY,
                    DirectShellFdProbeBinderProtocol.PROVIDER_METHOD_OPEN_SHARED_MEMORY,
                    null,
                    request
            );
            if (response == null) {
                return DescriptorResult.failure(
                        DirectShellFdProbeBinderProtocol.Result.INTERNAL_ERROR,
                        "EXTERNAL_PROVIDER_NULL_RESPONSE"
                );
            }
            response.setClassLoader(SharedMemory.class.getClassLoader());
            DirectShellFdProbeBinderProtocol.Result result =
                    DirectShellFdProbeBinderProtocol.Result.fromCode(response.getInt(
                            DirectShellFdProbeBinderProtocol.KEY_RESULT_CODE,
                            DirectShellFdProbeBinderProtocol.Result.INTERNAL_ERROR.code
                    ));
            String detail = response.getString(DirectShellFdProbeBinderProtocol.KEY_DETAIL, "none");
            SharedMemory memory = response.getParcelable(
                    DirectShellFdProbeBinderProtocol.KEY_SHARED_MEMORY
            );
            if (result != DirectShellFdProbeBinderProtocol.Result.OK || memory == null) {
                return DescriptorResult.failure(result, detail + " descriptor=" + (memory != null));
            }
            return DescriptorResult.success(memory, detail);
        } catch (Throwable throwable) {
            return DescriptorResult.failure(
                    DirectShellFdProbeBinderProtocol.Result.INTERNAL_ERROR,
                    "EXTERNAL_PROVIDER_CALL_FAILED_" + rootName(throwable)
            );
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
                    DirectShellFdProbeBinderProtocol.PROVIDER_AUTHORITY,
                    externalToken,
                    userId
            );
            return "EXTERNAL_PROVIDER_RELEASED";
        } catch (Throwable throwable) {
            // The token's process exit still releases the external provider reference.
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
        final DirectShellFdProbeExternalProviderClient client;
        final String detail;

        private OpenResult(DirectShellFdProbeExternalProviderClient client, String detail) {
            this.client = client;
            this.detail = detail;
        }

        static OpenResult success(DirectShellFdProbeExternalProviderClient client) {
            return new OpenResult(client, "EXTERNAL_PROVIDER_ACQUIRED");
        }

        static OpenResult failure(String detail) {
            return new OpenResult(null, detail);
        }

        boolean isSuccess() {
            return client != null;
        }
    }

    static final class DescriptorResult {
        final DirectShellFdProbeBinderProtocol.Result result;
        final SharedMemory sharedMemory;
        final String detail;

        private DescriptorResult(
                DirectShellFdProbeBinderProtocol.Result result,
                SharedMemory sharedMemory,
                String detail
        ) {
            this.result = result;
            this.sharedMemory = sharedMemory;
            this.detail = detail;
        }

        static DescriptorResult success(SharedMemory sharedMemory, String detail) {
            return new DescriptorResult(DirectShellFdProbeBinderProtocol.Result.OK, sharedMemory, detail);
        }

        static DescriptorResult failure(DirectShellFdProbeBinderProtocol.Result result, String detail) {
            return new DescriptorResult(result, null, detail);
        }
    }
}
