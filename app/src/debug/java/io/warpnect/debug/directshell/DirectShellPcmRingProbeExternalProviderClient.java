package io.warpnect.debug.directshell;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SharedMemory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Shell-side acquisition of the one-purpose PCM-ring provider through ActivityManager's external
 * provider path. app_process is not a registered application process; this path preserves the
 * real shell Binder caller identity that the provider checks before returning any descriptor.
 */
@TargetApi(android.os.Build.VERSION_CODES.S)
final class DirectShellPcmRingProbeExternalProviderClient implements AutoCloseable {
    private static final int ADB_SHELL_USER_ID = 0;

    private final Object activityManager;
    private final Object provider;
    private final Method providerCall;
    private final IBinder externalToken;
    private final AttributionSource attributionSource;
    private final int userId;
    private boolean closed;

    private DirectShellPcmRingProbeExternalProviderClient(
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
                    DirectShellPcmRingProbeBinderProtocol.PROVIDER_AUTHORITY,
                    ADB_SHELL_USER_ID,
                    token,
                    "warpnect-directshell-pcm-ring"
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
            return OpenResult.success(new DirectShellPcmRingProbeExternalProviderClient(
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

    DescriptorSetResult requestDescriptorSet(
            byte[] secret,
            long generationId,
            int magic,
            int version,
            int regionBytes,
            int slotCount,
            int payloadCapacityBytes
    ) {
        byte[] proof = DirectShellPcmRingProbeBinderProtocol.createProof(
                secret,
                generationId,
                magic,
                version,
                regionBytes,
                slotCount,
                payloadCapacityBytes
        );
        try {
            return requestDescriptorSetWithProof(
                    generationId,
                    magic,
                    version,
                    regionBytes,
                    slotCount,
                    payloadCapacityBytes,
                    proof
            );
        } finally {
            Arrays.fill(proof, (byte) 0);
        }
    }

    DescriptorSetResult requestDescriptorSetWithProof(
            long generationId,
            int magic,
            int version,
            int regionBytes,
            int slotCount,
            int payloadCapacityBytes,
            byte[] proof
    ) {
        if (closed) {
            return DescriptorSetResult.failure(
                    DirectShellPcmRingProbeBinderProtocol.Result.NOT_ARMED,
                    "EXTERNAL_PROVIDER_CLOSED"
            );
        }
        Bundle request = new Bundle();
        request.putInt(DirectShellPcmRingProbeBinderProtocol.KEY_MAGIC, magic);
        request.putInt(DirectShellPcmRingProbeBinderProtocol.KEY_VERSION, version);
        request.putLong(DirectShellPcmRingProbeBinderProtocol.KEY_GENERATION_ID, generationId);
        request.putInt(DirectShellPcmRingProbeBinderProtocol.KEY_REGION_BYTES, regionBytes);
        request.putInt(DirectShellPcmRingProbeBinderProtocol.KEY_SLOT_COUNT, slotCount);
        request.putInt(
                DirectShellPcmRingProbeBinderProtocol.KEY_PAYLOAD_CAPACITY_BYTES,
                payloadCapacityBytes
        );
        request.putByteArray(DirectShellPcmRingProbeBinderProtocol.KEY_PROOF, proof);
        try {
            Bundle response = (Bundle) providerCall.invoke(
                    provider,
                    attributionSource,
                    DirectShellPcmRingProbeBinderProtocol.PROVIDER_AUTHORITY,
                    DirectShellPcmRingProbeBinderProtocol.PROVIDER_METHOD_OPEN_PCM_RING,
                    null,
                    request
            );
            if (response == null) {
                return DescriptorSetResult.failure(
                        DirectShellPcmRingProbeBinderProtocol.Result.INTERNAL_ERROR,
                        "EXTERNAL_PROVIDER_NULL_RESPONSE"
                );
            }
            response.setClassLoader(SharedMemory.class.getClassLoader());
            DirectShellPcmRingProbeBinderProtocol.Result result =
                    DirectShellPcmRingProbeBinderProtocol.Result.fromCode(response.getInt(
                            DirectShellPcmRingProbeBinderProtocol.KEY_RESULT_CODE,
                            DirectShellPcmRingProbeBinderProtocol.Result.INTERNAL_ERROR.code
                    ));
            String detail = response.getString(DirectShellPcmRingProbeBinderProtocol.KEY_DETAIL, "none");
            SharedMemory memory = response.getParcelable(
                    DirectShellPcmRingProbeBinderProtocol.KEY_SHARED_MEMORY
            );
            ParcelFileDescriptor notifyWrite = response.getParcelable(
                    DirectShellPcmRingProbeBinderProtocol.KEY_NOTIFY_WRITE_FD
            );
            ParcelFileDescriptor ackRead = response.getParcelable(
                    DirectShellPcmRingProbeBinderProtocol.KEY_ACK_READ_FD
            );
            if (result != DirectShellPcmRingProbeBinderProtocol.Result.OK || memory == null ||
                    notifyWrite == null || ackRead == null) {
                closeQuietly(memory);
                closeQuietly(notifyWrite);
                closeQuietly(ackRead);
                return DescriptorSetResult.failure(
                        result,
                        detail + " memory=" + (memory != null) +
                                " notify_write=" + (notifyWrite != null) +
                                " ack_read=" + (ackRead != null)
                );
            }
            return DescriptorSetResult.success(memory, notifyWrite, ackRead, detail);
        } catch (Throwable throwable) {
            return DescriptorSetResult.failure(
                    DirectShellPcmRingProbeBinderProtocol.Result.INTERNAL_ERROR,
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
                    DirectShellPcmRingProbeBinderProtocol.PROVIDER_AUTHORITY,
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

    private static void closeQuietly(SharedMemory memory) {
        if (memory == null) return;
        try {
            memory.close();
        } catch (RuntimeException ignored) {
            // Failure reporting remains focused on the first provider boundary.
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (Exception ignored) {
            // Failure reporting remains focused on the first provider boundary.
        }
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
        final DirectShellPcmRingProbeExternalProviderClient client;
        final String detail;

        private OpenResult(DirectShellPcmRingProbeExternalProviderClient client, String detail) {
            this.client = client;
            this.detail = detail;
        }

        static OpenResult success(DirectShellPcmRingProbeExternalProviderClient client) {
            return new OpenResult(client, "EXTERNAL_PROVIDER_ACQUIRED");
        }

        static OpenResult failure(String detail) {
            return new OpenResult(null, detail);
        }

        boolean isSuccess() {
            return client != null;
        }
    }

    static final class DescriptorSetResult implements AutoCloseable {
        final DirectShellPcmRingProbeBinderProtocol.Result result;
        final SharedMemory sharedMemory;
        final ParcelFileDescriptor notifyWriteFd;
        final ParcelFileDescriptor ackReadFd;
        final String detail;

        private DescriptorSetResult(
                DirectShellPcmRingProbeBinderProtocol.Result result,
                SharedMemory sharedMemory,
                ParcelFileDescriptor notifyWriteFd,
                ParcelFileDescriptor ackReadFd,
                String detail
        ) {
            this.result = result;
            this.sharedMemory = sharedMemory;
            this.notifyWriteFd = notifyWriteFd;
            this.ackReadFd = ackReadFd;
            this.detail = detail;
        }

        static DescriptorSetResult success(
                SharedMemory sharedMemory,
                ParcelFileDescriptor notifyWriteFd,
                ParcelFileDescriptor ackReadFd,
                String detail
        ) {
            return new DescriptorSetResult(
                    DirectShellPcmRingProbeBinderProtocol.Result.OK,
                    sharedMemory,
                    notifyWriteFd,
                    ackReadFd,
                    detail
            );
        }

        static DescriptorSetResult failure(
                DirectShellPcmRingProbeBinderProtocol.Result result,
                String detail
        ) {
            return new DescriptorSetResult(result, null, null, null, detail);
        }

        @Override
        public void close() {
            closeQuietly(notifyWriteFd);
            closeQuietly(ackReadFd);
            closeQuietly(sharedMemory);
        }
    }
}
